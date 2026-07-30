mod ffi;
mod layout;

use std::collections::{BTreeMap, BTreeSet};

use pam_native_protocol::{
    Layout, Mutation, Node, PATCH_MAGIC, Patch, PatchOperation, PropKey, PropertyPatch, TREE_MAGIC,
    Tree, encode_batch,
};

pub use ffi::{
    PamNativeBuffer, PamNativeEngineHandle, PamNativeStats, PamStatus, pam_native_buffer_free,
    pam_native_engine_commit, pam_native_engine_free, pam_native_engine_new,
    pam_native_engine_relayout, pam_native_engine_relayout_with_metrics,
    pam_native_engine_set_text_scale, pam_native_engine_set_viewport, pam_native_engine_stats,
};

#[derive(Debug)]
pub struct Engine {
    current: Option<Tree>,
    viewport: layout::Size,
    text_scale: f32,
    layouts: BTreeMap<u64, Layout>,
    commits: u64,
    created: u64,
    removed: u64,
    updated: u64,
    full_commits: u64,
    patch_commits: u64,
    input_bytes: u64,
    output_bytes: u64,
}

impl Default for Engine {
    fn default() -> Self {
        Self {
            current: None,
            viewport: layout::Size {
                width: 360.0,
                height: 800.0,
            },
            text_scale: 1.0,
            layouts: BTreeMap::new(),
            commits: 0,
            created: 0,
            removed: 0,
            updated: 0,
            full_commits: 0,
            patch_commits: 0,
            input_bytes: 0,
            output_bytes: 0,
        }
    }
}

impl Engine {
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }

    pub fn set_viewport(&mut self, width: f32, height: f32) -> Result<(), EngineError> {
        if !width.is_finite() || !height.is_finite() || width <= 0.0 || height <= 0.0 {
            return Err(EngineError::InvalidViewport);
        }
        self.viewport = layout::Size { width, height };
        Ok(())
    }

    pub fn set_text_scale(&mut self, text_scale: f32) -> Result<(), EngineError> {
        if !text_scale.is_finite() || text_scale <= 0.0 {
            return Err(EngineError::InvalidViewport);
        }
        self.text_scale = text_scale;
        Ok(())
    }

    pub fn relayout(&mut self, width: f32, height: f32) -> Result<Vec<u8>, EngineError> {
        self.relayout_with_metrics(width, height, self.text_scale)
    }

    pub fn relayout_with_metrics(
        &mut self,
        width: f32,
        height: f32,
        text_scale: f32,
    ) -> Result<Vec<u8>, EngineError> {
        self.set_viewport(width, height)?;
        self.set_text_scale(text_scale)?;
        let Some(current) = self.current.as_ref() else {
            return Ok(Vec::new());
        };
        let next_layouts =
            layout::calculate_with_text_scale(current, self.viewport, self.text_scale)?;
        let mutations = next_layouts
            .iter()
            .filter_map(|(id, frame)| {
                (self.layouts.get(id) != Some(frame)).then_some(Mutation::Layout {
                    id: *id,
                    frame: *frame,
                })
            })
            .collect::<Vec<_>>();
        self.layouts = next_layouts;
        if mutations.is_empty() {
            return Ok(Vec::new());
        }
        let output = encode_batch(&mutations).map_err(EngineError::Protocol)?;
        self.output_bytes = self
            .output_bytes
            .saturating_add(u64::try_from(output.len()).unwrap_or(u64::MAX));
        Ok(output)
    }

    pub fn commit(&mut self, frame: &[u8]) -> Result<Vec<u8>, EngineError> {
        let magic = frame.get(..4).ok_or(EngineError::Protocol(
            pam_native_protocol::ProtocolError::UnexpectedEnd,
        ))?;
        let output = if magic == TREE_MAGIC {
            self.commit_tree(frame)?
        } else if magic == PATCH_MAGIC {
            self.commit_patch(frame)?
        } else {
            return Err(EngineError::Protocol(
                pam_native_protocol::ProtocolError::InvalidMagic,
            ));
        };
        self.input_bytes = self
            .input_bytes
            .saturating_add(u64::try_from(frame.len()).unwrap_or(u64::MAX));
        self.output_bytes = self
            .output_bytes
            .saturating_add(u64::try_from(output.len()).unwrap_or(u64::MAX));
        Ok(output)
    }

    fn commit_tree(&mut self, frame: &[u8]) -> Result<Vec<u8>, EngineError> {
        let next = Tree::decode(frame).map_err(EngineError::Protocol)?;
        let next_layouts =
            layout::calculate_with_text_scale(&next, self.viewport, self.text_scale)?;
        let mut mutations = Vec::new();
        let next_children = child_index(&next);

        match &self.current {
            None => {
                mutations.push(Mutation::SetRoot { id: next.root });
                append_creates(&next, &next_children, next.root, &mut mutations);
            }
            Some(current) => diff(current, &next, &next_children, &mut mutations),
        }

        for (id, frame) in &next_layouts {
            if self.layouts.get(id) != Some(frame) {
                mutations.push(Mutation::Layout {
                    id: *id,
                    frame: *frame,
                });
            }
        }

        for mutation in &mutations {
            match mutation {
                Mutation::Create(_) => self.created = self.created.saturating_add(1),
                Mutation::Remove { .. } => self.removed = self.removed.saturating_add(1),
                Mutation::Update { .. } | Mutation::Move { .. } => {
                    self.updated = self.updated.saturating_add(1);
                }
                Mutation::Layout { .. } | Mutation::SetRoot { .. } => {}
            }
        }
        self.commits = self.commits.saturating_add(1);
        self.full_commits = self.full_commits.saturating_add(1);
        self.current = Some(next);
        self.layouts = next_layouts;
        encode_batch(&mutations).map_err(EngineError::Protocol)
    }

    fn commit_patch(&mut self, frame: &[u8]) -> Result<Vec<u8>, EngineError> {
        let patch = Patch::decode(frame).map_err(EngineError::Protocol)?;
        if !patch.is_property_only() {
            return self.commit_structural_patch(patch);
        }
        let updates = patch
            .operations
            .into_iter()
            .map(|operation| match operation {
                PatchOperation::Update(update) => update,
                _ => unreachable!("property-only patch was prevalidated"),
            })
            .collect::<Vec<_>>();
        let current = self.current.as_ref().ok_or(EngineError::PatchWithoutTree)?;
        for PropertyPatch { id, .. } in &updates {
            if !current.nodes.contains_key(id) {
                return Err(EngineError::UnknownPatchNode(*id));
            }
        }

        let mut mutations = Vec::with_capacity(updates.len());
        let mut layout_dirty = false;
        let current = self.current.as_mut().expect("validated current tree");
        for PropertyPatch { id, key, value } in updates {
            let node = current.nodes.get_mut(&id).expect("prevalidated patch node");
            let previous = match &value {
                Some(next) => node.properties.insert(key, next.clone()),
                None => node.properties.remove(&key),
            };
            if previous.as_ref() == value.as_ref() {
                continue;
            }
            layout_dirty |= affects_layout(key);
            mutations.push(Mutation::Update { id, key, value });
            self.updated = self.updated.saturating_add(1);
        }

        if layout_dirty {
            let next_layouts = layout::calculate_with_text_scale(
                self.current
                    .as_ref()
                    .expect("current tree remains available"),
                self.viewport,
                self.text_scale,
            )?;
            for (id, next) in &next_layouts {
                if self.layouts.get(id) != Some(next) {
                    mutations.push(Mutation::Layout {
                        id: *id,
                        frame: *next,
                    });
                }
            }
            self.layouts = next_layouts;
        }

        self.commits = self.commits.saturating_add(1);
        self.patch_commits = self.patch_commits.saturating_add(1);
        encode_batch(&mutations).map_err(EngineError::Protocol)
    }

    fn commit_structural_patch(&mut self, patch: Patch) -> Result<Vec<u8>, EngineError> {
        let current = self.current.as_ref().ok_or(EngineError::PatchWithoutTree)?;
        let mut next = current.clone();
        for operation in patch.operations {
            match operation {
                PatchOperation::Create(node) => {
                    let id = node.id;
                    if id == 0 || next.nodes.insert(id, node).is_some() {
                        return Err(EngineError::Protocol(
                            pam_native_protocol::ProtocolError::DuplicateNode(id),
                        ));
                    }
                }
                PatchOperation::Remove { id } => {
                    if next.nodes.remove(&id).is_none() {
                        return Err(EngineError::UnknownPatchNode(id));
                    }
                }
                PatchOperation::Update(PropertyPatch { id, key, value }) => {
                    let node = next
                        .nodes
                        .get_mut(&id)
                        .ok_or(EngineError::UnknownPatchNode(id))?;
                    match value {
                        Some(value) => {
                            node.properties.insert(key, value);
                        }
                        None => {
                            node.properties.remove(&key);
                        }
                    }
                }
                PatchOperation::Move { id, parent, index } => {
                    let node = next
                        .nodes
                        .get_mut(&id)
                        .ok_or(EngineError::UnknownPatchNode(id))?;
                    node.parent = parent;
                    node.index = index;
                }
                PatchOperation::SetRoot { id } => next.root = id,
            }
        }
        next.validate().map_err(EngineError::Protocol)?;

        let next_layouts =
            layout::calculate_with_text_scale(&next, self.viewport, self.text_scale)?;
        let next_children = child_index(&next);
        let mut mutations = Vec::new();
        diff(current, &next, &next_children, &mut mutations);
        for (id, next_frame) in &next_layouts {
            if self.layouts.get(id) != Some(next_frame) {
                mutations.push(Mutation::Layout {
                    id: *id,
                    frame: *next_frame,
                });
            }
        }
        for mutation in &mutations {
            match mutation {
                Mutation::Create(_) => self.created = self.created.saturating_add(1),
                Mutation::Remove { .. } => self.removed = self.removed.saturating_add(1),
                Mutation::Update { .. } | Mutation::Move { .. } => {
                    self.updated = self.updated.saturating_add(1);
                }
                Mutation::Layout { .. } | Mutation::SetRoot { .. } => {}
            }
        }
        self.current = Some(next);
        self.layouts = next_layouts;
        self.commits = self.commits.saturating_add(1);
        self.patch_commits = self.patch_commits.saturating_add(1);
        encode_batch(&mutations).map_err(EngineError::Protocol)
    }

    #[must_use]
    pub fn stats(&self) -> EngineStats {
        EngineStats {
            commits: self.commits,
            nodes: self
                .current
                .as_ref()
                .map_or(0, |tree| tree.nodes.len() as u64),
            created: self.created,
            removed: self.removed,
            updated: self.updated,
            retained_bytes: self.current.as_ref().map_or(0, estimated_tree_bytes) as u64,
            full_commits: self.full_commits,
            patch_commits: self.patch_commits,
            input_bytes: self.input_bytes,
            output_bytes: self.output_bytes,
        }
    }
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct EngineStats {
    pub commits: u64,
    pub nodes: u64,
    pub created: u64,
    pub removed: u64,
    pub updated: u64,
    pub retained_bytes: u64,
    pub full_commits: u64,
    pub patch_commits: u64,
    pub input_bytes: u64,
    pub output_bytes: u64,
}

#[derive(Debug)]
pub enum EngineError {
    Protocol(pam_native_protocol::ProtocolError),
    Layout(layout::LayoutError),
    InvalidViewport,
    PatchWithoutTree,
    UnknownPatchNode(u64),
}

impl std::fmt::Display for EngineError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Protocol(error) => error.fmt(formatter),
            Self::Layout(error) => error.fmt(formatter),
            Self::InvalidViewport => formatter.write_str("viewport must be finite and positive"),
            Self::PatchWithoutTree => formatter.write_str("patch requires an initial tree"),
            Self::UnknownPatchNode(id) => write!(formatter, "patch references unknown node {id}"),
        }
    }
}

impl std::error::Error for EngineError {}

impl From<layout::LayoutError> for EngineError {
    fn from(value: layout::LayoutError) -> Self {
        Self::Layout(value)
    }
}

type ChildIndex<'a> = BTreeMap<u64, Vec<&'a pam_native_protocol::Node>>;

fn child_index(tree: &Tree) -> ChildIndex<'_> {
    let mut children = ChildIndex::new();
    for node in tree.nodes.values() {
        children.entry(node.parent).or_default().push(node);
    }
    for siblings in children.values_mut() {
        siblings.sort_by_key(|node| node.index);
    }
    children
}

fn diff(
    current: &Tree,
    next: &Tree,
    next_children: &ChildIndex<'_>,
    mutations: &mut Vec<Mutation>,
) {
    let current_children = child_index(current);
    if current.root != next.root {
        let mut removed = BTreeSet::new();
        append_removes(&current_children, current.root, &mut removed, mutations);
        mutations.push(Mutation::SetRoot { id: next.root });
        append_creates(next, next_children, next.root, mutations);
        return;
    }

    let removed_ids = current
        .nodes
        .keys()
        .filter(|id| !next.nodes.contains_key(id))
        .copied()
        .collect::<BTreeSet<_>>();
    let removed_roots = removed_ids
        .iter()
        .filter(|id| {
            current
                .nodes
                .get(id)
                .is_none_or(|node| !removed_ids.contains(&node.parent))
        })
        .copied()
        .collect::<Vec<_>>();
    let mut removed = BTreeSet::new();
    for id in removed_roots {
        append_removes(&current_children, id, &mut removed, mutations);
    }

    let new_ids = next
        .nodes
        .keys()
        .filter(|id| !current.nodes.contains_key(id))
        .copied()
        .collect::<BTreeSet<_>>();
    let new_roots = new_ids
        .iter()
        .filter(|id| {
            next.nodes
                .get(id)
                .is_none_or(|node| !new_ids.contains(&node.parent))
        })
        .copied()
        .collect::<Vec<_>>();
    for id in new_roots {
        append_creates(next, next_children, id, mutations);
    }

    for (id, next_node) in &next.nodes {
        let Some(current_node) = current.nodes.get(id) else {
            continue;
        };
        if current_node.kind != next_node.kind {
            if !removed.contains(id) {
                append_removes(&current_children, *id, &mut removed, mutations);
            }
            append_creates(next, next_children, *id, mutations);
            continue;
        }
        if current_node.parent != next_node.parent || current_node.index != next_node.index {
            mutations.push(Mutation::Move {
                id: *id,
                parent: next_node.parent,
                index: next_node.index,
            });
        }
        for key in current_node
            .properties
            .keys()
            .chain(next_node.properties.keys())
            .copied()
            .collect::<BTreeSet<PropKey>>()
        {
            let current_value = current_node.properties.get(&key);
            let next_value = next_node.properties.get(&key);
            if current_value != next_value {
                mutations.push(Mutation::Update {
                    id: *id,
                    key,
                    value: next_value.cloned(),
                });
            }
        }
    }
}

fn append_creates(tree: &Tree, children: &ChildIndex<'_>, id: u64, mutations: &mut Vec<Mutation>) {
    let Some(node) = tree.nodes.get(&id) else {
        return;
    };
    mutations.push(Mutation::Create(node.clone()));
    for child in children.get(&id).map_or(&[][..], Vec::as_slice) {
        append_creates(tree, children, child.id, mutations);
    }
}

fn append_removes(
    children: &ChildIndex<'_>,
    id: u64,
    removed: &mut BTreeSet<u64>,
    mutations: &mut Vec<Mutation>,
) {
    if !removed.insert(id) {
        return;
    }
    for child in children.get(&id).map_or(&[][..], Vec::as_slice) {
        append_removes(children, child.id, removed, mutations);
    }
    mutations.push(Mutation::Remove { id });
}

fn affects_layout(key: PropKey) -> bool {
    matches!(
        key,
        PropKey::Text
            | PropKey::Width
            | PropKey::Height
            | PropKey::FlexGrow
            | PropKey::Padding
            | PropKey::Gap
            | PropKey::PaddingHorizontal
            | PropKey::PaddingVertical
            | PropKey::Margin
            | PropKey::MarginHorizontal
            | PropKey::MarginVertical
            | PropKey::MinWidth
            | PropKey::MinHeight
            | PropKey::MaxWidth
            | PropKey::MaxHeight
            | PropKey::AlignItems
            | PropKey::AlignSelf
            | PropKey::JustifyContent
            | PropKey::FontSize
            | PropKey::FontWeight
            | PropKey::NumberOfLines
            | PropKey::LetterSpacing
            | PropKey::LineHeight
            | PropKey::FlexDirection
            | PropKey::FlexWrap
            | PropKey::FlexShrink
            | PropKey::PaddingLeft
            | PropKey::PaddingTop
            | PropKey::PaddingRight
            | PropKey::PaddingBottom
            | PropKey::MarginLeft
            | PropKey::MarginTop
            | PropKey::MarginRight
            | PropKey::MarginBottom
            | PropKey::PositionType
            | PropKey::Left
            | PropKey::Top
            | PropKey::Right
            | PropKey::Bottom
            | PropKey::AspectRatio
            | PropKey::WidthPercent
            | PropKey::HeightPercent
            | PropKey::MaxWidthPercent
            | PropKey::MaxHeightPercent
            | PropKey::TextTransform
            | PropKey::FontStyle
            | PropKey::FontFamily
            | PropKey::TextAllowFontScaling
            | PropKey::TextMaxFontSizeMultiplier
            | PropKey::TextAdjustsFontSizeToFit
            | PropKey::TextMinimumFontScale
            | PropKey::Visible
            | PropKey::MarginLeftAuto
            | PropKey::ScrollHorizontal
            | PropKey::ScrollFillViewport
            | PropKey::GridColumns
            | PropKey::GridSpan
            | PropKey::GridSpanSm
            | PropKey::GridSpanMd
            | PropKey::GridSpanLg
            | PropKey::GridSpanXl
            | PropKey::GridOffset
            | PropKey::GridOffsetSm
            | PropKey::GridOffsetMd
            | PropKey::GridOffsetLg
            | PropKey::GridOffsetXl
            | PropKey::GridOrder
            | PropKey::GridOrderSm
            | PropKey::GridOrderMd
            | PropKey::GridOrderLg
            | PropKey::GridOrderXl
            | PropKey::GridColumnGap
            | PropKey::GridRowGap
    )
}

fn estimated_tree_bytes(tree: &Tree) -> usize {
    tree.nodes.values().fold(0_usize, |total, node| {
        total
            .saturating_add(std::mem::size_of::<Node>())
            .saturating_add(node.properties.values().fold(0_usize, |properties, value| {
                properties.saturating_add(match value {
                    pam_native_protocol::PropValue::String(value) => value.len(),
                    pam_native_protocol::PropValue::Bytes(value) => value.len(),
                    _ => std::mem::size_of_val(value),
                })
            }))
    })
}

#[cfg(test)]
mod tests {
    use std::collections::BTreeMap;

    use pam_native_protocol::{Mutation, NodeKind, PropValue, decode_batch};

    use super::*;

    fn frame(text: &str, include_button: bool) -> Vec<u8> {
        let mut nodes = BTreeMap::from([
            (
                1,
                Node {
                    id: 1,
                    parent: 0,
                    index: 0,
                    kind: NodeKind::Screen,
                    properties: BTreeMap::new(),
                },
            ),
            (
                2,
                Node {
                    id: 2,
                    parent: 1,
                    index: 0,
                    kind: NodeKind::Column,
                    properties: BTreeMap::from([(PropKey::Padding, PropValue::Float(16.0))]),
                },
            ),
            (
                3,
                Node {
                    id: 3,
                    parent: 2,
                    index: 0,
                    kind: NodeKind::Text,
                    properties: BTreeMap::from([(
                        PropKey::Text,
                        PropValue::String(text.to_owned()),
                    )]),
                },
            ),
        ]);
        if include_button {
            nodes.insert(
                4,
                Node {
                    id: 4,
                    parent: 2,
                    index: 1,
                    kind: NodeKind::Button,
                    properties: BTreeMap::from([(
                        PropKey::Text,
                        PropValue::String("Tap".to_owned()),
                    )]),
                },
            );
        }
        Tree { root: 1, nodes }.encode().expect("frame")
    }

    #[test]
    fn emits_incremental_updates() {
        let mut engine = Engine::new();
        let initial =
            decode_batch(&engine.commit(&frame("A", true)).expect("initial")).expect("batch");
        assert!(
            initial
                .iter()
                .filter(|item| matches!(item, Mutation::Create(_)))
                .count()
                == 4
        );

        let update =
            decode_batch(&engine.commit(&frame("B", false)).expect("update")).expect("batch");
        assert!(update.iter().any(|item| matches!(
            item,
            Mutation::Update {
                id: 3,
                key: PropKey::Text,
                ..
            }
        )));
        assert!(
            update
                .iter()
                .any(|item| matches!(item, Mutation::Remove { id: 4 }))
        );
        assert!(
            !update
                .iter()
                .any(|item| matches!(item, Mutation::Create(Node { id: 1, .. })))
        );
    }

    #[test]
    fn repeated_identical_frames_do_not_grow_retained_state() {
        let mut engine = Engine::new();
        let input = frame("stable", true);
        engine.commit(&input).expect("initial");
        let retained = engine.stats().retained_bytes;
        for _ in 0..10_000 {
            let batch = decode_batch(&engine.commit(&input).expect("commit")).expect("batch");
            assert!(batch.is_empty());
        }
        assert_eq!(engine.stats().retained_bytes, retained);
        assert_eq!(engine.stats().nodes, 4);
    }

    #[test]
    fn property_patch_skips_layout_for_paint_only_updates() {
        let mut engine = Engine::new();
        engine.commit(&frame("A", true)).expect("initial");
        let patch = Patch {
            operations: vec![PatchOperation::Update(PropertyPatch {
                id: 3,
                key: PropKey::TextColor,
                value: Some(PropValue::Integer(0xff112233)),
            })],
        }
        .encode()
        .expect("patch");

        let mutations = decode_batch(&engine.commit(&patch).expect("update")).expect("batch");
        assert_eq!(
            mutations
                .iter()
                .filter(|mutation| matches!(mutation, Mutation::Update { .. }))
                .count(),
            1
        );
        assert!(
            !mutations
                .iter()
                .any(|mutation| matches!(mutation, Mutation::Layout { .. }))
        );
        assert_eq!(engine.stats().patch_commits, 1);
    }

    #[test]
    fn layout_property_patch_emits_changed_layouts() {
        let mut engine = Engine::new();
        engine.commit(&frame("A", true)).expect("initial");
        let patch = Patch {
            operations: vec![PatchOperation::Update(PropertyPatch {
                id: 2,
                key: PropKey::Padding,
                value: Some(PropValue::Float(24.0)),
            })],
        }
        .encode()
        .expect("patch");

        let mutations = decode_batch(&engine.commit(&patch).expect("update")).expect("batch");
        assert!(
            mutations
                .iter()
                .any(|mutation| matches!(mutation, Mutation::Update { .. }))
        );
        assert!(
            mutations
                .iter()
                .any(|mutation| matches!(mutation, Mutation::Layout { .. }))
        );
    }

    #[test]
    fn text_patch_remeasures_wrapped_content() {
        let mut engine = Engine::new();
        engine.commit(&frame("A", false)).expect("initial");
        let patch = Patch {
            operations: vec![PatchOperation::Update(PropertyPatch {
                id: 3,
                key: PropKey::Text,
                value: Some(PropValue::String(
                    "PamUI content expands across enough words to require multiple lines on a phone"
                        .to_owned(),
                )),
            })],
        }
        .encode()
        .expect("patch");

        let mutations = decode_batch(&engine.commit(&patch).expect("update")).expect("batch");
        assert!(
            mutations
                .iter()
                .any(|mutation| matches!(mutation, Mutation::Layout { id: 2 | 3, .. }))
        );
    }

    #[test]
    fn scroll_extent_configuration_is_classified_as_layout_work() {
        assert!(affects_layout(PropKey::ScrollHorizontal));
        assert!(affects_layout(PropKey::ScrollFillViewport));
        assert!(!affects_layout(PropKey::ScrollDecelerationRate));
        assert!(!affects_layout(PropKey::ScrollContentOffsetX));
    }

    #[test]
    fn viewport_change_relayouts_retained_tree_without_a_php_commit() {
        let mut engine = Engine::new();
        engine.commit(&frame("A", true)).expect("initial");
        let commits = engine.stats().commits;
        let output_before = engine.stats().output_bytes;
        let mutations =
            decode_batch(&engine.relayout(720.0, 480.0).expect("relayout")).expect("batch");

        assert!(
            mutations
                .iter()
                .any(|mutation| matches!(mutation, Mutation::Layout { .. }))
        );
        assert_eq!(engine.stats().commits, commits);
        assert!(engine.stats().output_bytes > output_before);
        assert!(engine.relayout(720.0, 480.0).expect("stable").is_empty());
    }

    #[test]
    fn font_scale_change_relayouts_retained_text_without_a_php_commit() {
        let mut engine = Engine::new();
        engine.commit(&frame("PamUI", false)).expect("initial");

        let mutations = decode_batch(
            &engine
                .relayout_with_metrics(360.0, 800.0, 1.5)
                .expect("scaled relayout"),
        )
        .expect("batch");

        assert!(
            mutations
                .iter()
                .any(|mutation| matches!(mutation, Mutation::Layout { id: 2 | 3, .. }))
        );
    }

    #[test]
    fn structural_patch_creates_and_removes_nodes_transactionally() {
        let mut engine = Engine::new();
        engine.commit(&frame("A", true)).expect("initial");
        let patch = Patch {
            operations: vec![
                PatchOperation::Remove { id: 4 },
                PatchOperation::Create(Node {
                    id: 5,
                    parent: 2,
                    index: 1,
                    kind: NodeKind::Input,
                    properties: BTreeMap::from([(
                        PropKey::Value,
                        PropValue::String("Native".to_owned()),
                    )]),
                }),
            ],
        }
        .encode()
        .expect("patch");

        let mutations = decode_batch(&engine.commit(&patch).expect("update")).expect("batch");
        assert!(
            mutations
                .iter()
                .any(|mutation| matches!(mutation, Mutation::Remove { id: 4 }))
        );
        assert!(
            mutations
                .iter()
                .any(|mutation| matches!(mutation, Mutation::Create(Node { id: 5, .. })))
        );
    }
}
