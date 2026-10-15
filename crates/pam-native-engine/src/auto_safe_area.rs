//! Automatic safe area: content stays out of the system bars by default.
//!
//! Every presentation surface (the root screen, each navigation route, each
//! `Modal`/`BottomSheet`) has a *content container*: the first node of its
//! single-child wrapper chain that holds the actual content. After layout the
//! engine measures how far the surface content (text, inputs, buttons,
//! pressables and the scroll end of overflowing lists) stays from each window
//! edge. Where content would end inside a system-bar, cutout or home-indicator
//! inset, the container receives the missing padding and the surface is laid
//! out again. Padding the application already authored counts, so the
//! effective padding is `max(inset, authored)`: a screen or sheet that clears
//! the bars itself is left untouched and nothing is applied twice.
//!
//! Backgrounds and media keep reaching the screen edges: wrapper chain nodes
//! above the container still span the whole surface, and images/media are not
//! content. A surface whose wrapper chain contains a `SafeAreaView` is
//! managed explicitly by the application (a `SafeAreaView` with every edge
//! disabled is the full-bleed opt-out).
//!
//! The applied padding is sticky per container (kept by the engine between
//! layouts) so incremental layouts reuse it and settle in one pass.

use std::cell::RefCell;
use std::collections::BTreeMap;

use pam_native_protocol::{Layout, Node, NodeKind, PropKey, PropValue, Tree};

thread_local! {
    static PADDING: RefCell<BTreeMap<u64, [f32; 4]>> = const { RefCell::new(BTreeMap::new()) };
    static ENABLED: std::cell::Cell<bool> = const { std::cell::Cell::new(true) };
}

/// Whether automatic safe-area handling runs in the current layout
/// (`Engine::set_automatic_safe_area`; the lint can check explicit handling
/// only).
pub(crate) fn enabled() -> bool {
    ENABLED.with(std::cell::Cell::get)
}

/// Installs the engine's sticky container padding for the layout calls made
/// on this thread until [`Scope::finish`] (or drop, which discards changes).
pub(crate) struct Scope {
    previous: Option<BTreeMap<u64, [f32; 4]>>,
    previous_enabled: bool,
}

impl Scope {
    #[cfg(test)]
    pub(crate) fn enter(padding: BTreeMap<u64, [f32; 4]>) -> Self {
        Self::enter_with(padding, true)
    }

    pub(crate) fn enter_with(padding: BTreeMap<u64, [f32; 4]>, enabled: bool) -> Self {
        let padding = if enabled { padding } else { BTreeMap::new() };
        Self {
            previous: Some(PADDING.with(|current| current.replace(padding))),
            previous_enabled: ENABLED.with(|current| current.replace(enabled)),
        }
    }

    /// Ends the scope and returns the padding the layout settled on.
    pub(crate) fn finish(mut self) -> BTreeMap<u64, [f32; 4]> {
        let previous = self.previous.take().unwrap_or_default();
        ENABLED.with(|current| current.set(self.previous_enabled));
        PADDING.with(|current| current.replace(previous))
    }
}

impl Drop for Scope {
    fn drop(&mut self) {
        if let Some(previous) = self.previous.take() {
            PADDING.with(|current| *current.borrow_mut() = previous);
            ENABLED.with(|current| current.set(self.previous_enabled));
        }
    }
}

/// Automatic padding (left, top, right, bottom) of a content container.
pub(crate) fn padding(id: u64) -> [f32; 4] {
    PADDING.with(|current| current.borrow().get(&id).copied().unwrap_or([0.0; 4]))
}

pub(crate) fn set_padding(id: u64, value: [f32; 4]) {
    PADDING.with(|current| {
        let mut map = current.borrow_mut();
        if value.iter().all(|edge| *edge <= 0.0) {
            map.remove(&id);
        } else {
            map.insert(id, value);
        }
    });
}

/// Drops the padding of containers that are no longer surface containers.
pub(crate) fn retain(containers: &[u64]) {
    PADDING.with(|current| {
        current.borrow_mut().retain(|id, _| containers.contains(id));
    });
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum SurfaceKind {
    /// The window root (a screen without a navigation host).
    Root,
    /// A `Modal`/`BottomSheet` node.
    Modal(u64),
    /// The active route (child) of a `NavigationHost`/`TabHost`.
    Route { host: u64, route: u64 },
}

#[derive(Clone, Debug)]
pub(crate) struct AutoSurface {
    pub kind: SurfaceKind,
    /// Nodes whose subtrees hold the surface content.
    pub start: Vec<u64>,
    /// Container receiving automatic padding (`None`: explicit or no chain).
    pub container: Option<u64>,
    /// Edges (left, top, right, bottom) this surface may pad.
    pub edges: [bool; 4],
}

const ALL_EDGES: [bool; 4] = [true; 4];
const SHEET_EDGES: [bool; 4] = [true, false, true, true];
const PRESENTATION_SHEET: i64 = 3;

pub(crate) fn visible(node: &Node) -> bool {
    !matches!(
        node.properties.get(&PropKey::Visible),
        Some(PropValue::Boolean(false))
    )
}

pub(crate) fn out_of_flow(node: &Node) -> bool {
    matches!(
        node.properties.get(&PropKey::PositionType),
        Some(PropValue::Integer(2 | 3))
    )
}

fn integer(node: &Node, key: PropKey) -> Option<i64> {
    match node.properties.get(&key) {
        Some(PropValue::Integer(value)) => Some(*value),
        _ => None,
    }
}

fn number(node: &Node, key: PropKey) -> Option<f32> {
    node.properties.get(&key).and_then(PropValue::as_number)
}

fn flag(node: &Node, key: PropKey) -> bool {
    matches!(node.properties.get(&key), Some(PropValue::Boolean(true)))
}

fn is_host(node: &Node) -> bool {
    matches!(node.kind, NodeKind::NavigationHost | NodeKind::TabHost)
}

fn is_scroll(node: &Node) -> bool {
    matches!(
        node.kind,
        NodeKind::Scroll | NodeKind::VirtualList | NodeKind::List | NodeKind::SectionList
    )
}

fn horizontal_scroll(node: &Node) -> bool {
    match node.kind {
        NodeKind::Scroll => flag(node, PropKey::ScrollHorizontal),
        NodeKind::VirtualList | NodeKind::List | NodeKind::SectionList => {
            flag(node, PropKey::ListHorizontal)
        }
        _ => false,
    }
}

/// Nodes that can carry the automatic padding of a surface.
fn container_kind(node: &Node) -> bool {
    matches!(
        node.kind,
        NodeKind::Screen
            | NodeKind::Column
            | NodeKind::Row
            | NodeKind::View
            | NodeKind::KeyboardAvoidingView
            | NodeKind::RefreshControl
            | NodeKind::ImageBackground
    )
}

fn flow_children<'a>(children: &'a BTreeMap<u64, Vec<&'a Node>>, id: u64) -> Vec<&'a Node> {
    children
        .get(&id)
        .map_or(&[][..], Vec::as_slice)
        .iter()
        .copied()
        .filter(|child| visible(child) && child.kind != NodeKind::Modal && !out_of_flow(child))
        .collect()
}

/// Walks the single-child wrapper chain from `start` and returns the content
/// container, or `None` when the application manages the safe area itself
/// (a `SafeAreaView` in the chain) or the chain hands over to a navigation
/// host (its routes are surfaces of their own).
fn chain_container(tree: &Tree, children: &BTreeMap<u64, Vec<&Node>>, start: u64) -> Option<u64> {
    let mut current = tree.nodes.get(&start)?;
    let mut container = None;
    for _ in 0..64 {
        if current.kind == NodeKind::SafeAreaView || is_host(current) {
            return None;
        }
        if !container_kind(current) {
            return container;
        }
        container = Some(current.id);
        let flow = flow_children(children, current.id);
        if flow.len() != 1 {
            return container;
        }
        let next = flow[0];
        // A single scroll/list keeps its parent as the container: the list
        // frame ends above the bar and its last row scrolls into view.
        if !container_kind(next) && next.kind != NodeKind::SafeAreaView && !is_host(next) {
            return container;
        }
        current = next;
    }
    container
}

/// Every presentation surface of `tree` with its content container.
pub(crate) fn surfaces(tree: &Tree, children: &BTreeMap<u64, Vec<&Node>>) -> Vec<AutoSurface> {
    let mut result = Vec::new();
    result.push(AutoSurface {
        kind: SurfaceKind::Root,
        start: vec![tree.root],
        container: chain_container(tree, children, tree.root),
        edges: ALL_EDGES,
    });
    for node in tree.nodes.values() {
        if !visible(node) || !reachable(tree, node) {
            continue;
        }
        if node.kind == NodeKind::Modal && node.id != tree.root {
            let content = children
                .get(&node.id)
                .map_or(&[][..], Vec::as_slice)
                .iter()
                .filter(|child| visible(child))
                .map(|child| child.id)
                .collect::<Vec<_>>();
            let container = match content.as_slice() {
                [only] => chain_container(tree, children, *only),
                _ => None,
            };
            let sheet = integer(node, PropKey::ModalPresentation) == Some(PRESENTATION_SHEET);
            result.push(AutoSurface {
                kind: SurfaceKind::Modal(node.id),
                start: content,
                container,
                edges: if sheet { SHEET_EDGES } else { ALL_EDGES },
            });
        } else if is_host(node) {
            let Some(route) = children
                .get(&node.id)
                .map_or(&[][..], Vec::as_slice)
                .iter()
                .rev()
                .find(|child| visible(child))
            else {
                continue;
            };
            result.push(AutoSurface {
                kind: SurfaceKind::Route {
                    host: node.id,
                    route: route.id,
                },
                start: vec![route.id],
                container: chain_container(tree, children, route.id),
                edges: ALL_EDGES,
            });
        }
    }
    result
}

/// Whether every ancestor of `node` up to the root is visible (hidden routes
/// and closed sheets are not surfaces).
fn reachable(tree: &Tree, node: &Node) -> bool {
    let mut current = node;
    for _ in 0..256 {
        if current.id == tree.root {
            return true;
        }
        let Some(parent) = tree.nodes.get(&current.parent) else {
            return false;
        };
        if !visible(parent) {
            return false;
        }
        current = parent;
    }
    false
}

/// What makes a node surface content for the safe area.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ContentKind {
    Text,
    Interactive,
    /// The last (or first) row of an overflowing list when scrolled to its end.
    ScrollEnd,
}

#[derive(Clone, Copy, Debug)]
pub(crate) struct ContentRect {
    pub id: u64,
    pub kind: ContentKind,
    pub rect: Layout,
}

/// Collects the content rectangles of a surface. `include_absolute` adds
/// out-of-flow subtrees (the lint checks them; the padding pass leaves them
/// to the absolute-inset rule).
pub(crate) fn content(
    tree: &Tree,
    children: &BTreeMap<u64, Vec<&Node>>,
    surface: &AutoSurface,
    layouts: &BTreeMap<u64, Layout>,
    viewport: Layout,
    include_absolute: bool,
    output: &mut Vec<ContentRect>,
) {
    let mut stack = surface.start.clone();
    let viewport_area = (viewport.width * viewport.height).max(1.0);
    let mut steps = 0usize;
    while let Some(id) = stack.pop() {
        steps += 1;
        if steps > 200_000 {
            break;
        }
        let Some(node) = tree.nodes.get(&id) else {
            continue;
        };
        if !visible(node)
            || (!include_absolute && out_of_flow(node) && !surface.start.contains(&id))
        {
            continue;
        }
        let Some(frame) = layouts.get(&id).copied() else {
            continue;
        };
        if node.kind == NodeKind::Modal && !surface.start.contains(&id) {
            continue;
        }
        let node_children = children.get(&id).map_or(&[][..], Vec::as_slice);
        if is_host(node) {
            // Routes are surfaces of their own.
            continue;
        }
        if is_scroll(node) {
            if horizontal_scroll(node) {
                if node_children.iter().any(|child| visible(child)) {
                    output.push(ContentRect {
                        id,
                        kind: ContentKind::Interactive,
                        rect: frame,
                    });
                }
                continue;
            }
            if let Some(end) = overflowing_scroll_end(node, frame, node_children, layouts) {
                output.push(ContentRect {
                    id,
                    kind: ContentKind::ScrollEnd,
                    rect: end,
                });
                continue;
            }
        }
        if frame.width > 0.0 && frame.height > 0.0 {
            match node.kind {
                NodeKind::Text => output.push(ContentRect {
                    id,
                    kind: ContentKind::Text,
                    rect: frame,
                }),
                NodeKind::Button | NodeKind::Input | NodeKind::Switch => {
                    output.push(ContentRect {
                        id,
                        kind: ContentKind::Interactive,
                        rect: frame,
                    });
                }
                NodeKind::Pressable if frame.width * frame.height < viewport_area * 0.5 => {
                    output.push(ContentRect {
                        id,
                        kind: ContentKind::Interactive,
                        rect: frame,
                    });
                }
                _ => {}
            }
        }
        for child in node_children {
            stack.push(child.id);
        }
    }
}

/// For a vertical scroll whose content is taller than its viewport: the
/// rectangle the content occupies when scrolled to its end (its end padding
/// excluded). `None` when everything fits (the rows are measured directly).
fn overflowing_scroll_end(
    node: &Node,
    frame: Layout,
    node_children: &[&Node],
    layouts: &BTreeMap<u64, Layout>,
) -> Option<Layout> {
    let padding_bottom = number(node, PropKey::PaddingBottom)
        .or_else(|| number(node, PropKey::PaddingVertical))
        .or_else(|| number(node, PropKey::Padding))
        .unwrap_or(0.0)
        .max(0.0);
    let mut content_bottom = f32::MIN;
    let mut end_padding = padding_bottom;
    for child in node_children.iter().filter(|child| visible(child)) {
        let Some(child_frame) = layouts.get(&child.id) else {
            continue;
        };
        content_bottom = content_bottom.max(child_frame.y + child_frame.height);
        if node.kind == NodeKind::Scroll {
            // The content container's own end padding scrolls with it.
            end_padding = padding_bottom
                + number(child, PropKey::PaddingBottom)
                    .or_else(|| number(child, PropKey::PaddingVertical))
                    .or_else(|| number(child, PropKey::Padding))
                    .unwrap_or(0.0)
                    .max(0.0);
        }
    }
    if content_bottom == f32::MIN || content_bottom <= frame.y + frame.height - padding_bottom + 0.5
    {
        return None;
    }
    let bottom = frame.y + frame.height - end_padding;
    Some(Layout {
        x: frame.x,
        y: frame.y,
        width: frame.width,
        height: (bottom - frame.y).max(0.0),
    })
}

/// Distance (left, top, right, bottom) from each viewport edge to the
/// nearest content; `f32::INFINITY` when the surface has no content.
pub(crate) fn clearance(rects: &[ContentRect], viewport: Layout) -> [f32; 4] {
    let mut result = [f32::INFINITY; 4];
    for content in rects {
        let rect = content.rect;
        result[0] = result[0].min(rect.x - viewport.x);
        result[1] = result[1].min(rect.y - viewport.y);
        result[2] = result[2].min(viewport.x + viewport.width - (rect.x + rect.width));
        result[3] = result[3].min(viewport.y + viewport.height - (rect.y + rect.height));
    }
    result
}

/// Padding a container needs so its content clears `insets`, given the
/// padding it had (`current`) when `clearance` was measured.
pub(crate) fn settle_padding(
    current: [f32; 4],
    insets: [f32; 4],
    clearance: [f32; 4],
    edges: [bool; 4],
) -> [f32; 4] {
    let mut next = [0.0; 4];
    for edge in 0..4 {
        if !edges[edge] || insets[edge] <= 0.0 || !clearance[edge].is_finite() {
            continue;
        }
        next[edge] = (current[edge] + insets[edge] - clearance[edge]).clamp(0.0, insets[edge]);
        if next[edge] < 0.5 {
            next[edge] = 0.0;
        }
    }
    next
}

/// Whether an absolutely positioned subtree holds content (text, inputs,
/// buttons, pressables): such overlays (FABs, toasts, close buttons) stay
/// inside the safe area; decorative ones (gradients, scrims) may bleed.
pub(crate) fn absolute_holds_content(children: &BTreeMap<u64, Vec<&Node>>, node: &Node) -> bool {
    let mut stack = vec![node];
    let mut steps = 0;
    while let Some(current) = stack.pop() {
        steps += 1;
        if steps > 512 {
            return true;
        }
        if !visible(current) {
            continue;
        }
        if matches!(
            current.kind,
            NodeKind::Text
                | NodeKind::Button
                | NodeKind::Input
                | NodeKind::Switch
                | NodeKind::Pressable
        ) {
            return true;
        }
        if let Some(next) = children.get(&current.id) {
            stack.extend(next.iter().copied());
        }
    }
    false
}

/// Whether `node` sits inside a scroll/list (its frames are content
/// coordinates, not window positions) or under a full-bleed opt-out.
pub(crate) fn exempt_from_absolute_insets(tree: &Tree, node: &Node) -> bool {
    let mut current = tree.nodes.get(&node.parent);
    for _ in 0..256 {
        let Some(candidate) = current else {
            return false;
        };
        if is_scroll(candidate) {
            return true;
        }
        if candidate.kind == NodeKind::SafeAreaView && full_bleed(candidate) {
            return true;
        }
        if candidate.kind == NodeKind::Modal || candidate.id == tree.root {
            return false;
        }
        current = tree.nodes.get(&candidate.parent);
    }
    false
}

/// A `SafeAreaView` with every edge disabled: the documented full-bleed
/// opt-out.
pub(crate) fn full_bleed(node: &Node) -> bool {
    [
        PropKey::SafeAreaLeft,
        PropKey::SafeAreaTop,
        PropKey::SafeAreaRight,
        PropKey::SafeAreaBottomEdge,
    ]
    .iter()
    .all(|key| matches!(node.properties.get(key), Some(PropValue::Boolean(false))))
}

/// A node that intrudes into a window inset.
#[derive(Clone, Debug, PartialEq)]
pub struct SafeAreaViolation {
    pub id: u64,
    pub kind: ContentKind,
    pub node_kind: NodeKind,
    /// Edge index: 0 left, 1 top, 2 right, 3 bottom.
    pub edge: usize,
    /// Points of the content inside the inset.
    pub intrusion: f32,
    pub test_id: Option<String>,
    pub label: Option<String>,
}

impl SafeAreaViolation {
    #[must_use]
    pub fn edge_name(&self) -> &'static str {
        ["left", "top", "right", "bottom"][self.edge.min(3)]
    }
}

pub(crate) fn violations_of(
    tree: &Tree,
    rects: &[ContentRect],
    viewport: Layout,
    insets: [f32; 4],
    edges: [bool; 4],
    output: &mut Vec<SafeAreaViolation>,
) {
    for content in rects {
        let rect = content.rect;
        let distance = [
            rect.x - viewport.x,
            rect.y - viewport.y,
            viewport.x + viewport.width - (rect.x + rect.width),
            viewport.y + viewport.height - (rect.y + rect.height),
        ];
        for edge in 0..4 {
            if !edges[edge] || insets[edge] <= 0.0 {
                continue;
            }
            let intrusion = insets[edge] - distance[edge];
            if intrusion > 0.5 {
                let node = &tree.nodes[&content.id];
                let text = |key| match node.properties.get(&key) {
                    Some(PropValue::String(value)) if !value.is_empty() => Some(value.clone()),
                    _ => None,
                };
                output.push(SafeAreaViolation {
                    id: content.id,
                    kind: content.kind,
                    node_kind: node.kind,
                    edge,
                    intrusion,
                    test_id: text(PropKey::TestId),
                    label: text(PropKey::AccessibilityLabel)
                        .or_else(|| text(PropKey::Text))
                        .map(|value| value.chars().take(48).collect()),
                });
            }
        }
    }
}
