use std::collections::BTreeMap;

use pam_native_protocol::{Layout, Node, NodeKind, PropKey, Tree};

const DEFAULT_TEXT_HEIGHT: f32 = 28.0;
const DEFAULT_CONTROL_HEIGHT: f32 = 48.0;
const DEFAULT_IMAGE_HEIGHT: f32 = 160.0;
const DEFAULT_LIST_HEIGHT: f32 = 240.0;
const MAX_LAYOUT_DEPTH: usize = 512;

#[derive(Clone, Copy, Debug)]
pub struct Size {
    pub width: f32,
    pub height: f32,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum Axis {
    Vertical,
    Horizontal,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum CrossAlignment {
    Start,
    Center,
    End,
    Stretch,
}

pub fn calculate(tree: &Tree, viewport: Size) -> Result<BTreeMap<u64, Layout>, LayoutError> {
    if !viewport.width.is_finite()
        || !viewport.height.is_finite()
        || viewport.width <= 0.0
        || viewport.height <= 0.0
    {
        return Err(LayoutError::InvalidDimension);
    }
    let children = child_index(tree);
    let mut result = BTreeMap::new();
    layout_node(
        tree,
        &children,
        tree.root,
        Layout {
            x: 0.0,
            y: 0.0,
            width: viewport.width,
            height: viewport.height,
        },
        0,
        &mut result,
    )?;
    Ok(result)
}

fn layout_node(
    tree: &Tree,
    children: &BTreeMap<u64, Vec<&Node>>,
    id: u64,
    bounds: Layout,
    depth: usize,
    output: &mut BTreeMap<u64, Layout>,
) -> Result<(), LayoutError> {
    if depth > MAX_LAYOUT_DEPTH {
        return Err(LayoutError::DepthExceeded);
    }
    let node = tree.nodes.get(&id).ok_or(LayoutError::MissingNode(id))?;
    let frame = Layout {
        width: constrained(
            number(node, PropKey::Width).unwrap_or(bounds.width),
            number(node, PropKey::MinWidth),
            number(node, PropKey::MaxWidth),
        )?,
        height: constrained(
            number(node, PropKey::Height).unwrap_or(bounds.height),
            number(node, PropKey::MinHeight),
            number(node, PropKey::MaxHeight),
        )?,
        ..bounds
    };
    output.insert(id, frame);

    let node_children = children.get(&id).map_or(&[][..], Vec::as_slice);
    if node_children.is_empty() {
        return Ok(());
    }

    let padding = finite_non_negative(number(node, PropKey::Padding).unwrap_or(0.0))?;
    let padding_horizontal =
        finite_non_negative(number(node, PropKey::PaddingHorizontal).unwrap_or(padding))?;
    let padding_vertical =
        finite_non_negative(number(node, PropKey::PaddingVertical).unwrap_or(padding))?;
    let gap = finite_non_negative(number(node, PropKey::Gap).unwrap_or(0.0))?;
    let inner = Layout {
        x: frame.x + padding_horizontal,
        y: frame.y + padding_vertical,
        width: (frame.width - padding_horizontal * 2.0).max(0.0),
        height: (frame.height - padding_vertical * 2.0).max(0.0),
    };
    if matches!(
        node.kind,
        NodeKind::Modal | NodeKind::RefreshControl | NodeKind::Scroll
    ) {
        for child in node_children {
            layout_node(tree, children, child.id, inner, depth + 1, output)?;
        }
        return Ok(());
    }
    if node.kind == NodeKind::DrawerLayout {
        for (position, child) in node_children.iter().enumerate() {
            let child_frame = if position == 0 {
                inner
            } else {
                Layout {
                    width: number(child, PropKey::Width).unwrap_or(inner.width * 0.82),
                    ..inner
                }
            };
            layout_node(tree, children, child.id, child_frame, depth + 1, output)?;
        }
        return Ok(());
    }
    let axis = if node.kind == NodeKind::Row {
        Axis::Horizontal
    } else {
        Axis::Vertical
    };
    let available_main = match axis {
        Axis::Vertical => inner.height,
        Axis::Horizontal => inner.width,
    };
    let total_gap = gap * node_children.len().saturating_sub(1) as f32;
    let total_flex = node_children
        .iter()
        .map(|child| number(child, PropKey::FlexGrow).unwrap_or(0.0).max(0.0))
        .sum::<f32>();
    let fixed = node_children
        .iter()
        .filter(|child| number(child, PropKey::FlexGrow).unwrap_or(0.0) <= 0.0)
        .map(|child| intrinsic_main(child, axis) + margin_main(child, axis) * 2.0)
        .sum::<f32>();
    let flex_margins = node_children
        .iter()
        .filter(|child| number(child, PropKey::FlexGrow).unwrap_or(0.0) > 0.0)
        .map(|child| margin_main(child, axis) * 2.0)
        .sum::<f32>();
    let remaining = (available_main - fixed - flex_margins - total_gap).max(0.0);
    let consumed =
        fixed + flex_margins + total_gap + if total_flex > 0.0 { remaining } else { 0.0 };
    let free = (available_main - consumed).max(0.0);
    let justify = integer(node, PropKey::JustifyContent).unwrap_or(1);
    let (mut cursor, distributed_gap) = justify_offsets(justify, free, node_children.len(), gap);
    let parent_alignment = cross_alignment(integer(node, PropKey::AlignItems).unwrap_or(4));

    for child in node_children {
        let flex = number(child, PropKey::FlexGrow).unwrap_or(0.0).max(0.0);
        let main = if flex > 0.0 && total_flex > 0.0 {
            remaining * flex / total_flex
        } else {
            intrinsic_main(child, axis)
        };
        let main_margin = margin_main(child, axis);
        let cross_margin = margin_cross(child, axis);
        let alignment = integer(child, PropKey::AlignSelf)
            .map(cross_alignment)
            .unwrap_or(parent_alignment);
        let available_cross = match axis {
            Axis::Vertical => inner.width,
            Axis::Horizontal => inner.height,
        };
        let explicit_cross = match axis {
            Axis::Vertical => number(child, PropKey::Width),
            Axis::Horizontal => number(child, PropKey::Height),
        };
        let cross = explicit_cross.unwrap_or_else(|| {
            if alignment == CrossAlignment::Stretch {
                (available_cross - cross_margin * 2.0).max(0.0)
            } else {
                intrinsic_cross(child, axis).min((available_cross - cross_margin * 2.0).max(0.0))
            }
        });
        let cross_offset = match alignment {
            CrossAlignment::Start | CrossAlignment::Stretch => cross_margin,
            CrossAlignment::Center => (available_cross - cross) / 2.0,
            CrossAlignment::End => available_cross - cross - cross_margin,
        }
        .max(0.0);
        cursor += main_margin;
        let child_frame = match axis {
            Axis::Vertical => Layout {
                x: inner.x + cross_offset,
                y: inner.y + cursor,
                width: cross,
                height: main,
            },
            Axis::Horizontal => Layout {
                x: inner.x + cursor,
                y: inner.y + cross_offset,
                width: main,
                height: cross,
            },
        };
        layout_node(tree, children, child.id, child_frame, depth + 1, output)?;
        cursor += main + main_margin + distributed_gap;
    }
    Ok(())
}

fn child_index(tree: &Tree) -> BTreeMap<u64, Vec<&Node>> {
    let mut children = BTreeMap::<u64, Vec<&Node>>::new();
    for node in tree.nodes.values() {
        children.entry(node.parent).or_default().push(node);
    }
    for siblings in children.values_mut() {
        siblings.sort_by_key(|node| node.index);
    }
    children
}

fn intrinsic_main(node: &Node, axis: Axis) -> f32 {
    let explicit = match axis {
        Axis::Vertical => number(node, PropKey::Height),
        Axis::Horizontal => number(node, PropKey::Width),
    };
    explicit.unwrap_or(match (axis, node.kind) {
        (Axis::Horizontal, _) => 100.0,
        (_, NodeKind::Text) => DEFAULT_TEXT_HEIGHT,
        (
            _,
            NodeKind::Button
            | NodeKind::Input
            | NodeKind::Pressable
            | NodeKind::Switch
            | NodeKind::ActivityIndicator,
        ) => DEFAULT_CONTROL_HEIGHT,
        (_, NodeKind::Image | NodeKind::ImageBackground) => DEFAULT_IMAGE_HEIGHT,
        (
            _,
            NodeKind::List | NodeKind::SectionList | NodeKind::Scroll | NodeKind::RefreshControl,
        ) => DEFAULT_LIST_HEIGHT,
        (_, NodeKind::Spacer) => 8.0,
        (_, NodeKind::StatusBar) => 0.0,
        _ => DEFAULT_CONTROL_HEIGHT,
    })
}

fn intrinsic_cross(node: &Node, axis: Axis) -> f32 {
    match axis {
        Axis::Vertical => number(node, PropKey::Width).unwrap_or(100.0),
        Axis::Horizontal => {
            number(node, PropKey::Height).unwrap_or(intrinsic_main(node, Axis::Vertical))
        }
    }
}

fn margin_main(node: &Node, axis: Axis) -> f32 {
    let all = number(node, PropKey::Margin).unwrap_or(0.0);
    match axis {
        Axis::Vertical => number(node, PropKey::MarginVertical).unwrap_or(all),
        Axis::Horizontal => number(node, PropKey::MarginHorizontal).unwrap_or(all),
    }
    .max(0.0)
}

fn margin_cross(node: &Node, axis: Axis) -> f32 {
    let all = number(node, PropKey::Margin).unwrap_or(0.0);
    match axis {
        Axis::Vertical => number(node, PropKey::MarginHorizontal).unwrap_or(all),
        Axis::Horizontal => number(node, PropKey::MarginVertical).unwrap_or(all),
    }
    .max(0.0)
}

fn cross_alignment(value: i64) -> CrossAlignment {
    match value {
        1 => CrossAlignment::Start,
        2 => CrossAlignment::Center,
        3 => CrossAlignment::End,
        _ => CrossAlignment::Stretch,
    }
}

fn justify_offsets(value: i64, free: f32, count: usize, gap: f32) -> (f32, f32) {
    match value {
        2 => (free / 2.0, gap),
        3 => (free, gap),
        4 if count > 1 => (0.0, gap + free / (count - 1) as f32),
        5 if count > 0 => {
            let space = free / count as f32;
            (space / 2.0, gap + space)
        }
        6 if count > 0 => {
            let space = free / (count + 1) as f32;
            (space, gap + space)
        }
        _ => (0.0, gap),
    }
}

fn number(node: &Node, key: PropKey) -> Option<f32> {
    node.properties
        .get(&key)
        .and_then(|value| value.as_number())
}

fn integer(node: &Node, key: PropKey) -> Option<i64> {
    match node.properties.get(&key) {
        Some(pam_native_protocol::PropValue::Integer(value)) => Some(*value),
        _ => None,
    }
}

fn constrained(value: f32, minimum: Option<f32>, maximum: Option<f32>) -> Result<f32, LayoutError> {
    let mut result = finite_non_negative(value)?;

    if let Some(minimum) = minimum {
        result = result.max(finite_non_negative(minimum)?);
    }

    if let Some(maximum) = maximum {
        result = result.min(finite_non_negative(maximum)?);
    }

    Ok(result)
}

fn finite_non_negative(value: f32) -> Result<f32, LayoutError> {
    if value.is_finite() && value >= 0.0 {
        Ok(value)
    } else {
        Err(LayoutError::InvalidDimension)
    }
}

#[derive(Debug)]
pub enum LayoutError {
    InvalidDimension,
    DepthExceeded,
    MissingNode(u64),
}

impl std::fmt::Display for LayoutError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::InvalidDimension => {
                formatter.write_str("layout dimensions must be finite and non-negative")
            }
            Self::DepthExceeded => formatter.write_str("layout tree exceeds maximum depth"),
            Self::MissingNode(id) => write!(formatter, "layout node {id} does not exist"),
        }
    }
}

impl std::error::Error for LayoutError {}
