pub mod bridge_v2;
mod ffi;
mod font_metrics;
mod layout;
pub mod performance;
pub mod reactive;
pub mod scheduler;
pub mod transaction;
pub mod ui_language;
pub mod virtualization;

use std::collections::{BTreeMap, BTreeSet};
use std::time::{Duration, Instant};

use pam_native_protocol::{
    Layout, Mutation, Node, PATCH_MAGIC, Patch, PatchOperation, PropKey, PropertyPatch, TREE_MAGIC,
    Tree, encode_batch_into,
};

pub use ffi::{
    PamNativeBuffer, PamNativeEngineHandle, PamNativeStats, PamStatus, pam_native_buffer_free,
    pam_native_engine_commit, pam_native_engine_free, pam_native_engine_last_error,
    pam_native_engine_new, pam_native_engine_relayout, pam_native_engine_relayout_with_metrics,
    pam_native_engine_set_asset_root, pam_native_engine_set_refresh_rate,
    pam_native_engine_set_text_scale, pam_native_engine_set_viewport, pam_native_engine_stats,
};

#[derive(Debug)]
pub struct Engine {
    current: Option<Tree>,
    viewport: layout::Size,
    text_scale: f32,
    font_metrics: font_metrics::FontMetricsCache,
    layouts: BTreeMap<u64, Layout>,
    commits: u64,
    created: u64,
    removed: u64,
    updated: u64,
    full_commits: u64,
    patch_commits: u64,
    input_bytes: u64,
    output_bytes: u64,
    frame_budget: Duration,
    performance: performance::PerformanceObserver,
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
            font_metrics: font_metrics::FontMetricsCache::default(),
            layouts: BTreeMap::new(),
            commits: 0,
            created: 0,
            removed: 0,
            updated: 0,
            full_commits: 0,
            patch_commits: 0,
            input_bytes: 0,
            output_bytes: 0,
            frame_budget: scheduler::RefreshRate::Hertz60.frame_budget(),
            performance: performance::PerformanceObserver::default(),
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

    pub fn set_refresh_rate(&mut self, refresh_rate_hz: f64) -> Result<(), EngineError> {
        if !refresh_rate_hz.is_finite() || refresh_rate_hz <= 0.0 {
            return Err(EngineError::InvalidViewport);
        }
        self.frame_budget = scheduler::RefreshRate::closest(refresh_rate_hz).frame_budget();
        Ok(())
    }

    pub(crate) fn text_scale(&self) -> f32 {
        self.text_scale
    }

    pub fn set_asset_root(&mut self, asset_root: impl Into<std::path::PathBuf>) {
        self.font_metrics.set_asset_root(asset_root);
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
        let mut output = Vec::new();
        self.relayout_with_metrics_into(width, height, text_scale, &mut output)?;
        Ok(output)
    }

    pub fn relayout_with_metrics_into(
        &mut self,
        width: f32,
        height: f32,
        text_scale: f32,
        output: &mut Vec<u8>,
    ) -> Result<(), EngineError> {
        output.clear();
        self.set_viewport(width, height)?;
        self.set_text_scale(text_scale)?;
        let Some(current) = self.current.as_ref() else {
            return Ok(());
        };
        let layout_started = Instant::now();
        let text_metrics = self.font_metrics.measure_tree(current);
        let next_layouts = layout::calculate_with_text_metrics(
            current,
            self.viewport,
            self.text_scale,
            text_metrics,
        )?;
        self.performance.record(
            performance::PerformanceStage::Layout,
            layout_started.elapsed(),
        );
        let mut mutations = next_layouts
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
            return Ok(());
        }
        self.encode_mutations(output, &mut mutations)?;
        self.output_bytes = self
            .output_bytes
            .saturating_add(u64::try_from(output.len()).unwrap_or(u64::MAX));
        Ok(())
    }

    pub fn commit(&mut self, frame: &[u8]) -> Result<Vec<u8>, EngineError> {
        let mut output = Vec::new();
        self.commit_into(frame, &mut output)?;
        Ok(output)
    }

    pub fn commit_into(&mut self, frame: &[u8], output: &mut Vec<u8>) -> Result<(), EngineError> {
        let frame_started = Instant::now();
        output.clear();
        let magic = frame.get(..4).ok_or(EngineError::Protocol(
            pam_native_protocol::ProtocolError::UnexpectedEnd,
        ))?;
        if magic == TREE_MAGIC {
            self.commit_tree(frame, output)?;
        } else if magic == PATCH_MAGIC {
            self.commit_patch(frame, output)?;
        } else {
            return Err(EngineError::Protocol(
                pam_native_protocol::ProtocolError::InvalidMagic,
            ));
        }
        self.input_bytes = self
            .input_bytes
            .saturating_add(u64::try_from(frame.len()).unwrap_or(u64::MAX));
        self.output_bytes = self
            .output_bytes
            .saturating_add(u64::try_from(output.len()).unwrap_or(u64::MAX));
        self.performance
            .frame_completed(frame_started.elapsed(), self.frame_budget);
        Ok(())
    }

    fn commit_tree(&mut self, frame: &[u8], output: &mut Vec<u8>) -> Result<(), EngineError> {
        let decode_started = Instant::now();
        let next = Tree::decode(frame).map_err(EngineError::Protocol)?;
        self.performance.record(
            performance::PerformanceStage::Decode,
            decode_started.elapsed(),
        );
        let layout_started = Instant::now();
        let text_metrics = self.font_metrics.measure_tree(&next);
        let next_layouts = layout::calculate_with_text_metrics(
            &next,
            self.viewport,
            self.text_scale,
            text_metrics,
        )?;
        self.performance.record(
            performance::PerformanceStage::Layout,
            layout_started.elapsed(),
        );
        let mut mutations = Vec::new();
        let next_children = child_index(&next);

        let reconcile_started = Instant::now();
        match &self.current {
            None => {
                mutations.push(Mutation::SetRoot { id: next.root });
                append_creates(&next, &next_children, next.root, &mut mutations);
            }
            Some(current) => diff(current, &next, &next_children, &mut mutations),
        }
        self.performance.record(
            performance::PerformanceStage::Reconcile,
            reconcile_started.elapsed(),
        );

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
        self.encode_mutations(output, &mut mutations)
    }

    fn commit_patch(&mut self, frame: &[u8], output: &mut Vec<u8>) -> Result<(), EngineError> {
        let decode_started = Instant::now();
        let patch = Patch::decode(frame).map_err(EngineError::Protocol)?;
        self.performance.record(
            performance::PerformanceStage::Decode,
            decode_started.elapsed(),
        );
        if !patch.is_property_only() {
            return self.commit_structural_patch(patch, output);
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
        let mut rollback = Vec::with_capacity(updates.len());
        let mut layout_dirty = false;
        let mut layout_dirty_nodes = BTreeSet::new();
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
            if affects_resolved_layout(node, key) {
                layout_dirty = true;
                layout_dirty_nodes.insert(id);
            }
            rollback.push(PropertyPatch {
                id,
                key,
                value: previous,
            });
            mutations.push(Mutation::Update { id, key, value });
        }

        let next_layouts = if layout_dirty {
            let layout_started = Instant::now();
            let current = self
                .current
                .as_ref()
                .expect("current tree remains available");
            let dirty_nodes = layout_dirty_nodes.iter().copied().collect::<Vec<_>>();
            let text_metrics = self.font_metrics.measure_nodes(current, &dirty_nodes);
            let calculated = layout::calculate_incremental_with_text_metrics(
                current,
                self.viewport,
                self.text_scale,
                text_metrics,
                &self.layouts,
                &layout_dirty_nodes,
            );
            let calculated = match calculated {
                Ok((layouts, _visited_nodes)) => layouts,
                Err(error) => {
                    rollback_property_updates(
                        self.current
                            .as_mut()
                            .expect("current tree remains available"),
                        rollback,
                    );
                    return Err(error.into());
                }
            };
            self.performance.record(
                performance::PerformanceStage::Layout,
                layout_started.elapsed(),
            );
            for (id, next) in &calculated {
                if self.layouts.get(id) != Some(next) {
                    mutations.push(Mutation::Layout {
                        id: *id,
                        frame: *next,
                    });
                }
            }
            Some(calculated)
        } else {
            None
        };

        match self.encode_mutations(output, &mut mutations) {
            Ok(()) => {}
            Err(error) => {
                rollback_property_updates(
                    self.current
                        .as_mut()
                        .expect("current tree remains available"),
                    rollback,
                );
                return Err(error);
            }
        }
        if let Some(next_layouts) = next_layouts {
            self.layouts = next_layouts;
        }
        self.updated = self
            .updated
            .saturating_add(u64::try_from(rollback.len()).unwrap_or(u64::MAX));
        self.commits = self.commits.saturating_add(1);
        self.patch_commits = self.patch_commits.saturating_add(1);
        Ok(())
    }

    fn commit_structural_patch(
        &mut self,
        patch: Patch,
        output: &mut Vec<u8>,
    ) -> Result<(), EngineError> {
        let current = self.current.as_ref().ok_or(EngineError::PatchWithoutTree)?;
        let reconcile_started = Instant::now();
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
        self.performance.record(
            performance::PerformanceStage::Reconcile,
            reconcile_started.elapsed(),
        );

        let layout_started = Instant::now();
        let text_metrics = self.font_metrics.measure_tree(&next);
        let next_layouts = layout::calculate_with_text_metrics(
            &next,
            self.viewport,
            self.text_scale,
            text_metrics,
        )?;
        self.performance.record(
            performance::PerformanceStage::Layout,
            layout_started.elapsed(),
        );
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
        self.encode_mutations(output, &mut mutations)
    }

    #[must_use]
    pub fn stats(&self) -> EngineStats {
        let performance = self.performance.snapshot();
        let percentile = |stage| {
            performance
                .stages
                .get(&stage)
                .map_or(0, |snapshot| snapshot.percentile_micros(95))
        };
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
            decode_p95_micros: percentile(performance::PerformanceStage::Decode),
            reconcile_p95_micros: percentile(performance::PerformanceStage::Reconcile),
            layout_p95_micros: percentile(performance::PerformanceStage::Layout),
            encode_p95_micros: percentile(performance::PerformanceStage::Encode),
            coalesced_commands: performance.coalesced_commands,
            deadline_misses: performance.deadline_misses,
            measured_frames: performance.frames,
        }
    }

    #[must_use]
    pub fn performance_snapshot(&self) -> performance::PerformanceSnapshot {
        self.performance.snapshot()
    }

    fn encode_mutations(
        &mut self,
        output: &mut Vec<u8>,
        mutations: &mut Vec<Mutation>,
    ) -> Result<(), EngineError> {
        let removed = bridge_v2::coalesce_in_place(mutations);
        self.performance.commands_coalesced(removed);
        let started = Instant::now();
        let result = encode_batch_into(output, mutations).map_err(EngineError::Protocol);
        self.performance
            .record(performance::PerformanceStage::Encode, started.elapsed());
        result
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
    pub decode_p95_micros: u64,
    pub reconcile_p95_micros: u64,
    pub layout_p95_micros: u64,
    pub encode_p95_micros: u64,
    pub coalesced_commands: u64,
    pub deadline_misses: u64,
    pub measured_frames: u64,
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

fn rollback_property_updates(tree: &mut Tree, updates: Vec<PropertyPatch>) {
    for PropertyPatch { id, key, value } in updates.into_iter().rev() {
        let Some(node) = tree.nodes.get_mut(&id) else {
            continue;
        };
        match value {
            Some(value) => {
                node.properties.insert(key, value);
            }
            None => {
                node.properties.remove(&key);
            }
        }
    }
}

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
            | PropKey::LeftPercent
            | PropKey::TopPercent
            | PropKey::RightPercent
            | PropKey::BottomPercent
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

fn affects_resolved_layout(node: &Node, key: PropKey) -> bool {
    if !affects_layout(key) {
        return false;
    }
    let fixed_text_box = node.kind == pam_native_protocol::NodeKind::Text
        && node.properties.contains_key(&PropKey::Width)
        && node.properties.contains_key(&PropKey::Height);
    if fixed_text_box
        && matches!(
            key,
            PropKey::Text
                | PropKey::FontSize
                | PropKey::FontWeight
                | PropKey::NumberOfLines
                | PropKey::LetterSpacing
                | PropKey::LineHeight
                | PropKey::TextTransform
                | PropKey::FontStyle
                | PropKey::FontFamily
                | PropKey::TextAllowFontScaling
                | PropKey::TextMaxFontSizeMultiplier
                | PropKey::TextAdjustsFontSizeToFit
                | PropKey::TextMinimumFontScale
        )
    {
        return false;
    }
    true
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
        engine.set_refresh_rate(120.0).expect("display rate");
        let input = frame("stable", true);
        engine.commit(&input).expect("initial");
        let retained = engine.stats().retained_bytes;
        for _ in 0..10_000 {
            let batch = decode_batch(&engine.commit(&input).expect("commit")).expect("batch");
            assert!(batch.is_empty());
        }
        assert_eq!(engine.stats().retained_bytes, retained);
        assert_eq!(engine.stats().nodes, 4);
        assert_eq!(engine.stats().measured_frames, 10_001);
    }

    #[test]
    fn rejects_invalid_display_refresh_rates() {
        let mut engine = Engine::new();
        assert!(engine.set_refresh_rate(0.0).is_err());
        assert!(engine.set_refresh_rate(f64::NAN).is_err());
        assert!(engine.set_refresh_rate(90.0).is_ok());
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
    fn fixed_text_box_skips_intrinsic_relayout() {
        let tree = Tree {
            root: 1,
            nodes: BTreeMap::from([
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
                        kind: NodeKind::Text,
                        properties: BTreeMap::from([
                            (PropKey::Text, PropValue::String("short".to_owned())),
                            (PropKey::Width, PropValue::Float(240.0)),
                            (PropKey::Height, PropValue::Float(48.0)),
                        ]),
                    },
                ),
            ]),
        };
        let mut engine = Engine::new();
        engine
            .commit(&tree.encode().expect("tree"))
            .expect("initial");
        let before = engine
            .performance_snapshot()
            .stages
            .get(&performance::PerformanceStage::Layout)
            .map_or(0, |stage| stage.samples);
        let patch = Patch {
            operations: vec![PatchOperation::Update(PropertyPatch {
                id: 2,
                key: PropKey::Text,
                value: Some(PropValue::String("a much longer label".to_owned())),
            })],
        };
        let mutations = decode_batch(
            &engine
                .commit(&patch.encode().expect("patch"))
                .expect("commit"),
        )
        .expect("batch");
        let after = engine
            .performance_snapshot()
            .stages
            .get(&performance::PerformanceStage::Layout)
            .map_or(0, |stage| stage.samples);

        assert_eq!(before, after);
        assert_eq!(mutations.len(), 1);
        assert!(matches!(mutations[0], Mutation::Update { id: 2, .. }));
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
    fn rejected_property_patch_rolls_back_the_retained_tree() {
        let mut engine = Engine::new();
        engine.commit(&frame("A", true)).expect("initial");
        let invalid = Patch {
            operations: vec![PatchOperation::Update(PropertyPatch {
                id: 2,
                key: PropKey::Padding,
                value: Some(PropValue::Float(-1.0)),
            })],
        }
        .encode()
        .expect("invalid patch");

        assert!(matches!(
            engine.commit(&invalid),
            Err(EngineError::Layout(layout::LayoutError::InvalidDimension)),
        ));
        assert_eq!(
            engine
                .current
                .as_ref()
                .expect("retained tree")
                .nodes
                .get(&2)
                .expect("column")
                .properties
                .get(&PropKey::Padding),
            Some(&PropValue::Float(16.0)),
        );
        assert_eq!(engine.stats().patch_commits, 0);
        assert_eq!(engine.stats().updated, 0);

        let valid = Patch {
            operations: vec![PatchOperation::Update(PropertyPatch {
                id: 2,
                key: PropKey::Padding,
                value: Some(PropValue::Float(24.0)),
            })],
        }
        .encode()
        .expect("valid patch");
        let mutations = decode_batch(&engine.commit(&valid).expect("retry")).expect("batch");
        assert!(mutations.iter().any(|mutation| matches!(
            mutation,
            Mutation::Update {
                id: 2,
                key: PropKey::Padding,
                ..
            }
        )));
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
