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
        true,
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
    resolve_dimensions: bool,
    depth: usize,
    output: &mut BTreeMap<u64, Layout>,
) -> Result<(), LayoutError> {
    if depth > MAX_LAYOUT_DEPTH {
        return Err(LayoutError::DepthExceeded);
    }
    let node = tree.nodes.get(&id).ok_or(LayoutError::MissingNode(id))?;
    let frame = if resolve_dimensions {
        let explicit_width = dimension(node, PropKey::Width, PropKey::WidthPercent, bounds.width);
        let explicit_height =
            dimension(node, PropKey::Height, PropKey::HeightPercent, bounds.height);
        let aspect_ratio = number(node, PropKey::AspectRatio).filter(|value| *value > 0.0);
        let width = match (explicit_width, explicit_height, aspect_ratio) {
            (None, Some(height), Some(ratio)) => height * ratio,
            (Some(width), _, _) => width,
            _ => bounds.width,
        };
        let height = match (explicit_width, explicit_height, aspect_ratio) {
            (Some(width), None, Some(ratio)) => width / ratio,
            (_, Some(height), _) => height,
            _ => bounds.height,
        };
        let max_width = dimension(
            node,
            PropKey::MaxWidth,
            PropKey::MaxWidthPercent,
            bounds.width,
        );
        let max_height = dimension(
            node,
            PropKey::MaxHeight,
            PropKey::MaxHeightPercent,
            bounds.height,
        );
        Layout {
            width: constrained(width, number(node, PropKey::MinWidth), max_width)?,
            height: constrained(height, number(node, PropKey::MinHeight), max_height)?,
            ..bounds
        }
    } else {
        bounds
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
    let padding_left =
        finite_non_negative(number(node, PropKey::PaddingLeft).unwrap_or(padding_horizontal))?;
    let padding_top =
        finite_non_negative(number(node, PropKey::PaddingTop).unwrap_or(padding_vertical))?;
    let padding_right =
        finite_non_negative(number(node, PropKey::PaddingRight).unwrap_or(padding_horizontal))?;
    let padding_bottom =
        finite_non_negative(number(node, PropKey::PaddingBottom).unwrap_or(padding_vertical))?;
    let gap = finite(number(node, PropKey::Gap).unwrap_or(0.0))?;
    let inner = Layout {
        x: frame.x + padding_left,
        y: frame.y + padding_top,
        width: (frame.width - padding_left - padding_right).max(0.0),
        height: (frame.height - padding_top - padding_bottom).max(0.0),
    };
    if node.kind == NodeKind::Scroll {
        let axis = if boolean(node, PropKey::ScrollHorizontal) {
            Axis::Horizontal
        } else {
            Axis::Vertical
        };
        let fill_viewport = !matches!(
            node.properties.get(&PropKey::ScrollFillViewport),
            Some(pam_native_protocol::PropValue::Boolean(false))
        );
        for child in node_children {
            if !visible(child) {
                continue;
            }
            let available_main = match axis {
                Axis::Vertical => inner.height,
                Axis::Horizontal => inner.width,
            };
            let natural = natural_scroll_extent(children, child, axis, available_main, depth + 1)?;
            let main = if fill_viewport {
                natural.max(available_main)
            } else {
                natural
            };
            let content_frame = match axis {
                Axis::Vertical => Layout {
                    width: inner.width,
                    height: main,
                    ..inner
                },
                Axis::Horizontal => Layout {
                    width: main,
                    height: inner.height,
                    ..inner
                },
            };
            layout_node(
                tree,
                children,
                child.id,
                content_frame,
                false,
                depth + 1,
                output,
            )?;
        }
        return Ok(());
    }
    if matches!(node.kind, NodeKind::Modal | NodeKind::RefreshControl) {
        for child in node_children {
            if !visible(child) {
                continue;
            }
            layout_node(tree, children, child.id, inner, true, depth + 1, output)?;
        }
        return Ok(());
    }
    if node.kind == NodeKind::DrawerLayout {
        for (position, child) in node_children
            .iter()
            .filter(|child| visible(child))
            .enumerate()
        {
            let child_frame = if position == 0 {
                inner
            } else {
                Layout {
                    width: number(child, PropKey::Width).unwrap_or(inner.width * 0.82),
                    ..inner
                }
            };
            layout_node(
                tree,
                children,
                child.id,
                child_frame,
                false,
                depth + 1,
                output,
            )?;
        }
        return Ok(());
    }
    let default_direction = if node.kind == NodeKind::Row { 2 } else { 1 };
    let direction = integer(node, PropKey::FlexDirection).unwrap_or(default_direction);
    let axis = if matches!(direction, 2 | 4) {
        Axis::Horizontal
    } else {
        Axis::Vertical
    };
    let flow_children = node_children
        .iter()
        .copied()
        .filter(|child| visible(child) && integer(child, PropKey::PositionType).unwrap_or(1) != 2)
        .collect::<Vec<_>>();
    let absolute_children = node_children
        .iter()
        .copied()
        .filter(|child| visible(child) && integer(child, PropKey::PositionType).unwrap_or(1) == 2)
        .collect::<Vec<_>>();
    let ordered_children = if matches!(direction, 3 | 4) {
        flow_children.iter().rev().copied().collect::<Vec<_>>()
    } else {
        flow_children.clone()
    };
    let available_main = match axis {
        Axis::Vertical => inner.height,
        Axis::Horizontal => inner.width,
    };
    let available_cross = match axis {
        Axis::Vertical => inner.width,
        Axis::Horizontal => inner.height,
    };
    let total_gap = gap * flow_children.len().saturating_sub(1) as f32;
    let total_flex = flow_children
        .iter()
        .map(|child| number(child, PropKey::FlexGrow).unwrap_or(0.0).max(0.0))
        .sum::<f32>();
    let mut base_main = BTreeMap::new();
    for child in &flow_children {
        base_main.insert(
            child.id,
            child_main(child, axis, available_main, available_cross)?,
        );
    }
    let fixed = flow_children
        .iter()
        .filter(|child| number(child, PropKey::FlexGrow).unwrap_or(0.0) <= 0.0)
        .map(|child| {
            let (before, after) = margin_main(child, axis);
            base_main[&child.id] + before + after
        })
        .sum::<f32>();
    let flex_margins = flow_children
        .iter()
        .filter(|child| number(child, PropKey::FlexGrow).unwrap_or(0.0) > 0.0)
        .map(|child| {
            let (before, after) = margin_main(child, axis);
            before + after
        })
        .sum::<f32>();
    let overflow = (fixed + flex_margins + total_gap - available_main).max(0.0);
    let shrink_weight = flow_children
        .iter()
        .filter(|child| number(child, PropKey::FlexGrow).unwrap_or(0.0) <= 0.0)
        .map(|child| {
            number(child, PropKey::FlexShrink).unwrap_or(0.0).max(0.0) * base_main[&child.id]
        })
        .sum::<f32>();
    let resolved_main = flow_children
        .iter()
        .map(|child| {
            let base = base_main[&child.id];
            let weight = number(child, PropKey::FlexShrink).unwrap_or(0.0).max(0.0) * base;
            let main = if overflow > 0.0 && shrink_weight > 0.0 {
                (base - overflow * weight / shrink_weight).max(0.0)
            } else {
                base
            };
            (child.id, main)
        })
        .collect::<BTreeMap<_, _>>();
    let resolved_fixed = flow_children
        .iter()
        .filter(|child| number(child, PropKey::FlexGrow).unwrap_or(0.0) <= 0.0)
        .map(|child| {
            let (before, after) = margin_main(child, axis);
            resolved_main[&child.id] + before + after
        })
        .sum::<f32>();
    let remaining = (available_main - resolved_fixed - flex_margins - total_gap).max(0.0);
    let consumed =
        resolved_fixed + flex_margins + total_gap + if total_flex > 0.0 { remaining } else { 0.0 };
    let free = (available_main - consumed).max(0.0);
    let auto_margin_count = if axis == Axis::Horizontal {
        flow_children
            .iter()
            .filter(|child| boolean(child, PropKey::MarginLeftAuto))
            .count()
    } else {
        0
    };
    let auto_margin = if auto_margin_count > 0 {
        free / auto_margin_count as f32
    } else {
        0.0
    };
    let justify = integer(node, PropKey::JustifyContent).unwrap_or(1);
    let (mut cursor, distributed_gap) = justify_offsets(
        justify,
        if auto_margin_count > 0 { 0.0 } else { free },
        flow_children.len(),
        gap,
    );
    let parent_alignment = cross_alignment(integer(node, PropKey::AlignItems).unwrap_or(4));

    for child in ordered_children {
        let flex = number(child, PropKey::FlexGrow).unwrap_or(0.0).max(0.0);
        let main = if flex > 0.0 && total_flex > 0.0 {
            remaining * flex / total_flex
        } else {
            resolved_main[&child.id]
        };
        let (mut main_before, main_after) = margin_main(child, axis);
        if axis == Axis::Horizontal && boolean(child, PropKey::MarginLeftAuto) {
            main_before += auto_margin;
        }
        let (cross_before, cross_after) = margin_cross(child, axis);
        let alignment = integer(child, PropKey::AlignSelf)
            .map(cross_alignment)
            .unwrap_or(parent_alignment);
        let explicit_cross = child_cross(child, axis, available_cross, main)?;
        let cross = explicit_cross.unwrap_or_else(|| {
            if alignment == CrossAlignment::Stretch {
                (available_cross - cross_before - cross_after).max(0.0)
            } else {
                intrinsic_cross(child, axis)
                    .min((available_cross - cross_before - cross_after).max(0.0))
            }
        });
        let cross_offset = match alignment {
            CrossAlignment::Start | CrossAlignment::Stretch => cross_before,
            CrossAlignment::Center => (available_cross - cross + cross_before - cross_after) / 2.0,
            CrossAlignment::End => available_cross - cross - cross_after,
        }
        .max(0.0);
        cursor += main_before;
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
        layout_node(
            tree,
            children,
            child.id,
            child_frame,
            false,
            depth + 1,
            output,
        )?;
        cursor += main + main_after + distributed_gap;
    }

    for child in absolute_children {
        let left = number(child, PropKey::Left);
        let top = number(child, PropKey::Top);
        let right = number(child, PropKey::Right);
        let bottom = number(child, PropKey::Bottom);
        let explicit_width = dimension(child, PropKey::Width, PropKey::WidthPercent, inner.width);
        let explicit_height =
            dimension(child, PropKey::Height, PropKey::HeightPercent, inner.height);
        let mut width = explicit_width.unwrap_or_else(|| {
            if let (Some(left), Some(right)) = (left, right) {
                (inner.width - left - right).max(0.0)
            } else {
                intrinsic_cross(child, Axis::Vertical)
            }
        });
        let mut height = explicit_height.unwrap_or_else(|| {
            if let (Some(top), Some(bottom)) = (top, bottom) {
                (inner.height - top - bottom).max(0.0)
            } else {
                intrinsic_main(child, Axis::Vertical)
            }
        });
        if let Some(ratio) = number(child, PropKey::AspectRatio).filter(|value| *value > 0.0) {
            if explicit_width.is_some() && explicit_height.is_none() {
                height = width / ratio;
            } else if explicit_height.is_some() && explicit_width.is_none() {
                width = height * ratio;
            }
        }
        width = constrained(
            width,
            number(child, PropKey::MinWidth),
            dimension(
                child,
                PropKey::MaxWidth,
                PropKey::MaxWidthPercent,
                inner.width,
            ),
        )?;
        height = constrained(
            height,
            number(child, PropKey::MinHeight),
            dimension(
                child,
                PropKey::MaxHeight,
                PropKey::MaxHeightPercent,
                inner.height,
            ),
        )?;
        let child_frame = Layout {
            x: inner.x
                + left.unwrap_or_else(|| {
                    right.map_or(0.0, |right| (inner.width - right - width).max(0.0))
                }),
            y: inner.y
                + top.unwrap_or_else(|| {
                    bottom.map_or(0.0, |bottom| (inner.height - bottom - height).max(0.0))
                }),
            width,
            height,
        };
        layout_node(
            tree,
            children,
            child.id,
            child_frame,
            false,
            depth + 1,
            output,
        )?;
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

fn natural_scroll_extent(
    children: &BTreeMap<u64, Vec<&Node>>,
    node: &Node,
    axis: Axis,
    available_main: f32,
    depth: usize,
) -> Result<f32, LayoutError> {
    if depth > MAX_LAYOUT_DEPTH {
        return Err(LayoutError::DepthExceeded);
    }
    let explicit = match axis {
        Axis::Vertical => dimension(
            node,
            PropKey::Height,
            PropKey::HeightPercent,
            available_main,
        ),
        Axis::Horizontal => dimension(node, PropKey::Width, PropKey::WidthPercent, available_main),
    };
    if let Some(explicit) = explicit {
        return finite_non_negative(explicit);
    }
    let visible_children = children
        .get(&node.id)
        .map_or(&[][..], Vec::as_slice)
        .iter()
        .copied()
        .filter(|child| visible(child) && integer(child, PropKey::PositionType).unwrap_or(1) != 2)
        .collect::<Vec<_>>();
    if visible_children.is_empty() {
        return finite_non_negative(intrinsic_main(node, axis));
    }
    let direction = integer(node, PropKey::FlexDirection)
        .unwrap_or_else(|| if node.kind == NodeKind::Row { 2 } else { 1 });
    let node_axis = if matches!(direction, 2 | 4) {
        Axis::Horizontal
    } else {
        Axis::Vertical
    };
    let all_padding = finite_non_negative(number(node, PropKey::Padding).unwrap_or(0.0))?;
    let horizontal_padding =
        finite_non_negative(number(node, PropKey::PaddingHorizontal).unwrap_or(all_padding))?;
    let vertical_padding =
        finite_non_negative(number(node, PropKey::PaddingVertical).unwrap_or(all_padding))?;
    let padding_before = match axis {
        Axis::Vertical => {
            finite_non_negative(number(node, PropKey::PaddingTop).unwrap_or(vertical_padding))?
        }
        Axis::Horizontal => {
            finite_non_negative(number(node, PropKey::PaddingLeft).unwrap_or(horizontal_padding))?
        }
    };
    let padding_after = match axis {
        Axis::Vertical => {
            finite_non_negative(number(node, PropKey::PaddingBottom).unwrap_or(vertical_padding))?
        }
        Axis::Horizontal => {
            finite_non_negative(number(node, PropKey::PaddingRight).unwrap_or(horizontal_padding))?
        }
    };
    let mut extents = Vec::with_capacity(visible_children.len());
    for child in visible_children {
        let extent = natural_scroll_extent(children, child, axis, available_main, depth + 1)?;
        let (before, after) = margin_main(child, axis);
        extents.push(extent + before + after);
    }
    let content = if node_axis == axis {
        let gap = finite(number(node, PropKey::Gap).unwrap_or(0.0))?;
        extents.iter().sum::<f32>() + gap * extents.len().saturating_sub(1) as f32
    } else {
        extents.into_iter().fold(0.0, f32::max)
    };
    finite_non_negative(padding_before + content + padding_after)
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

fn child_main(
    node: &Node,
    axis: Axis,
    available_main: f32,
    available_cross: f32,
) -> Result<f32, LayoutError> {
    let explicit_main = match axis {
        Axis::Vertical => dimension(
            node,
            PropKey::Height,
            PropKey::HeightPercent,
            available_main,
        ),
        Axis::Horizontal => dimension(node, PropKey::Width, PropKey::WidthPercent, available_main),
    };
    let explicit_cross = match axis {
        Axis::Vertical => dimension(node, PropKey::Width, PropKey::WidthPercent, available_cross),
        Axis::Horizontal => dimension(
            node,
            PropKey::Height,
            PropKey::HeightPercent,
            available_cross,
        ),
    };
    let ratio = number(node, PropKey::AspectRatio).filter(|value| *value > 0.0);
    let main = explicit_main.unwrap_or_else(|| match (axis, explicit_cross, ratio) {
        (Axis::Vertical, Some(width), Some(ratio)) => width / ratio,
        (Axis::Horizontal, Some(height), Some(ratio)) => height * ratio,
        _ => intrinsic_main(node, axis),
    });
    let (minimum, maximum) = match axis {
        Axis::Vertical => (
            number(node, PropKey::MinHeight),
            dimension(
                node,
                PropKey::MaxHeight,
                PropKey::MaxHeightPercent,
                available_main,
            ),
        ),
        Axis::Horizontal => (
            number(node, PropKey::MinWidth),
            dimension(
                node,
                PropKey::MaxWidth,
                PropKey::MaxWidthPercent,
                available_main,
            ),
        ),
    };

    constrained(main, minimum, maximum)
}

fn child_cross(
    node: &Node,
    axis: Axis,
    available_cross: f32,
    resolved_main: f32,
) -> Result<Option<f32>, LayoutError> {
    let explicit = match axis {
        Axis::Vertical => dimension(node, PropKey::Width, PropKey::WidthPercent, available_cross),
        Axis::Horizontal => dimension(
            node,
            PropKey::Height,
            PropKey::HeightPercent,
            available_cross,
        ),
    };
    let ratio = number(node, PropKey::AspectRatio).filter(|value| *value > 0.0);
    let resolved = explicit.or_else(|| {
        ratio.map(|ratio| match axis {
            Axis::Vertical => resolved_main * ratio,
            Axis::Horizontal => resolved_main / ratio,
        })
    });
    let Some(resolved) = resolved else {
        return Ok(None);
    };
    let (minimum, maximum) = match axis {
        Axis::Vertical => (
            number(node, PropKey::MinWidth),
            dimension(
                node,
                PropKey::MaxWidth,
                PropKey::MaxWidthPercent,
                available_cross,
            ),
        ),
        Axis::Horizontal => (
            number(node, PropKey::MinHeight),
            dimension(
                node,
                PropKey::MaxHeight,
                PropKey::MaxHeightPercent,
                available_cross,
            ),
        ),
    };

    Ok(Some(constrained(resolved, minimum, maximum)?))
}

fn margin_main(node: &Node, axis: Axis) -> (f32, f32) {
    let all = number(node, PropKey::Margin).unwrap_or(0.0);
    let horizontal = number(node, PropKey::MarginHorizontal).unwrap_or(all);
    let vertical = number(node, PropKey::MarginVertical).unwrap_or(all);

    match axis {
        Axis::Vertical => (
            number(node, PropKey::MarginTop).unwrap_or(vertical),
            number(node, PropKey::MarginBottom).unwrap_or(vertical),
        ),
        Axis::Horizontal => (
            number(node, PropKey::MarginLeft).unwrap_or(horizontal),
            number(node, PropKey::MarginRight).unwrap_or(horizontal),
        ),
    }
}

fn margin_cross(node: &Node, axis: Axis) -> (f32, f32) {
    let all = number(node, PropKey::Margin).unwrap_or(0.0);
    let horizontal = number(node, PropKey::MarginHorizontal).unwrap_or(all);
    let vertical = number(node, PropKey::MarginVertical).unwrap_or(all);

    match axis {
        Axis::Vertical => (
            number(node, PropKey::MarginLeft).unwrap_or(horizontal),
            number(node, PropKey::MarginRight).unwrap_or(horizontal),
        ),
        Axis::Horizontal => (
            number(node, PropKey::MarginTop).unwrap_or(vertical),
            number(node, PropKey::MarginBottom).unwrap_or(vertical),
        ),
    }
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

fn boolean(node: &Node, key: PropKey) -> bool {
    matches!(
        node.properties.get(&key),
        Some(pam_native_protocol::PropValue::Boolean(true))
    )
}

fn visible(node: &Node) -> bool {
    !matches!(
        node.properties.get(&PropKey::Visible),
        Some(pam_native_protocol::PropValue::Boolean(false))
    )
}

fn dimension(node: &Node, points: PropKey, percent: PropKey, available: f32) -> Option<f32> {
    number(node, points).or_else(|| {
        number(node, percent)
            .filter(|value| value.is_finite())
            .map(|value| available * value.clamp(0.0, 100.0) / 100.0)
    })
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

fn finite(value: f32) -> Result<f32, LayoutError> {
    if value.is_finite() {
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

#[cfg(test)]
mod tests {
    use pam_native_protocol::PropValue;

    use super::*;

    fn node(
        id: u64,
        parent: u64,
        index: u32,
        kind: NodeKind,
        properties: impl IntoIterator<Item = (PropKey, PropValue)>,
    ) -> Node {
        Node {
            id,
            parent,
            index,
            kind,
            properties: properties.into_iter().collect(),
        }
    }

    #[test]
    fn resolves_absolute_percentage_aspect_and_individual_padding_once() {
        let tree = Tree {
            root: 1,
            nodes: BTreeMap::from([
                (
                    1,
                    node(
                        1,
                        0,
                        0,
                        NodeKind::Screen,
                        [
                            (PropKey::PaddingLeft, PropValue::Float(10.0)),
                            (PropKey::PaddingTop, PropValue::Float(20.0)),
                            (PropKey::PaddingRight, PropValue::Float(30.0)),
                            (PropKey::PaddingBottom, PropValue::Float(40.0)),
                        ],
                    ),
                ),
                (
                    2,
                    node(
                        2,
                        1,
                        0,
                        NodeKind::View,
                        [
                            (PropKey::PositionType, PropValue::Integer(2)),
                            (PropKey::WidthPercent, PropValue::Float(60.0)),
                            (PropKey::AspectRatio, PropValue::Float(1.0)),
                            (PropKey::Right, PropValue::Float(0.0)),
                            (PropKey::Bottom, PropValue::Float(4.0)),
                        ],
                    ),
                ),
            ]),
        };

        let layouts = calculate(
            &tree,
            Size {
                width: 300.0,
                height: 400.0,
            },
        )
        .expect("layout");
        let child = layouts[&2];

        assert_eq!(child.width, 156.0);
        assert_eq!(child.height, 156.0);
        assert_eq!(child.x, 114.0);
        assert_eq!(child.y, 200.0);
    }

    #[test]
    fn flex_shrink_is_calculated_in_rust_without_runtime_callbacks() {
        let tree = Tree {
            root: 1,
            nodes: BTreeMap::from([
                (
                    1,
                    node(
                        1,
                        0,
                        0,
                        NodeKind::Row,
                        [(PropKey::FlexDirection, PropValue::Integer(2))],
                    ),
                ),
                (
                    2,
                    node(
                        2,
                        1,
                        0,
                        NodeKind::View,
                        [
                            (PropKey::Width, PropValue::Float(80.0)),
                            (PropKey::FlexShrink, PropValue::Float(1.0)),
                        ],
                    ),
                ),
                (
                    3,
                    node(
                        3,
                        1,
                        1,
                        NodeKind::View,
                        [
                            (PropKey::Width, PropValue::Float(80.0)),
                            (PropKey::FlexShrink, PropValue::Float(1.0)),
                        ],
                    ),
                ),
            ]),
        };

        let layouts = calculate(
            &tree,
            Size {
                width: 100.0,
                height: 40.0,
            },
        )
        .expect("layout");

        assert_eq!(layouts[&2].width, 50.0);
        assert_eq!(layouts[&3].width, 50.0);
        assert_eq!(layouts[&3].x, 50.0);
    }

    #[test]
    fn horizontal_auto_margin_consumes_remaining_space_in_rust() {
        let tree = Tree {
            root: 1,
            nodes: BTreeMap::from([
                (
                    1,
                    node(
                        1,
                        0,
                        0,
                        NodeKind::Row,
                        [(PropKey::FlexDirection, PropValue::Integer(2))],
                    ),
                ),
                (
                    2,
                    node(
                        2,
                        1,
                        0,
                        NodeKind::View,
                        [(PropKey::Width, PropValue::Float(40.0))],
                    ),
                ),
                (
                    3,
                    node(
                        3,
                        1,
                        1,
                        NodeKind::View,
                        [
                            (PropKey::Width, PropValue::Float(30.0)),
                            (PropKey::MarginLeftAuto, PropValue::Boolean(true)),
                        ],
                    ),
                ),
            ]),
        };

        let layouts = calculate(
            &tree,
            Size {
                width: 300.0,
                height: 40.0,
            },
        )
        .expect("layout");

        assert_eq!(layouts[&2].x, 0.0);
        assert_eq!(layouts[&3].x, 270.0);
    }

    #[test]
    fn hidden_children_do_not_consume_space_or_gaps() {
        let tree = Tree {
            root: 1,
            nodes: BTreeMap::from([
                (
                    1,
                    node(
                        1,
                        0,
                        0,
                        NodeKind::Row,
                        [
                            (PropKey::FlexDirection, PropValue::Integer(2)),
                            (PropKey::Gap, PropValue::Float(12.0)),
                        ],
                    ),
                ),
                (
                    2,
                    node(
                        2,
                        1,
                        0,
                        NodeKind::View,
                        [
                            (PropKey::Width, PropValue::Float(100.0)),
                            (PropKey::Visible, PropValue::Boolean(false)),
                        ],
                    ),
                ),
                (
                    3,
                    node(
                        3,
                        1,
                        1,
                        NodeKind::View,
                        [(PropKey::Width, PropValue::Float(50.0))],
                    ),
                ),
            ]),
        };

        let layouts = calculate(
            &tree,
            Size {
                width: 300.0,
                height: 40.0,
            },
        )
        .expect("layout");

        assert!(!layouts.contains_key(&2));
        assert_eq!(layouts[&3].x, 0.0);
    }

    #[test]
    fn scroll_content_keeps_its_natural_extent_on_both_axes() {
        let horizontal = Tree {
            root: 1,
            nodes: BTreeMap::from([
                (
                    1,
                    node(
                        1,
                        0,
                        0,
                        NodeKind::Scroll,
                        [
                            (PropKey::ScrollHorizontal, PropValue::Boolean(true)),
                            (PropKey::ScrollFillViewport, PropValue::Boolean(false)),
                        ],
                    ),
                ),
                (
                    2,
                    node(
                        2,
                        1,
                        0,
                        NodeKind::Row,
                        [
                            (PropKey::FlexDirection, PropValue::Integer(2)),
                            (PropKey::Gap, PropValue::Float(8.0)),
                        ],
                    ),
                ),
                (
                    3,
                    node(
                        3,
                        2,
                        0,
                        NodeKind::View,
                        [(PropKey::Width, PropValue::Float(120.0))],
                    ),
                ),
                (
                    4,
                    node(
                        4,
                        2,
                        1,
                        NodeKind::View,
                        [(PropKey::Width, PropValue::Float(120.0))],
                    ),
                ),
                (
                    5,
                    node(
                        5,
                        2,
                        2,
                        NodeKind::View,
                        [(PropKey::Width, PropValue::Float(120.0))],
                    ),
                ),
            ]),
        };
        let horizontal_layouts = calculate(
            &horizontal,
            Size {
                width: 300.0,
                height: 80.0,
            },
        )
        .expect("horizontal scroll layout");
        assert_eq!(horizontal_layouts[&2].width, 376.0);
        assert_eq!(horizontal_layouts[&5].x, 256.0);

        let vertical = Tree {
            root: 1,
            nodes: BTreeMap::from([
                (
                    1,
                    node(
                        1,
                        0,
                        0,
                        NodeKind::Scroll,
                        [(PropKey::ScrollFillViewport, PropValue::Boolean(false))],
                    ),
                ),
                (2, node(2, 1, 0, NodeKind::Column, [])),
                (
                    3,
                    node(
                        3,
                        2,
                        0,
                        NodeKind::View,
                        [(PropKey::Height, PropValue::Float(150.0))],
                    ),
                ),
                (
                    4,
                    node(
                        4,
                        2,
                        1,
                        NodeKind::View,
                        [(PropKey::Height, PropValue::Float(150.0))],
                    ),
                ),
            ]),
        };
        let vertical_layouts = calculate(
            &vertical,
            Size {
                width: 100.0,
                height: 200.0,
            },
        )
        .expect("vertical scroll layout");
        assert_eq!(vertical_layouts[&2].height, 300.0);
        assert_eq!(vertical_layouts[&4].y, 150.0);
    }
}
