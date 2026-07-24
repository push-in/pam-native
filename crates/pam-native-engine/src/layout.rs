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

struct LayoutContext<'a> {
    tree: &'a Tree,
    children: &'a BTreeMap<u64, Vec<&'a Node>>,
    text_scale: f32,
}

#[derive(Clone, Copy)]
struct GridPlacement<'a> {
    child: &'a Node,
    row: usize,
    column: usize,
    width: f32,
    height: f32,
}

#[cfg(test)]
fn calculate(tree: &Tree, viewport: Size) -> Result<BTreeMap<u64, Layout>, LayoutError> {
    calculate_with_text_scale(tree, viewport, 1.0)
}

pub fn calculate_with_text_scale(
    tree: &Tree,
    viewport: Size,
    text_scale: f32,
) -> Result<BTreeMap<u64, Layout>, LayoutError> {
    if !viewport.width.is_finite()
        || !viewport.height.is_finite()
        || viewport.width <= 0.0
        || viewport.height <= 0.0
        || !text_scale.is_finite()
        || text_scale <= 0.0
    {
        return Err(LayoutError::InvalidDimension);
    }
    let children = child_index(tree);
    let context = LayoutContext {
        tree,
        children: &children,
        text_scale,
    };
    let mut result = BTreeMap::new();
    layout_node(
        &context,
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
    for modal in tree
        .nodes
        .values()
        .filter(|node| node.kind == NodeKind::Modal && visible(node) && node.id != tree.root)
    {
        layout_node(
            &context,
            modal.id,
            Layout {
                x: 0.0,
                y: 0.0,
                width: viewport.width,
                height: viewport.height,
            },
            false,
            0,
            &mut result,
        )?;
    }
    Ok(result)
}

fn layout_node(
    context: &LayoutContext<'_>,
    id: u64,
    bounds: Layout,
    resolve_dimensions: bool,
    depth: usize,
    output: &mut BTreeMap<u64, Layout>,
) -> Result<(), LayoutError> {
    if depth > MAX_LAYOUT_DEPTH {
        return Err(LayoutError::DepthExceeded);
    }
    let node = context
        .tree
        .nodes
        .get(&id)
        .ok_or(LayoutError::MissingNode(id))?;
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

    let node_children = context.children.get(&id).map_or(&[][..], Vec::as_slice);
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
            let available_cross = match axis {
                Axis::Vertical => inner.width,
                Axis::Horizontal => inner.height,
            };
            let natural = natural_scroll_extent(
                context.children,
                child,
                axis,
                available_main,
                available_cross,
                context.text_scale,
                depth + 1,
            )?;
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
            layout_node(context, child.id, content_frame, false, depth + 1, output)?;
        }
        return Ok(());
    }
    if matches!(
        node.kind,
        NodeKind::Modal | NodeKind::RefreshControl | NodeKind::NavigationHost
    ) {
        for child in node_children {
            if !visible(child) {
                continue;
            }
            layout_node(context, child.id, inner, true, depth + 1, output)?;
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
            layout_node(context, child.id, child_frame, false, depth + 1, output)?;
        }
        return Ok(());
    }
    if integer(node, PropKey::GridColumns).unwrap_or(0) > 0 {
        layout_grid(context, node, node_children, inner, gap, depth, output)?;
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
        .filter(|child| {
            visible(child)
                && child.kind != NodeKind::Modal
                && integer(child, PropKey::PositionType).unwrap_or(1) != 2
        })
        .collect::<Vec<_>>();
    let absolute_children = node_children
        .iter()
        .copied()
        .filter(|child| {
            visible(child)
                && child.kind != NodeKind::Modal
                && integer(child, PropKey::PositionType).unwrap_or(1) == 2
        })
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
            child_main(
                context.children,
                child,
                axis,
                available_main,
                available_cross,
                context.text_scale,
                depth + 1,
            )?,
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
                intrinsic_cross(
                    context.children,
                    child,
                    axis,
                    available_main,
                    available_cross,
                    context.text_scale,
                    depth + 1,
                )
                .unwrap_or(0.0)
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
        layout_node(context, child.id, child_frame, false, depth + 1, output)?;
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
                intrinsic_extent(
                    context.children,
                    child,
                    Axis::Horizontal,
                    inner.width,
                    inner.height,
                    context.text_scale,
                    depth + 1,
                )
                .unwrap_or(0.0)
            }
        });
        let mut height = explicit_height.unwrap_or_else(|| {
            if let (Some(top), Some(bottom)) = (top, bottom) {
                (inner.height - top - bottom).max(0.0)
            } else {
                intrinsic_extent(
                    context.children,
                    child,
                    Axis::Vertical,
                    width,
                    inner.height,
                    context.text_scale,
                    depth + 1,
                )
                .unwrap_or(0.0)
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
        layout_node(context, child.id, child_frame, false, depth + 1, output)?;
    }
    Ok(())
}

fn layout_grid(
    context: &LayoutContext<'_>,
    node: &Node,
    node_children: &[&Node],
    inner: Layout,
    fallback_gap: f32,
    depth: usize,
    output: &mut BTreeMap<u64, Layout>,
) -> Result<(), LayoutError> {
    let columns = integer(node, PropKey::GridColumns)
        .unwrap_or(12)
        .clamp(1, 64) as usize;
    let column_gap =
        finite_non_negative(number(node, PropKey::GridColumnGap).unwrap_or(fallback_gap))?;
    let row_gap = finite_non_negative(number(node, PropKey::GridRowGap).unwrap_or(fallback_gap))?;
    let unit =
        ((inner.width - column_gap * columns.saturating_sub(1) as f32).max(0.0)) / columns as f32;
    let mut children = node_children
        .iter()
        .copied()
        .filter(|child| {
            visible(child)
                && child.kind != NodeKind::Modal
                && integer(child, PropKey::PositionType).unwrap_or(1) != 2
        })
        .collect::<Vec<_>>();
    children.sort_by_key(|child| {
        (
            responsive_grid_value(child, inner.width, GridValue::Order, 0),
            child.index,
        )
    });

    let mut placements = Vec::with_capacity(children.len());
    let mut row = 0_usize;
    let mut cursor = 0_usize;
    let mut row_heights = Vec::<f32>::new();
    for child in children {
        let span = responsive_grid_value(child, inner.width, GridValue::Span, columns as i64)
            .clamp(1, columns as i64) as usize;
        let offset = responsive_grid_value(child, inner.width, GridValue::Offset, 0)
            .clamp(0, columns.saturating_sub(1) as i64) as usize;
        if cursor > 0 && cursor + offset + span > columns {
            row += 1;
            cursor = 0;
        }
        let column = (cursor + offset).min(columns.saturating_sub(span));
        let width = unit * span as f32 + column_gap * span.saturating_sub(1) as f32;
        let (margin_top, margin_bottom) = margin_main(child, Axis::Vertical);
        let (margin_left, margin_right) = margin_cross(child, Axis::Vertical);
        let content_width = (width - margin_left - margin_right).max(0.0);
        let height = child_main(
            context.children,
            child,
            Axis::Vertical,
            inner.height,
            content_width,
            context.text_scale,
            depth + 1,
        )?;
        if row_heights.len() <= row {
            row_heights.push(0.0);
        }
        row_heights[row] = row_heights[row].max(height + margin_top + margin_bottom);
        placements.push(GridPlacement {
            child,
            row,
            column,
            width,
            height,
        });
        cursor = column + span;
        if cursor >= columns {
            row += 1;
            cursor = 0;
        }
    }
    let mut row_offsets = Vec::with_capacity(row_heights.len());
    let mut y = 0.0;
    for height in &row_heights {
        row_offsets.push(y);
        y += *height + row_gap;
    }
    for placement in placements {
        let (margin_top, _) = margin_main(placement.child, Axis::Vertical);
        let (margin_left, margin_right) = margin_cross(placement.child, Axis::Vertical);
        let x = inner.x + placement.column as f32 * (unit + column_gap) + margin_left;
        let frame = Layout {
            x,
            y: inner.y + row_offsets[placement.row] + margin_top,
            width: (placement.width - margin_left - margin_right).max(0.0),
            height: placement.height,
        };
        layout_node(context, placement.child.id, frame, false, depth + 1, output)?;
    }
    // Absolute children remain relative to the grid's inner box.
    for child in node_children.iter().copied().filter(|child| {
        visible(child)
            && child.kind != NodeKind::Modal
            && integer(child, PropKey::PositionType).unwrap_or(1) == 2
    }) {
        let left = number(child, PropKey::Left).unwrap_or(0.0);
        let top = number(child, PropKey::Top).unwrap_or(0.0);
        let width = dimension(child, PropKey::Width, PropKey::WidthPercent, inner.width)
            .unwrap_or(inner.width);
        let height = child_main(
            context.children,
            child,
            Axis::Vertical,
            inner.height,
            width,
            context.text_scale,
            depth + 1,
        )?;
        layout_node(
            context,
            child.id,
            Layout {
                x: inner.x + left,
                y: inner.y + top,
                width,
                height,
            },
            false,
            depth + 1,
            output,
        )?;
    }
    Ok(())
}

#[derive(Clone, Copy)]
enum GridValue {
    Span,
    Offset,
    Order,
}

fn responsive_grid_value(node: &Node, width: f32, value: GridValue, default: i64) -> i64 {
    let keys = match value {
        GridValue::Span => [
            PropKey::GridSpan,
            PropKey::GridSpanSm,
            PropKey::GridSpanMd,
            PropKey::GridSpanLg,
            PropKey::GridSpanXl,
        ],
        GridValue::Offset => [
            PropKey::GridOffset,
            PropKey::GridOffsetSm,
            PropKey::GridOffsetMd,
            PropKey::GridOffsetLg,
            PropKey::GridOffsetXl,
        ],
        GridValue::Order => [
            PropKey::GridOrder,
            PropKey::GridOrderSm,
            PropKey::GridOrderMd,
            PropKey::GridOrderLg,
            PropKey::GridOrderXl,
        ],
    };
    let level = if width >= 1600.0 {
        4
    } else if width >= 1200.0 {
        3
    } else if width >= 840.0 {
        2
    } else if width >= 600.0 {
        1
    } else {
        0
    };
    (0..=level)
        .rev()
        .find_map(|index| integer(node, keys[index]))
        .unwrap_or(default)
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
    available_cross: f32,
    text_scale: f32,
    depth: usize,
) -> Result<f32, LayoutError> {
    if depth > MAX_LAYOUT_DEPTH {
        return Err(LayoutError::DepthExceeded);
    }
    let explicit = match axis {
        Axis::Vertical => number(node, PropKey::Height),
        Axis::Horizontal => number(node, PropKey::Width),
    };
    if let Some(explicit) = explicit {
        return finite_non_negative(explicit);
    }
    if integer(node, PropKey::GridColumns).unwrap_or(0) > 0 {
        return intrinsic_extent(
            children,
            node,
            axis,
            if axis == Axis::Vertical {
                available_cross
            } else {
                available_main
            },
            if axis == Axis::Vertical {
                available_main
            } else {
                available_cross
            },
            text_scale,
            depth + 1,
        );
    }
    let visible_children = children
        .get(&node.id)
        .map_or(&[][..], Vec::as_slice)
        .iter()
        .copied()
        .filter(|child| {
            visible(child)
                && child.kind != NodeKind::Modal
                && integer(child, PropKey::PositionType).unwrap_or(1) != 2
        })
        .collect::<Vec<_>>();
    if visible_children.is_empty() {
        return intrinsic_extent(
            children,
            node,
            axis,
            if axis == Axis::Vertical {
                available_cross
            } else {
                available_main
            },
            if axis == Axis::Vertical {
                available_main
            } else {
                available_cross
            },
            text_scale,
            depth + 1,
        );
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
        let extent = natural_scroll_extent(
            children,
            child,
            axis,
            available_main,
            available_cross,
            text_scale,
            depth + 1,
        )?;
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

fn intrinsic_extent(
    children: &BTreeMap<u64, Vec<&Node>>,
    node: &Node,
    requested_axis: Axis,
    available_width: f32,
    available_height: f32,
    text_scale: f32,
    depth: usize,
) -> Result<f32, LayoutError> {
    if depth > MAX_LAYOUT_DEPTH {
        return Err(LayoutError::DepthExceeded);
    }
    // A percentage on an auto-sized axis is indefinite in flexbox. Resolving it
    // against the provisional intrinsic extent feeds the result back into its
    // own parent and can grow a finite tree without bound. Point dimensions are
    // definite here; percentages are resolved later by `layout_node`, once the
    // containing block has a final frame.
    let explicit = match requested_axis {
        Axis::Vertical => number(node, PropKey::Height),
        Axis::Horizontal => number(node, PropKey::Width),
    };
    if let Some(explicit) = explicit {
        return finite_non_negative(explicit);
    }

    if node.kind == NodeKind::Text {
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
        let inner_width = (available_width - padding_left - padding_right).max(0.0);
        let text = text_extent(node, requested_axis, inner_width, text_scale);
        let padding_extent = match requested_axis {
            Axis::Vertical => padding_top + padding_bottom,
            Axis::Horizontal => padding_left + padding_right,
        };

        return finite_non_negative(text + padding_extent);
    }

    let node_children = children
        .get(&node.id)
        .map_or(&[][..], Vec::as_slice)
        .iter()
        .copied()
        .filter(|child| {
            visible(child)
                && child.kind != NodeKind::Modal
                && integer(child, PropKey::PositionType).unwrap_or(1) != 2
        })
        .collect::<Vec<_>>();
    if node_children.is_empty() || !content_sized(node) {
        return finite_non_negative(leaf_intrinsic(node, requested_axis, text_scale));
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
    let inner_width = (available_width - padding_left - padding_right).max(0.0);
    let inner_height = (available_height - padding_top - padding_bottom).max(0.0);
    if integer(node, PropKey::GridColumns).unwrap_or(0) > 0 {
        let content = match requested_axis {
            Axis::Horizontal => inner_width,
            Axis::Vertical => grid_intrinsic_height(
                children,
                node,
                &node_children,
                inner_width,
                inner_height,
                text_scale,
                depth + 1,
            )?,
        };
        let padding_extent = match requested_axis {
            Axis::Vertical => padding_top + padding_bottom,
            Axis::Horizontal => padding_left + padding_right,
        };
        return finite_non_negative(content + padding_extent);
    }
    let direction = integer(node, PropKey::FlexDirection)
        .unwrap_or_else(|| if node.kind == NodeKind::Row { 2 } else { 1 });
    let flow_axis = if matches!(direction, 2 | 4) {
        Axis::Horizontal
    } else {
        Axis::Vertical
    };
    let gap = finite(number(node, PropKey::Gap).unwrap_or(0.0))?;
    let mut child_extents = Vec::with_capacity(node_children.len());
    for child in node_children {
        let child_extent = constrained_intrinsic_extent(
            children,
            child,
            requested_axis,
            inner_width,
            inner_height,
            text_scale,
            depth + 1,
        )?;
        let (before, after) = if requested_axis == flow_axis {
            margin_main(child, flow_axis)
        } else {
            margin_cross(child, flow_axis)
        };
        child_extents.push(child_extent + before + after);
    }
    let content = if requested_axis == flow_axis {
        child_extents.iter().sum::<f32>() + gap * child_extents.len().saturating_sub(1) as f32
    } else {
        child_extents.into_iter().fold(0.0, f32::max)
    };
    let padding_extent = match requested_axis {
        Axis::Vertical => padding_top + padding_bottom,
        Axis::Horizontal => padding_left + padding_right,
    };

    finite_non_negative(content + padding_extent)
}

fn grid_intrinsic_height(
    children_index: &BTreeMap<u64, Vec<&Node>>,
    node: &Node,
    node_children: &[&Node],
    width: f32,
    available_height: f32,
    text_scale: f32,
    depth: usize,
) -> Result<f32, LayoutError> {
    let columns = integer(node, PropKey::GridColumns)
        .unwrap_or(12)
        .clamp(1, 64) as usize;
    let fallback_gap = finite(number(node, PropKey::Gap).unwrap_or(0.0))?;
    let column_gap =
        finite_non_negative(number(node, PropKey::GridColumnGap).unwrap_or(fallback_gap))?;
    let row_gap = finite_non_negative(number(node, PropKey::GridRowGap).unwrap_or(fallback_gap))?;
    let unit = ((width - column_gap * columns.saturating_sub(1) as f32).max(0.0)) / columns as f32;
    let mut flow = node_children
        .iter()
        .copied()
        .filter(|child| {
            visible(child)
                && child.kind != NodeKind::Modal
                && integer(child, PropKey::PositionType).unwrap_or(1) != 2
        })
        .collect::<Vec<_>>();
    flow.sort_by_key(|child| {
        (
            responsive_grid_value(child, width, GridValue::Order, 0),
            child.index,
        )
    });
    let mut row_heights = Vec::<f32>::new();
    let mut row = 0_usize;
    let mut cursor = 0_usize;
    for child in flow {
        let span = responsive_grid_value(child, width, GridValue::Span, columns as i64)
            .clamp(1, columns as i64) as usize;
        let offset = responsive_grid_value(child, width, GridValue::Offset, 0)
            .clamp(0, columns.saturating_sub(1) as i64) as usize;
        if cursor > 0 && cursor + offset + span > columns {
            row += 1;
            cursor = 0;
        }
        let column = (cursor + offset).min(columns.saturating_sub(span));
        let cell_width = unit * span as f32 + column_gap * span.saturating_sub(1) as f32;
        let (margin_left, margin_right) = margin_cross(child, Axis::Vertical);
        let (margin_top, margin_bottom) = margin_main(child, Axis::Vertical);
        let child_height = child_main(
            children_index,
            child,
            Axis::Vertical,
            available_height,
            (cell_width - margin_left - margin_right).max(0.0),
            text_scale,
            depth + 1,
        )?;
        if row_heights.len() <= row {
            row_heights.push(0.0);
        }
        row_heights[row] = row_heights[row].max(child_height + margin_top + margin_bottom);
        cursor = column + span;
        if cursor >= columns {
            row += 1;
            cursor = 0;
        }
    }
    Ok(row_heights.iter().sum::<f32>() + row_gap * row_heights.len().saturating_sub(1) as f32)
}

fn constrained_intrinsic_extent(
    children: &BTreeMap<u64, Vec<&Node>>,
    node: &Node,
    axis: Axis,
    available_width: f32,
    available_height: f32,
    text_scale: f32,
    depth: usize,
) -> Result<f32, LayoutError> {
    let extent = intrinsic_extent(
        children,
        node,
        axis,
        available_width,
        available_height,
        text_scale,
        depth,
    )?;
    let (minimum, maximum) = match axis {
        Axis::Vertical => (
            number(node, PropKey::MinHeight),
            number(node, PropKey::MaxHeight),
        ),
        Axis::Horizontal => (
            number(node, PropKey::MinWidth),
            number(node, PropKey::MaxWidth),
        ),
    };
    constrained(extent, minimum, maximum)
}

fn content_sized(node: &Node) -> bool {
    match node.kind {
        NodeKind::Screen
        | NodeKind::Column
        | NodeKind::Row
        | NodeKind::View
        | NodeKind::Pressable
        | NodeKind::ImageBackground
        | NodeKind::KeyboardAvoidingView
        | NodeKind::SafeAreaView
        | NodeKind::InputAccessoryView
        | NodeKind::NavigationHost => true,
        // Authored native components are frequently compound controls (forms,
        // calendars, tables, conversations, etc.). Their host participates in
        // intrinsic measurement so descendants cannot escape and overlap the
        // next sibling. Grid is the exception: its Android view owns the
        // responsive row/column plan, while its engine children retain a
        // simple fallback layout. Counting that fallback would reserve both
        // arrangements and leave a large blank tail below the actual grid.
        NodeKind::CustomView => !matches!(
            node.properties.get(&PropKey::HostName),
            Some(pam_native_protocol::PropValue::String(name))
                if name == "pam.mobile_ui.grid"
        ),
        _ => false,
    }
}

fn leaf_intrinsic(node: &Node, axis: Axis, text_scale: f32) -> f32 {
    match (axis, node.kind) {
        (Axis::Horizontal, NodeKind::Text) => text_extent(node, axis, f32::INFINITY, text_scale),
        (Axis::Horizontal, NodeKind::Switch) => 52.0,
        (Axis::Horizontal, NodeKind::ActivityIndicator) => DEFAULT_CONTROL_HEIGHT,
        (Axis::Horizontal, NodeKind::Image | NodeKind::ImageBackground) => DEFAULT_IMAGE_HEIGHT,
        (Axis::Horizontal, NodeKind::Spacer) => 8.0,
        (Axis::Horizontal, NodeKind::StatusBar) => 0.0,
        (Axis::Horizontal, _) => 100.0,
        (
            Axis::Vertical,
            NodeKind::Button
            | NodeKind::Input
            | NodeKind::Pressable
            | NodeKind::Switch
            | NodeKind::ActivityIndicator,
        ) => DEFAULT_CONTROL_HEIGHT,
        (Axis::Vertical, NodeKind::Image | NodeKind::ImageBackground) => DEFAULT_IMAGE_HEIGHT,
        (
            Axis::Vertical,
            NodeKind::List | NodeKind::SectionList | NodeKind::Scroll | NodeKind::RefreshControl,
        ) => DEFAULT_LIST_HEIGHT,
        (Axis::Vertical, NodeKind::Spacer) => 8.0,
        (Axis::Vertical, NodeKind::StatusBar) => 0.0,
        (Axis::Vertical, _) => DEFAULT_CONTROL_HEIGHT,
    }
}

fn text_extent(node: &Node, axis: Axis, available_width: f32, device_text_scale: f32) -> f32 {
    let allow_scaling = !matches!(
        node.properties.get(&PropKey::TextAllowFontScaling),
        Some(pam_native_protocol::PropValue::Boolean(false))
    );
    let maximum_scale = number(node, PropKey::TextMaxFontSizeMultiplier).unwrap_or(0.0);
    let text_scale = if !allow_scaling {
        1.0
    } else if maximum_scale > 0.0 {
        device_text_scale.min(maximum_scale.max(1.0))
    } else {
        device_text_scale
    };
    let base_font_size = number(node, PropKey::FontSize).unwrap_or(14.0).max(1.0);
    let font_size = base_font_size * text_scale;
    let line_height = number(node, PropKey::LineHeight)
        .unwrap_or((base_font_size * 1.4).max(DEFAULT_TEXT_HEIGHT / 2.0))
        * text_scale;
    let letter_spacing = number(node, PropKey::LetterSpacing).unwrap_or(0.0) * font_size;
    let source_text = match node.properties.get(&PropKey::Text) {
        Some(pam_native_protocol::PropValue::String(value)) => value.as_str(),
        _ => "",
    };
    let text = transformed_text_for_measurement(
        source_text,
        integer(node, PropKey::TextTransform).unwrap_or(1),
    );
    let lines = wrapped_text_lines(&text, font_size, letter_spacing, available_width);
    match axis {
        Axis::Vertical => {
            let maximum_lines = integer(node, PropKey::NumberOfLines)
                .filter(|value| *value > 0)
                .map_or(lines.len(), |value| value as usize);
            line_height * lines.len().min(maximum_lines).max(1) as f32
        }
        Axis::Horizontal => lines
            .iter()
            .map(|line| estimated_text_width(line, font_size, letter_spacing))
            .fold(0.0, f32::max)
            .min(available_width.max(0.0)),
    }
}

fn transformed_text_for_measurement(text: &str, transform: i64) -> String {
    match transform {
        2 => text.to_uppercase(),
        3 => text.to_lowercase(),
        4 => {
            let mut capitalize_next = true;
            text.chars()
                .flat_map(|character| {
                    if character.is_whitespace() {
                        capitalize_next = true;
                        character.to_string().chars().collect::<Vec<_>>()
                    } else if capitalize_next && character.is_alphabetic() {
                        capitalize_next = false;
                        character.to_uppercase().collect::<Vec<_>>()
                    } else {
                        capitalize_next = false;
                        character.to_string().chars().collect::<Vec<_>>()
                    }
                })
                .collect()
        }
        _ => text.to_owned(),
    }
}

fn wrapped_text_lines(
    text: &str,
    font_size: f32,
    letter_spacing: f32,
    available_width: f32,
) -> Vec<&str> {
    let hard_lines = text.split('\n').collect::<Vec<_>>();
    if !available_width.is_finite() || available_width <= 0.0 {
        return hard_lines;
    }
    let mut result = Vec::new();
    for hard_line in hard_lines {
        if hard_line.is_empty() {
            result.push(hard_line);
            continue;
        }
        let mut start = 0;
        let mut last_break = None;
        let mut width = 0.0;
        for (offset, character) in hard_line.char_indices() {
            let advance = estimated_character_width(character, font_size) + letter_spacing;
            if character.is_whitespace() {
                last_break = Some(offset);
            }
            // Intrinsic parents are often exactly the sum of their text and
            // padding. Allow a small floating-point tolerance so measuring
            // that child again does not wrap its last word onto a phantom
            // second line.
            let fit_tolerance = 0.5_f32.max(font_size * 0.02);
            if width + advance > available_width + fit_tolerance && offset > start {
                let end = last_break
                    .filter(|position| *position > start)
                    .unwrap_or(offset);
                result.push(hard_line[start..end].trim_end());
                start = if end < offset {
                    hard_line[end..]
                        .char_indices()
                        .find(|(_, character)| !character.is_whitespace())
                        .map_or(offset, |(position, _)| end + position)
                } else {
                    offset
                };
                last_break = None;
                width = estimated_text_width(&hard_line[start..offset], font_size, letter_spacing);
            }
            width += advance;
        }
        result.push(hard_line[start..].trim_end());
    }
    result
}

fn estimated_text_width(text: &str, font_size: f32, letter_spacing: f32) -> f32 {
    let mut characters = text.chars().peekable();
    let mut width = 0.0;
    while let Some(character) = characters.next() {
        width += estimated_character_width(character, font_size);
        if characters.peek().is_some() {
            width += letter_spacing;
        }
    }
    width
}

fn estimated_character_width(character: char, font_size: f32) -> f32 {
    let em = match character {
        ' ' | '\t' => 0.33,
        'i' | 'l' | 'I' | '!' | '|' | '.' | ',' | ':' | ';' | '\'' => 0.30,
        'm' | 'w' | 'M' | 'W' | '@' | '%' | '&' => 0.88,
        character if character.is_ascii_uppercase() => 0.68,
        character if character.is_ascii_digit() => 0.56,
        character if character.is_ascii() => 0.54,
        _ => 1.0,
    };
    font_size * em
}

fn intrinsic_cross(
    children: &BTreeMap<u64, Vec<&Node>>,
    node: &Node,
    parent_axis: Axis,
    available_main: f32,
    available_cross: f32,
    text_scale: f32,
    depth: usize,
) -> Result<f32, LayoutError> {
    let (requested_axis, available_width, available_height) = match parent_axis {
        Axis::Vertical => (Axis::Horizontal, available_cross, available_main),
        Axis::Horizontal => (Axis::Vertical, available_main, available_cross),
    };
    intrinsic_extent(
        children,
        node,
        requested_axis,
        available_width,
        available_height,
        text_scale,
        depth,
    )
}

fn child_main(
    children: &BTreeMap<u64, Vec<&Node>>,
    node: &Node,
    axis: Axis,
    available_main: f32,
    available_cross: f32,
    text_scale: f32,
    depth: usize,
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
        _ => {
            let (available_width, available_height) = match axis {
                Axis::Vertical => (explicit_cross.unwrap_or(available_cross), available_main),
                Axis::Horizontal => (available_main, explicit_cross.unwrap_or(available_cross)),
            };
            intrinsic_extent(
                children,
                node,
                axis,
                available_width,
                available_height,
                text_scale,
                depth,
            )
            .unwrap_or(0.0)
        }
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

    #[test]
    fn percentage_height_inside_auto_content_resolves_after_intrinsic_measurement() {
        let tree = Tree {
            root: 1,
            nodes: BTreeMap::from([
                (1, node(1, 0, 0, NodeKind::Scroll, [])),
                (
                    2,
                    node(
                        2,
                        1,
                        0,
                        NodeKind::Column,
                        [(PropKey::FlexDirection, PropValue::Integer(1))],
                    ),
                ),
                (
                    3,
                    node(
                        3,
                        2,
                        0,
                        NodeKind::View,
                        [(PropKey::Padding, PropValue::Float(16.0))],
                    ),
                ),
                (
                    4,
                    node(
                        4,
                        3,
                        0,
                        NodeKind::Pressable,
                        [(PropKey::FlexDirection, PropValue::Integer(2))],
                    ),
                ),
                (
                    5,
                    node(
                        5,
                        4,
                        0,
                        NodeKind::Input,
                        [(PropKey::HeightPercent, PropValue::Float(100.0))],
                    ),
                ),
                (
                    6,
                    node(
                        6,
                        4,
                        1,
                        NodeKind::View,
                        [
                            (PropKey::Width, PropValue::Float(24.0)),
                            (PropKey::Height, PropValue::Float(24.0)),
                        ],
                    ),
                ),
            ]),
        };

        let layouts = calculate(
            &tree,
            Size {
                width: 360.0,
                height: 640.0,
            },
        )
        .expect("layout");

        assert_eq!(layouts[&3].height, 80.0);
        assert_eq!(layouts[&4].height, 48.0);
        assert_eq!(layouts[&5].height, 48.0);
        assert!(layouts.values().all(|frame| frame.height <= 640.0));
    }

    #[test]
    fn modal_is_a_viewport_portal_and_does_not_consume_parent_flow() {
        let tree = Tree {
            root: 1,
            nodes: BTreeMap::from([
                (
                    1,
                    node(
                        1,
                        0,
                        0,
                        NodeKind::Column,
                        [(PropKey::Gap, PropValue::Float(10.0))],
                    ),
                ),
                (
                    2,
                    node(
                        2,
                        1,
                        0,
                        NodeKind::View,
                        [(PropKey::Height, PropValue::Float(20.0))],
                    ),
                ),
                (3, node(3, 1, 1, NodeKind::Modal, [])),
                (
                    4,
                    node(
                        4,
                        3,
                        0,
                        NodeKind::View,
                        [
                            (PropKey::PositionType, PropValue::Integer(2)),
                            (PropKey::Left, PropValue::Float(0.0)),
                            (PropKey::Top, PropValue::Float(0.0)),
                            (PropKey::Right, PropValue::Float(0.0)),
                            (PropKey::Bottom, PropValue::Float(0.0)),
                        ],
                    ),
                ),
                (
                    5,
                    node(
                        5,
                        1,
                        2,
                        NodeKind::View,
                        [(PropKey::Height, PropValue::Float(20.0))],
                    ),
                ),
            ]),
        };

        let layouts = calculate(
            &tree,
            Size {
                width: 360.0,
                height: 640.0,
            },
        )
        .expect("modal portal layout");

        assert_eq!(layouts[&3].width, 360.0);
        assert_eq!(layouts[&3].height, 640.0);
        assert_eq!(layouts[&4].width, 360.0);
        assert_eq!(layouts[&4].height, 640.0);
        assert_eq!(layouts[&5].y, 30.0);
    }

    #[test]
    fn nested_auto_sized_containers_expand_to_fit_wrapped_text_and_controls() {
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
                            (PropKey::AlignItems, PropValue::Integer(2)),
                            (PropKey::JustifyContent, PropValue::Integer(2)),
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
                            (PropKey::Width, PropValue::Float(300.0)),
                            (PropKey::Padding, PropValue::Float(24.0)),
                            (PropKey::Gap, PropValue::Float(24.0)),
                        ],
                    ),
                ),
                (
                    3,
                    node(
                        3,
                        2,
                        0,
                        NodeKind::Column,
                        [(PropKey::Gap, PropValue::Float(8.0))],
                    ),
                ),
                (
                    4,
                    node(
                        4,
                        3,
                        0,
                        NodeKind::Text,
                        [
                            (
                                PropKey::Text,
                                PropValue::String("Build native apps with PHP".into()),
                            ),
                            (PropKey::FontSize, PropValue::Float(36.0)),
                            (PropKey::LineHeight, PropValue::Float(50.4)),
                        ],
                    ),
                ),
                (
                    5,
                    node(
                        5,
                        3,
                        1,
                        NodeKind::Text,
                        [
                            (
                                PropKey::Text,
                                PropValue::String(
                                    "Accessible official components on the PAM Native renderer."
                                        .into(),
                                ),
                            ),
                            (PropKey::FontSize, PropValue::Float(14.0)),
                            (PropKey::LineHeight, PropValue::Float(19.6)),
                        ],
                    ),
                ),
                (
                    6,
                    node(
                        6,
                        2,
                        1,
                        NodeKind::Pressable,
                        [
                            (PropKey::MinHeight, PropValue::Float(52.0)),
                            (PropKey::PaddingHorizontal, PropValue::Float(24.0)),
                        ],
                    ),
                ),
                (
                    7,
                    node(
                        7,
                        6,
                        0,
                        NodeKind::Text,
                        [
                            (PropKey::Text, PropValue::String("Native taps: 0".into())),
                            (PropKey::FontSize, PropValue::Float(14.0)),
                            (PropKey::LineHeight, PropValue::Float(19.6)),
                        ],
                    ),
                ),
            ]),
        };

        let layouts = calculate(
            &tree,
            Size {
                width: 360.0,
                height: 800.0,
            },
        )
        .expect("content-sized layout");
        let card = layouts[&2];
        let stack = layouts[&3];
        let heading = layouts[&4];
        let description = layouts[&5];
        let button = layouts[&6];

        assert!(card.height > DEFAULT_CONTROL_HEIGHT);
        assert!(heading.height >= 100.0, "heading should wrap to two lines");
        assert!(description.y >= heading.y + heading.height);
        assert!(button.y >= stack.y + stack.height);
        assert!(
            button.y + button.height <= card.y + card.height,
            "card={card:?} stack={stack:?} button={button:?}",
        );
    }

    #[test]
    fn compound_custom_view_expands_to_fit_its_children() {
        let tree = Tree {
            root: 1,
            nodes: BTreeMap::from([
                (1, node(1, 0, 0, NodeKind::Column, [])),
                (
                    2,
                    node(
                        2,
                        1,
                        0,
                        NodeKind::CustomView,
                        [
                            (PropKey::PaddingVertical, PropValue::Float(2.0)),
                            (PropKey::Gap, PropValue::Float(4.0)),
                        ],
                    ),
                ),
                (
                    3,
                    node(
                        3,
                        2,
                        0,
                        NodeKind::Text,
                        [
                            (PropKey::Text, PropValue::String("Email".into())),
                            (PropKey::LineHeight, PropValue::Float(20.0)),
                        ],
                    ),
                ),
                (4, node(4, 2, 1, NodeKind::Input, [])),
                (
                    5,
                    node(
                        5,
                        2,
                        2,
                        NodeKind::Text,
                        [
                            (
                                PropKey::Text,
                                PropValue::String("We never share your email.".into()),
                            ),
                            (PropKey::LineHeight, PropValue::Float(20.0)),
                        ],
                    ),
                ),
                (
                    6,
                    node(
                        6,
                        1,
                        1,
                        NodeKind::View,
                        [(PropKey::Height, PropValue::Float(24.0))],
                    ),
                ),
            ]),
        };

        let layouts = calculate(
            &tree,
            Size {
                width: 320.0,
                height: 640.0,
            },
        )
        .expect("compound custom view layout");

        assert_eq!(layouts[&2].height, 100.0);
        assert_eq!(layouts[&6].y, 100.0);
        assert!(layouts[&5].y >= layouts[&4].y + layouts[&4].height);
    }

    #[test]
    fn native_grid_uses_its_authored_minimum_instead_of_fallback_child_flow() {
        let tree = Tree {
            root: 1,
            nodes: BTreeMap::from([
                (1, node(1, 0, 0, NodeKind::Column, [])),
                (
                    2,
                    node(
                        2,
                        1,
                        0,
                        NodeKind::CustomView,
                        [
                            (
                                PropKey::HostName,
                                PropValue::String("pam.mobile_ui.grid".into()),
                            ),
                            (PropKey::MinHeight, PropValue::Float(108.0)),
                            (PropKey::Gap, PropValue::Float(12.0)),
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
                        [(PropKey::Height, PropValue::Float(108.0))],
                    ),
                ),
                (
                    4,
                    node(
                        4,
                        2,
                        1,
                        NodeKind::View,
                        [(PropKey::Height, PropValue::Float(108.0))],
                    ),
                ),
                (
                    5,
                    node(
                        5,
                        1,
                        1,
                        NodeKind::View,
                        [(PropKey::Height, PropValue::Float(24.0))],
                    ),
                ),
            ]),
        };

        let layouts = calculate(
            &tree,
            Size {
                width: 320.0,
                height: 640.0,
            },
        )
        .expect("native grid layout");

        assert_eq!(layouts[&2].height, 108.0);
        assert_eq!(layouts[&5].y, 108.0);
    }

    #[test]
    fn text_measurement_honors_explicit_line_height_and_number_of_lines() {
        let text = node(
            1,
            0,
            0,
            NodeKind::Text,
            [
                (
                    PropKey::Text,
                    PropValue::String("one two three four five six".into()),
                ),
                (PropKey::FontSize, PropValue::Float(20.0)),
                (PropKey::LineHeight, PropValue::Float(30.0)),
                (PropKey::NumberOfLines, PropValue::Integer(2)),
            ],
        );

        assert_eq!(text_extent(&text, Axis::Vertical, 70.0, 1.0), 60.0);
    }

    #[test]
    fn text_measurement_tracks_android_font_scale_and_respects_opt_out() {
        let scalable = node(
            1,
            0,
            0,
            NodeKind::Text,
            [
                (PropKey::Text, PropValue::String("PamUI".into())),
                (PropKey::FontSize, PropValue::Float(20.0)),
                (PropKey::LineHeight, PropValue::Float(28.0)),
            ],
        );
        let fixed = node(
            2,
            0,
            0,
            NodeKind::Text,
            [
                (PropKey::Text, PropValue::String("PamUI".into())),
                (PropKey::FontSize, PropValue::Float(20.0)),
                (PropKey::LineHeight, PropValue::Float(28.0)),
                (PropKey::TextAllowFontScaling, PropValue::Boolean(false)),
            ],
        );

        assert_eq!(text_extent(&scalable, Axis::Vertical, 200.0, 1.5), 42.0,);
        assert_eq!(text_extent(&fixed, Axis::Vertical, 200.0, 1.5), 28.0);
    }

    #[test]
    fn text_measurement_uses_the_rendered_case_transform() {
        let plain = node(
            1,
            0,
            0,
            NodeKind::Text,
            [
                (
                    PropKey::Text,
                    PropValue::String("pushinbr/pam-mobile-ui".into()),
                ),
                (PropKey::FontSize, PropValue::Float(12.0)),
            ],
        );
        let uppercase = node(
            2,
            0,
            0,
            NodeKind::Text,
            [
                (
                    PropKey::Text,
                    PropValue::String("pushinbr/pam-mobile-ui".into()),
                ),
                (PropKey::FontSize, PropValue::Float(12.0)),
                (PropKey::TextTransform, PropValue::Integer(2)),
            ],
        );

        assert!(
            text_extent(&uppercase, Axis::Horizontal, 400.0, 1.0)
                > text_extent(&plain, Axis::Horizontal, 400.0, 1.0),
        );
    }

    #[test]
    fn exact_intrinsic_text_width_does_not_create_a_phantom_line() {
        let font_size = 15.4;
        let letter_spacing = 0.0;
        let label = "Open modal";
        let intrinsic = estimated_text_width(label, font_size, letter_spacing);

        assert_eq!(
            wrapped_text_lines(label, font_size, letter_spacing, intrinsic),
            vec![label],
        );
    }

    #[test]
    fn intrinsic_text_extent_includes_authored_padding() {
        let padded = node(
            1,
            0,
            0,
            NodeKind::Text,
            [
                (PropKey::Text, PropValue::String("Cell".into())),
                (PropKey::FontSize, PropValue::Float(16.0)),
                (PropKey::LineHeight, PropValue::Float(22.0)),
                (PropKey::PaddingHorizontal, PropValue::Float(24.0)),
                (PropKey::PaddingVertical, PropValue::Float(14.0)),
            ],
        );

        assert_eq!(
            intrinsic_extent(
                &BTreeMap::new(),
                &padded,
                Axis::Vertical,
                320.0,
                640.0,
                1.0,
                0,
            )
            .expect("padded text height"),
            50.0,
        );
        assert!(
            intrinsic_extent(
                &BTreeMap::new(),
                &padded,
                Axis::Horizontal,
                320.0,
                640.0,
                1.0,
                0,
            )
            .expect("padded text width")
                > 48.0,
        );
    }

    #[test]
    fn responsive_grid_wraps_spans_and_reflows_at_breakpoints() {
        let mut nodes = BTreeMap::new();
        nodes.insert(
            1,
            node(
                1,
                0,
                0,
                NodeKind::Row,
                [
                    (PropKey::GridColumns, PropValue::Integer(12)),
                    (PropKey::GridColumnGap, PropValue::Float(12.0)),
                    (PropKey::GridRowGap, PropValue::Float(8.0)),
                ],
            ),
        );
        for (index, id) in [2_u64, 3, 4].into_iter().enumerate() {
            nodes.insert(
                id,
                node(
                    id,
                    1,
                    index as u32,
                    NodeKind::View,
                    [
                        (PropKey::GridSpan, PropValue::Integer(6)),
                        (PropKey::GridSpanMd, PropValue::Integer(4)),
                        (PropKey::Height, PropValue::Float(40.0)),
                    ],
                ),
            );
        }
        let tree = Tree { root: 1, nodes };

        let phone = calculate(
            &tree,
            Size {
                width: 360.0,
                height: 300.0,
            },
        )
        .expect("phone grid");
        assert_eq!(phone[&2].width, 174.0);
        assert_eq!(phone[&3].x, 186.0);
        assert_eq!(phone[&4].y, 48.0);

        let tablet = calculate(
            &tree,
            Size {
                width: 900.0,
                height: 300.0,
            },
        )
        .expect("tablet grid");
        assert_eq!(tablet[&2].width, 292.0);
        assert_eq!(tablet[&3].x, 304.0);
        assert_eq!(tablet[&4].x, 608.0);
        assert_eq!(tablet[&4].y, 0.0);
    }
}
