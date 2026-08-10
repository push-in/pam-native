use std::cell::Cell;
use std::collections::{BTreeMap, BTreeSet};

use pam_native_protocol::{Layout, Node, NodeKind, PropKey, Tree};

use crate::font_metrics::TextMetrics;

const DEFAULT_TEXT_HEIGHT: f32 = 28.0;
const DEFAULT_CONTROL_HEIGHT: f32 = 48.0;
const DEFAULT_SWITCH_WIDTH: f32 = 46.5;
const DEFAULT_SWITCH_HEIGHT: f32 = 27.0;
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
    Baseline,
}

struct LayoutContext<'a> {
    tree: &'a Tree,
    children: &'a BTreeMap<u64, Vec<&'a Node>>,
    text_scale: f32,
    text_metrics: &'a TextMetrics,
    previous: Option<&'a BTreeMap<u64, Layout>>,
    dirty_path: Option<&'a BTreeSet<u64>>,
    visited_nodes: Cell<usize>,
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

#[cfg(test)]
fn calculate_with_text_scale(
    tree: &Tree,
    viewport: Size,
    text_scale: f32,
) -> Result<BTreeMap<u64, Layout>, LayoutError> {
    calculate_with_text_metrics(tree, viewport, text_scale, &TextMetrics::new())
}

pub fn calculate_with_text_metrics(
    tree: &Tree,
    viewport: Size,
    text_scale: f32,
    text_metrics: &TextMetrics,
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
        text_metrics,
        previous: None,
        dirty_path: None,
        visited_nodes: Cell::new(0),
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

pub fn calculate_incremental_with_text_metrics(
    tree: &Tree,
    viewport: Size,
    text_scale: f32,
    text_metrics: &TextMetrics,
    previous: &BTreeMap<u64, Layout>,
    dirty_nodes: &BTreeSet<u64>,
) -> Result<(BTreeMap<u64, Layout>, usize), LayoutError> {
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
    let mut dirty_path = BTreeSet::new();
    for dirty in dirty_nodes {
        let mut current = *dirty;
        for _ in 0..=MAX_LAYOUT_DEPTH {
            if !dirty_path.insert(current) {
                break;
            }
            let Some(node) = tree.nodes.get(&current) else {
                return Err(LayoutError::MissingNode(current));
            };
            if node.parent == 0 {
                break;
            }
            current = node.parent;
        }
    }
    let context = LayoutContext {
        tree,
        children: &children,
        text_scale,
        text_metrics,
        previous: Some(previous),
        dirty_path: Some(&dirty_path),
        visited_nodes: Cell::new(0),
    };
    let mut result = previous.clone();
    let mut pending = dirty_nodes.iter().copied().collect::<Vec<_>>();
    while let Some(id) = pending.pop() {
        result.remove(&id);
        if let Some(descendants) = children.get(&id) {
            pending.extend(descendants.iter().map(|node| node.id));
        }
    }
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
    Ok((result, context.visited_nodes.get()))
}

fn layout_node(
    context: &LayoutContext<'_>,
    id: u64,
    bounds: Layout,
    resolve_dimensions: bool,
    depth: usize,
    output: &mut BTreeMap<u64, Layout>,
) -> Result<(), LayoutError> {
    context
        .visited_nodes
        .set(context.visited_nodes.get().saturating_add(1));
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
    if context
        .previous
        .is_some_and(|previous| previous.get(&id) == Some(&frame))
        && context
            .dirty_path
            .is_some_and(|dirty_path| !dirty_path.contains(&id))
    {
        return Ok(());
    }
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
    let gap = finite_non_negative(number(node, PropKey::Gap).unwrap_or(0.0))?;
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
                context.text_metrics,
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
    if node.kind == NodeKind::Modal {
        let dialog = integer(node, PropKey::ModalPresentation).unwrap_or(2) == 2;
        for child in node_children {
            if !visible(child) {
                continue;
            }
            if !dialog {
                layout_node(context, child.id, inner, true, depth + 1, output)?;
                continue;
            }

            let left = dimension(child, PropKey::Left, PropKey::LeftPercent, inner.width);
            let right = dimension(child, PropKey::Right, PropKey::RightPercent, inner.width);
            let top = dimension(child, PropKey::Top, PropKey::TopPercent, inner.height);
            let bottom = dimension(child, PropKey::Bottom, PropKey::BottomPercent, inner.height);
            let width = constrained(
                dimension(child, PropKey::Width, PropKey::WidthPercent, inner.width)
                    .or_else(|| match (left, right) {
                        (Some(left), Some(right)) => Some((inner.width - left - right).max(0.0)),
                        _ => None,
                    })
                    .unwrap_or(intrinsic_extent(
                        context.children,
                        child,
                        Axis::Horizontal,
                        inner.width,
                        inner.height,
                        context.text_scale,
                        context.text_metrics,
                        depth + 1,
                    )?),
                number(child, PropKey::MinWidth),
                dimension(
                    child,
                    PropKey::MaxWidth,
                    PropKey::MaxWidthPercent,
                    inner.width,
                ),
            )?
            .min(inner.width);
            let height = constrained(
                dimension(child, PropKey::Height, PropKey::HeightPercent, inner.height)
                    .or_else(|| match (top, bottom) {
                        (Some(top), Some(bottom)) => Some((inner.height - top - bottom).max(0.0)),
                        _ => None,
                    })
                    .unwrap_or(intrinsic_extent(
                        context.children,
                        child,
                        Axis::Vertical,
                        width,
                        inner.height,
                        context.text_scale,
                        context.text_metrics,
                        depth + 1,
                    )?),
                number(child, PropKey::MinHeight),
                dimension(
                    child,
                    PropKey::MaxHeight,
                    PropKey::MaxHeightPercent,
                    inner.height,
                ),
            )?
            .min(inner.height);
            layout_node(
                context,
                child.id,
                Layout {
                    x: inner.x + (inner.width - width) / 2.0,
                    y: inner.y + (inner.height - height) / 2.0,
                    width,
                    height,
                },
                false,
                depth + 1,
                output,
            )?;
        }
        return Ok(());
    }
    if matches!(
        node.kind,
        NodeKind::RefreshControl | NodeKind::NavigationHost | NodeKind::TabHost
    ) {
        for child in node_children {
            if visible(child) {
                layout_node(context, child.id, inner, true, depth + 1, output)?;
            }
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
    if node.kind == NodeKind::VirtualList {
        let horizontal = boolean(node, PropKey::ListHorizontal);
        let columns = if horizontal {
            1
        } else {
            integer(node, PropKey::ListNumColumns)
                .unwrap_or(1)
                .clamp(1, 64) as usize
        };
        let row_height = number(node, PropKey::ListRowHeight)
            .unwrap_or(DEFAULT_CONTROL_HEIGHT)
            .max(1.0);
        let cell_width = if horizontal {
            row_height
        } else {
            inner.width / columns as f32
        };
        let visible_children = node_children
            .iter()
            .filter(|child| visible(child))
            .collect::<Vec<_>>();

        if horizontal {
            let mut cursor = inner.x;
            for child in visible_children {
                let item_width = number(child, PropKey::Width).unwrap_or(row_height).max(1.0);
                let item_frame = Layout {
                    x: cursor,
                    y: inner.y,
                    width: item_width,
                    height: inner.height,
                };
                layout_node(context, child.id, item_frame, false, depth + 1, output)?;
                cursor += item_width;
            }
        } else {
            let mut cursor = inner.y;
            for row in visible_children.chunks(columns) {
                let item_height = row
                    .iter()
                    .filter_map(|child| number(child, PropKey::Height))
                    .reduce(f32::max)
                    .unwrap_or(row_height)
                    .max(1.0);
                for (column, child) in row.iter().enumerate() {
                    let item_frame = Layout {
                        x: inner.x + column as f32 * cell_width,
                        y: cursor,
                        width: cell_width,
                        height: item_height,
                    };
                    layout_node(context, child.id, item_frame, false, depth + 1, output)?;
                }
                cursor += item_height;
            }
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
    let column_gap = finite_non_negative(number(node, PropKey::GridColumnGap).unwrap_or(gap))?;
    let row_gap = finite_non_negative(number(node, PropKey::GridRowGap).unwrap_or(gap))?;
    let (main_gap, cross_gap) = match axis {
        Axis::Horizontal => (column_gap, row_gap),
        Axis::Vertical => (row_gap, column_gap),
    };
    let mut flow_children = node_children
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
    let mut ordered_children = if matches!(direction, 3 | 4) {
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
    if integer(node, PropKey::FlexWrap).unwrap_or(1) == 2 {
        layout_wrapped_children(
            context,
            node,
            &ordered_children,
            inner,
            axis,
            available_main,
            available_cross,
            main_gap,
            cross_gap,
            depth,
            output,
        )?;
        flow_children.clear();
        ordered_children.clear();
    }
    let total_gap = main_gap * flow_children.len().saturating_sub(1) as f32;
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
                context.text_metrics,
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
    let flex_allocations = allocate_flex_main(&flow_children, axis, available_main, remaining)?;
    let flex_consumed = flex_allocations.values().sum::<f32>();
    let consumed = resolved_fixed + flex_margins + total_gap + flex_consumed;
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
        main_gap,
    );
    let parent_alignment = cross_alignment(integer(node, PropKey::AlignItems).unwrap_or(4));

    let has_baseline_alignment = parent_alignment == CrossAlignment::Baseline
        || flow_children.iter().any(|child| {
            integer(child, PropKey::AlignSelf).map(cross_alignment)
                == Some(CrossAlignment::Baseline)
        });
    let baseline_target = if axis == Axis::Horizontal && has_baseline_alignment {
        flow_children
            .iter()
            .map(|child| {
                let main = resolved_main[&child.id];
                let cross = child_cross(child, axis, available_cross, main)
                    .ok()
                    .flatten()
                    .or_else(|| {
                        intrinsic_cross(
                            context.children,
                            child,
                            axis,
                            available_main,
                            available_cross,
                            context.text_scale,
                            context.text_metrics,
                            depth + 1,
                        )
                        .ok()
                    })
                    .unwrap_or(0.0);
                baseline_from_top(child, cross, context.text_scale)
            })
            .fold(0.0_f32, f32::max)
    } else {
        0.0
    };

    for child in ordered_children {
        let flex = number(child, PropKey::FlexGrow).unwrap_or(0.0).max(0.0);
        let main = if flex > 0.0 && total_flex > 0.0 {
            flex_allocations.get(&child.id).copied().unwrap_or(0.0)
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
                    context.text_metrics,
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
            CrossAlignment::Baseline if axis == Axis::Horizontal => {
                baseline_target - baseline_from_top(child, cross, context.text_scale) + cross_before
            }
            CrossAlignment::Baseline => cross_before,
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
        let left = dimension(child, PropKey::Left, PropKey::LeftPercent, inner.width);
        let top = dimension(child, PropKey::Top, PropKey::TopPercent, inner.height);
        let right = dimension(child, PropKey::Right, PropKey::RightPercent, inner.width);
        let bottom = dimension(child, PropKey::Bottom, PropKey::BottomPercent, inner.height);
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
                    context.text_metrics,
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
                    context.text_metrics,
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
        let static_x =
            absolute_static_offset(child, node, axis, Axis::Horizontal, inner.width, width);
        let static_y =
            absolute_static_offset(child, node, axis, Axis::Vertical, inner.height, height);
        let child_frame = Layout {
            x: inner.x
                + left.unwrap_or_else(|| {
                    right.map_or(static_x, |right| (inner.width - right - width).max(0.0))
                }),
            y: inner.y
                + top.unwrap_or_else(|| {
                    bottom.map_or(static_y, |bottom| (inner.height - bottom - height).max(0.0))
                }),
            width,
            height,
        };
        layout_node(context, child.id, child_frame, false, depth + 1, output)?;
    }
    Ok(())
}

#[allow(clippy::too_many_arguments)]
fn layout_wrapped_children(
    context: &LayoutContext<'_>,
    node: &Node,
    children: &[&Node],
    inner: Layout,
    axis: Axis,
    available_main: f32,
    available_cross: f32,
    main_gap: f32,
    cross_gap: f32,
    depth: usize,
    output: &mut BTreeMap<u64, Layout>,
) -> Result<(), LayoutError> {
    if children.is_empty() {
        return Ok(());
    }

    let mut base_main = BTreeMap::new();
    let mut lines = Vec::<Vec<&Node>>::new();
    let mut line = Vec::<&Node>::new();
    let mut consumed = 0.0_f32;
    for child in children {
        let main = child_main(
            context.children,
            child,
            axis,
            available_main,
            available_cross,
            context.text_scale,
            context.text_metrics,
            depth + 1,
        )?;
        base_main.insert(child.id, main);
        let (before, after) = margin_main(child, axis);
        let outer = main + before + after;
        let candidate = if line.is_empty() {
            outer
        } else {
            consumed + main_gap + outer
        };
        if !line.is_empty() && candidate > available_main {
            lines.push(std::mem::take(&mut line));
            consumed = outer;
        } else {
            consumed = candidate;
        }
        line.push(child);
    }
    if !line.is_empty() {
        lines.push(line);
    }

    let parent_alignment = cross_alignment(integer(node, PropKey::AlignItems).unwrap_or(4));
    let justify = integer(node, PropKey::JustifyContent).unwrap_or(1);
    let mut cross_cursor = 0.0_f32;

    for line in lines {
        let fixed_with_margins = line
            .iter()
            .map(|child| {
                let (before, after) = margin_main(child, axis);
                base_main[&child.id] + before + after
            })
            .sum::<f32>();
        let line_gaps = main_gap * line.len().saturating_sub(1) as f32;
        let grow = line
            .iter()
            .map(|child| number(child, PropKey::FlexGrow).unwrap_or(0.0).max(0.0))
            .sum::<f32>();
        let grow_space = (available_main - fixed_with_margins - line_gaps).max(0.0);

        let mut resolved_cross = BTreeMap::new();
        let mut line_cross = 0.0_f32;
        for child in &line {
            let child_grow = number(child, PropKey::FlexGrow).unwrap_or(0.0).max(0.0);
            let main = base_main[&child.id]
                + if grow > 0.0 {
                    grow_space * child_grow / grow
                } else {
                    0.0
                };
            let explicit = child_cross(child, axis, available_cross, main)?;
            let intrinsic = intrinsic_cross(
                context.children,
                child,
                axis,
                available_main,
                available_cross,
                context.text_scale,
                context.text_metrics,
                depth + 1,
            )?;
            let cross = explicit.unwrap_or(intrinsic);
            let (before, after) = margin_cross(child, axis);
            line_cross = line_cross.max(cross + before + after);
            resolved_cross.insert(child.id, (main, explicit, intrinsic));
        }
        line_cross = line_cross.min((available_cross - cross_cursor).max(0.0));

        let consumed_main =
            fixed_with_margins + line_gaps + if grow > 0.0 { grow_space } else { 0.0 };
        let free = (available_main - consumed_main).max(0.0);
        let (mut main_cursor, distributed_gap) =
            justify_offsets(justify, free, line.len(), main_gap);

        let has_line_baseline = parent_alignment == CrossAlignment::Baseline
            || line.iter().any(|child| {
                integer(child, PropKey::AlignSelf).map(cross_alignment)
                    == Some(CrossAlignment::Baseline)
            });
        let line_baseline = if axis == Axis::Horizontal && has_line_baseline {
            line.iter()
                .map(|child| {
                    let (_, explicit, intrinsic) = resolved_cross[&child.id];
                    baseline_from_top(child, explicit.unwrap_or(intrinsic), context.text_scale)
                })
                .fold(0.0_f32, f32::max)
        } else {
            0.0
        };

        for child in line {
            let (main, explicit_cross, intrinsic_cross) = resolved_cross[&child.id];
            let (main_before, main_after) = margin_main(child, axis);
            let (cross_before, cross_after) = margin_cross(child, axis);
            let alignment = integer(child, PropKey::AlignSelf)
                .map(cross_alignment)
                .unwrap_or(parent_alignment);
            let cross = explicit_cross.unwrap_or_else(|| {
                if alignment == CrossAlignment::Stretch {
                    (line_cross - cross_before - cross_after).max(0.0)
                } else {
                    intrinsic_cross.min((line_cross - cross_before - cross_after).max(0.0))
                }
            });
            let cross_offset = match alignment {
                CrossAlignment::Start | CrossAlignment::Stretch => cross_before,
                CrossAlignment::Center => (line_cross - cross + cross_before - cross_after) / 2.0,
                CrossAlignment::End => line_cross - cross - cross_after,
                CrossAlignment::Baseline if axis == Axis::Horizontal => {
                    line_baseline - baseline_from_top(child, cross, context.text_scale)
                        + cross_before
                }
                CrossAlignment::Baseline => cross_before,
            }
            .max(0.0);
            main_cursor += main_before;
            let child_frame = match axis {
                Axis::Vertical => Layout {
                    x: inner.x + cross_cursor + cross_offset,
                    y: inner.y + main_cursor,
                    width: cross,
                    height: main,
                },
                Axis::Horizontal => Layout {
                    x: inner.x + main_cursor,
                    y: inner.y + cross_cursor + cross_offset,
                    width: main,
                    height: cross,
                },
            };
            layout_node(context, child.id, child_frame, false, depth + 1, output)?;
            main_cursor += main + main_after + distributed_gap;
        }
        cross_cursor += line_cross + cross_gap;
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
            context.text_metrics,
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
        let left =
            dimension(child, PropKey::Left, PropKey::LeftPercent, inner.width).unwrap_or(0.0);
        let top = dimension(child, PropKey::Top, PropKey::TopPercent, inner.height).unwrap_or(0.0);
        let width = dimension(child, PropKey::Width, PropKey::WidthPercent, inner.width)
            .unwrap_or(inner.width);
        let height = child_main(
            context.children,
            child,
            Axis::Vertical,
            inner.height,
            width,
            context.text_scale,
            context.text_metrics,
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

#[allow(clippy::too_many_arguments)]
fn natural_scroll_extent(
    children: &BTreeMap<u64, Vec<&Node>>,
    node: &Node,
    axis: Axis,
    available_main: f32,
    available_cross: f32,
    text_scale: f32,
    text_metrics: &TextMetrics,
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
            text_metrics,
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
            text_metrics,
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
            text_metrics,
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

#[allow(clippy::too_many_arguments)]
fn intrinsic_extent(
    children: &BTreeMap<u64, Vec<&Node>>,
    node: &Node,
    requested_axis: Axis,
    available_width: f32,
    available_height: f32,
    text_scale: f32,
    text_metrics: &TextMetrics,
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
        let text = text_extent(
            node,
            requested_axis,
            inner_width,
            text_scale,
            text_metrics.get(&node.id).map(AsRef::as_ref),
        );
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
        return finite_non_negative(leaf_intrinsic(
            node,
            requested_axis,
            text_scale,
            text_metrics.get(&node.id).map(AsRef::as_ref),
        ));
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
                text_metrics,
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
    let gap = finite_non_negative(number(node, PropKey::Gap).unwrap_or(0.0))?;
    let column_gap = finite_non_negative(number(node, PropKey::GridColumnGap).unwrap_or(gap))?;
    let row_gap = finite_non_negative(number(node, PropKey::GridRowGap).unwrap_or(gap))?;
    let (main_gap, cross_gap) = match flow_axis {
        Axis::Horizontal => (column_gap, row_gap),
        Axis::Vertical => (row_gap, column_gap),
    };
    if integer(node, PropKey::FlexWrap).unwrap_or(1) == 2 && requested_axis != flow_axis {
        let available_main = match flow_axis {
            Axis::Horizontal => inner_width,
            Axis::Vertical => inner_height,
        };
        let content = wrapped_intrinsic_cross(
            children,
            &node_children,
            node,
            flow_axis,
            available_main,
            inner_width,
            inner_height,
            main_gap,
            cross_gap,
            text_scale,
            text_metrics,
            depth + 1,
        )?;
        let padding_extent = match requested_axis {
            Axis::Vertical => padding_top + padding_bottom,
            Axis::Horizontal => padding_left + padding_right,
        };
        return finite_non_negative(content + padding_extent);
    }
    let mut child_extents = Vec::with_capacity(node_children.len());
    for child in &node_children {
        let child_extent = constrained_intrinsic_extent(
            children,
            child,
            requested_axis,
            inner_width,
            inner_height,
            text_scale,
            text_metrics,
            depth + 1,
        )?;
        let (before, after) = if requested_axis == flow_axis {
            margin_main(child, flow_axis)
        } else {
            margin_cross(child, flow_axis)
        };
        child_extents.push((child_extent, before, after));
    }
    let content = if requested_axis == flow_axis {
        child_extents
            .iter()
            .map(|(extent, before, after)| extent + before + after)
            .sum::<f32>()
            + main_gap * child_extents.len().saturating_sub(1) as f32
    } else if flow_axis == Axis::Horizontal
        && (cross_alignment(integer(node, PropKey::AlignItems).unwrap_or(4))
            == CrossAlignment::Baseline
            || node_children.iter().any(|child| {
                integer(child, PropKey::AlignSelf).map(cross_alignment)
                    == Some(CrossAlignment::Baseline)
            }))
    {
        let (ascent, descent) = node_children.iter().zip(&child_extents).fold(
            (0.0_f32, 0.0_f32),
            |(ascent, descent), (child, (extent, before, after))| {
                let baseline = baseline_from_top(child, *extent, text_scale);
                (
                    ascent.max(before + baseline),
                    descent.max(after + extent - baseline),
                )
            },
        );
        ascent + descent
    } else {
        child_extents
            .into_iter()
            .map(|(extent, before, after)| extent + before + after)
            .fold(0.0, f32::max)
    };
    let padding_extent = match requested_axis {
        Axis::Vertical => padding_top + padding_bottom,
        Axis::Horizontal => padding_left + padding_right,
    };

    finite_non_negative(content + padding_extent)
}

#[allow(clippy::too_many_arguments)]
fn wrapped_intrinsic_cross(
    children_index: &BTreeMap<u64, Vec<&Node>>,
    children: &[&Node],
    parent: &Node,
    flow_axis: Axis,
    available_main: f32,
    available_width: f32,
    available_height: f32,
    main_gap: f32,
    cross_gap: f32,
    text_scale: f32,
    text_metrics: &TextMetrics,
    depth: usize,
) -> Result<f32, LayoutError> {
    let cross_axis = match flow_axis {
        Axis::Horizontal => Axis::Vertical,
        Axis::Vertical => Axis::Horizontal,
    };
    let mut total_cross = 0.0_f32;
    let mut line_main = 0.0_f32;
    let mut line_cross = 0.0_f32;
    let mut line_ascent = 0.0_f32;
    let mut line_descent = 0.0_f32;
    let mut line_count = 0_usize;
    let mut line_total = 0_usize;

    let parent_alignment = cross_alignment(integer(parent, PropKey::AlignItems).unwrap_or(4));
    for child in children {
        let main = constrained_intrinsic_extent(
            children_index,
            child,
            flow_axis,
            available_width,
            available_height,
            text_scale,
            text_metrics,
            depth + 1,
        )?;
        let cross = constrained_intrinsic_extent(
            children_index,
            child,
            cross_axis,
            available_width,
            available_height,
            text_scale,
            text_metrics,
            depth + 1,
        )?;
        let (main_before, main_after) = margin_main(child, flow_axis);
        let (cross_before, cross_after) = margin_cross(child, flow_axis);
        let baseline_aligned = flow_axis == Axis::Horizontal
            && integer(child, PropKey::AlignSelf)
                .map(cross_alignment)
                .unwrap_or(parent_alignment)
                == CrossAlignment::Baseline;
        let baseline = baseline_from_top(child, cross, text_scale);
        let outer_main = main + main_before + main_after;
        let candidate = if line_count == 0 {
            outer_main
        } else {
            line_main + main_gap + outer_main
        };
        if line_count > 0 && candidate > available_main {
            total_cross += if line_total == 0 { 0.0 } else { cross_gap } + line_cross;
            line_total += 1;
            line_main = outer_main;
            line_cross = cross + cross_before + cross_after;
            line_ascent = if baseline_aligned {
                cross_before + baseline
            } else {
                0.0
            };
            line_descent = if baseline_aligned {
                cross_after + cross - baseline
            } else {
                0.0
            };
            line_count = 1;
        } else {
            line_main = candidate;
            line_cross = line_cross.max(cross + cross_before + cross_after);
            if baseline_aligned {
                line_ascent = line_ascent.max(cross_before + baseline);
                line_descent = line_descent.max(cross_after + cross - baseline);
                line_cross = line_cross.max(line_ascent + line_descent);
            }
            line_count += 1;
        }
    }
    if line_count > 0 {
        total_cross += if line_total == 0 { 0.0 } else { cross_gap } + line_cross;
    }

    finite_non_negative(total_cross)
}

#[allow(clippy::too_many_arguments)]
fn grid_intrinsic_height(
    children_index: &BTreeMap<u64, Vec<&Node>>,
    node: &Node,
    node_children: &[&Node],
    width: f32,
    available_height: f32,
    text_scale: f32,
    text_metrics: &TextMetrics,
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
            text_metrics,
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

#[allow(clippy::too_many_arguments)]
fn constrained_intrinsic_extent(
    children: &BTreeMap<u64, Vec<&Node>>,
    node: &Node,
    axis: Axis,
    available_width: f32,
    available_height: f32,
    text_scale: f32,
    text_metrics: &TextMetrics,
    depth: usize,
) -> Result<f32, LayoutError> {
    let extent = intrinsic_extent(
        children,
        node,
        axis,
        available_width,
        available_height,
        text_scale,
        text_metrics,
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
        | NodeKind::NavigationHost
        | NodeKind::TabHost => true,
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

fn leaf_intrinsic(
    node: &Node,
    axis: Axis,
    text_scale: f32,
    glyph_advances: Option<&BTreeMap<char, f32>>,
) -> f32 {
    match (axis, node.kind) {
        (Axis::Horizontal, NodeKind::Text) => {
            text_extent(node, axis, f32::INFINITY, text_scale, glyph_advances)
        }
        (Axis::Horizontal, NodeKind::Switch) => DEFAULT_SWITCH_WIDTH,
        (Axis::Horizontal, NodeKind::ActivityIndicator) => DEFAULT_CONTROL_HEIGHT,
        (Axis::Horizontal, NodeKind::Image | NodeKind::ImageBackground) => DEFAULT_IMAGE_HEIGHT,
        (Axis::Horizontal, NodeKind::Spacer) => 8.0,
        (Axis::Horizontal, NodeKind::StatusBar) => 0.0,
        (Axis::Horizontal, _) => 100.0,
        (
            Axis::Vertical,
            NodeKind::Button | NodeKind::Input | NodeKind::Pressable | NodeKind::ActivityIndicator,
        ) => DEFAULT_CONTROL_HEIGHT,
        (Axis::Vertical, NodeKind::Switch) => DEFAULT_SWITCH_HEIGHT,
        (Axis::Vertical, NodeKind::Image | NodeKind::ImageBackground) => DEFAULT_IMAGE_HEIGHT,
        (
            Axis::Vertical,
            NodeKind::List
            | NodeKind::SectionList
            | NodeKind::VirtualList
            | NodeKind::Scroll
            | NodeKind::RefreshControl,
        ) => DEFAULT_LIST_HEIGHT,
        (Axis::Vertical, NodeKind::Spacer) => 8.0,
        (Axis::Vertical, NodeKind::StatusBar) => 0.0,
        (Axis::Vertical, _) => DEFAULT_CONTROL_HEIGHT,
    }
}

fn text_extent(
    node: &Node,
    axis: Axis,
    available_width: f32,
    device_text_scale: f32,
    glyph_advances: Option<&BTreeMap<char, f32>>,
) -> f32 {
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
    // PAM authors letter spacing in logical points. Android receives the
    // equivalent `em` value, so the rendered glyph gap scales once with the
    // configured accessibility text scale, not again with the font size.
    let letter_spacing = number(node, PropKey::LetterSpacing).unwrap_or(0.0) * text_scale;
    let source_text = match node.properties.get(&PropKey::Text) {
        Some(pam_native_protocol::PropValue::String(value)) => value.as_str(),
        _ => "",
    };
    let text = transformed_text_for_measurement(
        source_text,
        integer(node, PropKey::TextTransform).unwrap_or(1),
    );
    let lines = wrapped_text_lines_with_metrics(
        &text,
        font_size,
        letter_spacing,
        available_width,
        glyph_advances,
    );
    match axis {
        Axis::Vertical => {
            let maximum_lines = integer(node, PropKey::NumberOfLines)
                .filter(|value| *value > 0)
                .map_or(lines.len(), |value| value as usize);
            line_height * lines.len().min(maximum_lines).max(1) as f32
        }
        Axis::Horizontal => lines
            .iter()
            .map(|line| measured_text_width(line, font_size, letter_spacing, glyph_advances))
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

#[cfg(test)]
fn wrapped_text_lines(
    text: &str,
    font_size: f32,
    letter_spacing: f32,
    available_width: f32,
) -> Vec<&str> {
    wrapped_text_lines_with_metrics(text, font_size, letter_spacing, available_width, None)
}

fn wrapped_text_lines_with_metrics<'a>(
    text: &'a str,
    font_size: f32,
    letter_spacing: f32,
    available_width: f32,
    glyph_advances: Option<&BTreeMap<char, f32>>,
) -> Vec<&'a str> {
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
            let advance =
                measured_character_width(character, font_size, glyph_advances) + letter_spacing;
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
                width = measured_text_width(
                    &hard_line[start..offset],
                    font_size,
                    letter_spacing,
                    glyph_advances,
                );
            }
            width += advance;
        }
        result.push(hard_line[start..].trim_end());
    }
    result
}

#[cfg(test)]
fn estimated_text_width(text: &str, font_size: f32, letter_spacing: f32) -> f32 {
    measured_text_width(text, font_size, letter_spacing, None)
}

fn measured_text_width(
    text: &str,
    font_size: f32,
    letter_spacing: f32,
    glyph_advances: Option<&BTreeMap<char, f32>>,
) -> f32 {
    let mut characters = text.chars().peekable();
    let mut width = 0.0;
    while let Some(character) = characters.next() {
        width += measured_character_width(character, font_size, glyph_advances);
        if characters.peek().is_some() {
            width += letter_spacing;
        }
    }
    if glyph_advances.is_some() {
        // TTF advances describe the ideal unhinted run. Android's TextView can
        // shape and hint the same run slightly wider when it builds its
        // StaticLayout. A fixed half-pixel guard was insufficient for longer
        // multi-word labels: the last word wrapped while numberOfLines kept
        // only the first line, clipping it completely. Keep a small relative
        // platform guard plus the sub-pixel rounding allowance.
        return if width > 0.0 { width * 1.02 + 0.5 } else { 0.0 };
    }
    // Bounding-box based em classes need a small conversion to platform glyph
    // advances. The old broad classes plus a 6% safety margin substantially
    // over-allocated short labels, so centered icon/label rows looked shifted
    // even though their frames were centered. These classes track Android's
    // modern sans metrics closely; keep only a sub-pixel wrapping tolerance.
    if width > 0.0 { width * 1.09 + 0.5 } else { 0.0 }
}

fn measured_character_width(
    character: char,
    font_size: f32,
    glyph_advances: Option<&BTreeMap<char, f32>>,
) -> f32 {
    glyph_advances
        .and_then(|advances| advances.get(&character))
        .filter(|advance| **advance > 0.0)
        .map_or_else(
            || estimated_character_width(character, font_size),
            |advance| *advance * font_size,
        )
}

fn estimated_character_width(character: char, font_size: f32) -> f32 {
    let em = match character {
        ' ' | '\t' => 0.25,
        'i' | '!' | '.' | ',' | ':' | ';' | '\'' => 0.23,
        'l' | 'I' | '|' => 0.21,
        'r' => 0.32,
        'f' | 't' => 0.42,
        'm' => 0.78,
        'w' => 0.74,
        'M' => 0.82,
        'W' => 0.87,
        '@' => 0.95,
        '%' => 0.74,
        '&' => 0.56,
        character if character.is_ascii_uppercase() => 0.59,
        character if character.is_ascii_digit() => 0.56,
        character if character.is_ascii() => 0.54,
        _ => 1.0,
    };
    font_size * em
}

#[allow(clippy::too_many_arguments)]
fn intrinsic_cross(
    children: &BTreeMap<u64, Vec<&Node>>,
    node: &Node,
    parent_axis: Axis,
    available_main: f32,
    available_cross: f32,
    text_scale: f32,
    text_metrics: &TextMetrics,
    depth: usize,
) -> Result<f32, LayoutError> {
    let (requested_axis, available_width, available_height) = match parent_axis {
        Axis::Vertical => (Axis::Horizontal, available_cross, available_main),
        Axis::Horizontal => (Axis::Vertical, available_main, available_cross),
    };
    constrained_intrinsic_extent(
        children,
        node,
        requested_axis,
        available_width,
        available_height,
        text_scale,
        text_metrics,
        depth,
    )
}

fn allocate_flex_main(
    children: &[&Node],
    axis: Axis,
    available_main: f32,
    remaining: f32,
) -> Result<BTreeMap<u64, f32>, LayoutError> {
    let mut unresolved = children
        .iter()
        .copied()
        .filter(|child| number(child, PropKey::FlexGrow).unwrap_or(0.0) > 0.0)
        .collect::<Vec<_>>();
    let bounds = unresolved
        .iter()
        .map(|child| Ok((child.id, flex_main_bounds(child, axis, available_main)?)))
        .collect::<Result<BTreeMap<_, _>, LayoutError>>()?;
    let mut allocations = BTreeMap::new();
    let mut pool = remaining.max(0.0);

    while !unresolved.is_empty() {
        let minimum_total = unresolved
            .iter()
            .map(|child| bounds[&child.id].0)
            .sum::<f32>();
        if minimum_total > pool {
            for child in unresolved.drain(..) {
                allocations.insert(child.id, bounds[&child.id].0);
            }
            break;
        }
        let total_weight = unresolved
            .iter()
            .map(|child| number(child, PropKey::FlexGrow).unwrap_or(0.0).max(0.0))
            .sum::<f32>();
        if total_weight <= 0.0 {
            break;
        }
        let mut frozen = Vec::new();
        for child in &unresolved {
            let weight = number(child, PropKey::FlexGrow).unwrap_or(0.0).max(0.0);
            let candidate = pool * weight / total_weight;
            let (minimum, maximum) = bounds[&child.id];
            let constrained = candidate.max(minimum).min(maximum);
            if (constrained - candidate).abs() > f32::EPSILON {
                frozen.push((child.id, constrained));
            }
        }
        if frozen.is_empty() {
            for child in unresolved.drain(..) {
                let weight = number(child, PropKey::FlexGrow).unwrap_or(0.0).max(0.0);
                allocations.insert(child.id, pool * weight / total_weight);
            }
            break;
        }
        for (id, value) in frozen {
            allocations.insert(id, value);
            pool = (pool - value).max(0.0);
            unresolved.retain(|child| child.id != id);
        }
    }

    Ok(allocations)
}

fn flex_main_bounds(
    node: &Node,
    axis: Axis,
    available_main: f32,
) -> Result<(f32, f32), LayoutError> {
    let (raw_minimum, raw_maximum) = match axis {
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
    let minimum = raw_minimum
        .map(finite_non_negative)
        .transpose()?
        .unwrap_or(0.0);
    let maximum = raw_maximum
        .map(finite_non_negative)
        .transpose()?
        .unwrap_or(f32::INFINITY);

    Ok((minimum, maximum.max(minimum)))
}

#[allow(clippy::too_many_arguments)]
fn child_main(
    children: &BTreeMap<u64, Vec<&Node>>,
    node: &Node,
    axis: Axis,
    available_main: f32,
    available_cross: f32,
    text_scale: f32,
    text_metrics: &TextMetrics,
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
                text_metrics,
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
        5 => CrossAlignment::Baseline,
        _ => CrossAlignment::Stretch,
    }
}

fn baseline_from_top(node: &Node, height: f32, text_scale: f32) -> f32 {
    if matches!(
        node.kind,
        NodeKind::Text | NodeKind::Input | NodeKind::Button
    ) {
        let allow_scaling = !matches!(
            node.properties.get(&PropKey::TextAllowFontScaling),
            Some(pam_native_protocol::PropValue::Boolean(false))
        );
        let scale = if allow_scaling { text_scale } else { 1.0 };
        let font_size = number(node, PropKey::FontSize).unwrap_or(14.0).max(1.0) * scale;
        let base_font_size = number(node, PropKey::FontSize).unwrap_or(14.0).max(1.0);
        let line_height = number(node, PropKey::LineHeight)
            .unwrap_or((base_font_size * 1.4).max(DEFAULT_TEXT_HEIGHT / 2.0))
            * scale;
        let content_baseline = ((line_height - font_size).max(0.0) / 2.0) + font_size * 0.8;
        return content_baseline.min(height.max(0.0));
    }
    height.max(0.0)
}

fn absolute_static_offset(
    child: &Node,
    parent: &Node,
    parent_axis: Axis,
    target_axis: Axis,
    available: f32,
    child_size: f32,
) -> f32 {
    let free = (available - child_size).max(0.0);
    if parent_axis == target_axis {
        return justify_offsets(
            integer(parent, PropKey::JustifyContent).unwrap_or(1),
            free,
            1,
            0.0,
        )
        .0;
    }

    let alignment = integer(child, PropKey::AlignSelf)
        .map(cross_alignment)
        .unwrap_or_else(|| cross_alignment(integer(parent, PropKey::AlignItems).unwrap_or(4)));
    match alignment {
        CrossAlignment::Center => free / 2.0,
        CrossAlignment::End => free,
        CrossAlignment::Start | CrossAlignment::Stretch | CrossAlignment::Baseline => 0.0,
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
    fn row_baseline_aligns_text_with_different_font_sizes() {
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
                            (PropKey::FlexDirection, PropValue::Integer(2)),
                            (PropKey::AlignItems, PropValue::Integer(5)),
                        ],
                    ),
                ),
                (
                    2,
                    node(
                        2,
                        1,
                        0,
                        NodeKind::Text,
                        [
                            (PropKey::Text, PropValue::String("small".to_owned())),
                            (PropKey::FontSize, PropValue::Float(10.0)),
                            (PropKey::LineHeight, PropValue::Float(14.0)),
                        ],
                    ),
                ),
                (
                    3,
                    node(
                        3,
                        1,
                        1,
                        NodeKind::Text,
                        [
                            (PropKey::Text, PropValue::String("large".to_owned())),
                            (PropKey::FontSize, PropValue::Float(20.0)),
                            (PropKey::LineHeight, PropValue::Float(28.0)),
                        ],
                    ),
                ),
            ]),
        };

        let layouts = calculate(
            &tree,
            Size {
                width: 240.0,
                height: 80.0,
            },
        )
        .expect("baseline layout");
        let small_baseline =
            layouts[&2].y + baseline_from_top(&tree.nodes[&2], layouts[&2].height, 1.0);
        let large_baseline =
            layouts[&3].y + baseline_from_top(&tree.nodes[&3], layouts[&3].height, 1.0);

        assert!((small_baseline - large_baseline).abs() < 0.01);
        assert!(layouts[&2].y > layouts[&3].y);
    }

    #[test]
    fn resolves_percentage_position_offsets_against_the_inner_containing_block() {
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
                        [(PropKey::Padding, PropValue::Float(10.0))],
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
                            (PropKey::LeftPercent, PropValue::Float(10.0)),
                            (PropKey::TopPercent, PropValue::Float(20.0)),
                            (PropKey::Width, PropValue::Float(20.0)),
                            (PropKey::Height, PropValue::Float(20.0)),
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
        .expect("percentage offsets");
        let child = layouts[&2];

        assert_eq!(child.x, 38.0);
        assert_eq!(child.y, 86.0);
    }

    #[test]
    fn absolute_child_without_horizontal_insets_uses_flex_static_alignment() {
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
                        [(PropKey::AlignItems, PropValue::Integer(2))],
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
                            (PropKey::Bottom, PropValue::Float(0.0)),
                            (PropKey::Width, PropValue::Float(26.0)),
                            (PropKey::Height, PropValue::Float(3.0)),
                        ],
                    ),
                ),
            ]),
        };

        let layouts = calculate(
            &tree,
            Size {
                width: 360.0,
                height: 48.0,
            },
        )
        .expect("absolute static alignment");
        let child = layouts[&2];

        assert_eq!(child.x, 167.0);
        assert_eq!(child.y, 45.0);
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
    fn flex_wrap_breaks_rows_and_reports_intrinsic_cross_extent() {
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
                        [(PropKey::AlignItems, PropValue::Integer(1))],
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
                            (PropKey::Width, PropValue::Float(100.0)),
                            (PropKey::FlexWrap, PropValue::Integer(2)),
                            (PropKey::GridColumnGap, PropValue::Float(8.0)),
                            (PropKey::GridRowGap, PropValue::Float(6.0)),
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
                        [
                            (PropKey::Width, PropValue::Float(44.0)),
                            (PropKey::Height, PropValue::Float(20.0)),
                        ],
                    ),
                ),
                (
                    4,
                    node(
                        4,
                        2,
                        1,
                        NodeKind::View,
                        [
                            (PropKey::Width, PropValue::Float(44.0)),
                            (PropKey::Height, PropValue::Float(20.0)),
                        ],
                    ),
                ),
                (
                    5,
                    node(
                        5,
                        2,
                        2,
                        NodeKind::View,
                        [
                            (PropKey::Width, PropValue::Float(44.0)),
                            (PropKey::Height, PropValue::Float(20.0)),
                        ],
                    ),
                ),
            ]),
        };

        let layouts = calculate(
            &tree,
            Size {
                width: 100.0,
                height: 100.0,
            },
        )
        .expect("wrapped layout");

        assert_eq!(layouts[&2].height, 46.0);
        assert_eq!((layouts[&3].x, layouts[&3].y), (0.0, 0.0));
        assert_eq!((layouts[&4].x, layouts[&4].y), (52.0, 0.0));
        assert_eq!((layouts[&5].x, layouts[&5].y), (0.0, 26.0));
    }

    #[test]
    fn flex_wrap_respects_intrinsic_cross_axis_minimums() {
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
                        [(PropKey::AlignItems, PropValue::Integer(1))],
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
                            (PropKey::Width, PropValue::Float(100.0)),
                            (PropKey::FlexWrap, PropValue::Integer(2)),
                            (PropKey::GridRowGap, PropValue::Float(6.0)),
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
                        [
                            (PropKey::Width, PropValue::Float(44.0)),
                            (PropKey::MinHeight, PropValue::Float(52.0)),
                        ],
                    ),
                ),
                (
                    4,
                    node(
                        4,
                        2,
                        1,
                        NodeKind::View,
                        [
                            (PropKey::Width, PropValue::Float(60.0)),
                            (PropKey::MinHeight, PropValue::Float(52.0)),
                        ],
                    ),
                ),
            ]),
        };

        let layouts = calculate(
            &tree,
            Size {
                width: 100.0,
                height: 140.0,
            },
        )
        .expect("wrapped minimum layout");

        assert_eq!(layouts[&2].height, 110.0);
        assert_eq!(layouts[&3].height, 52.0);
        assert_eq!(layouts[&4].height, 52.0);
        assert_eq!(layouts[&4].y, 58.0);
    }

    #[test]
    fn flex_growth_redistributes_space_after_minimum_and_maximum_constraints() {
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
                            (PropKey::FlexGrow, PropValue::Float(1.0)),
                            (PropKey::MinWidth, PropValue::Float(80.0)),
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
                        [(PropKey::FlexGrow, PropValue::Float(1.0))],
                    ),
                ),
                (
                    4,
                    node(
                        4,
                        1,
                        2,
                        NodeKind::View,
                        [
                            (PropKey::FlexGrow, PropValue::Float(1.0)),
                            (PropKey::MaxWidth, PropValue::Float(40.0)),
                        ],
                    ),
                ),
            ]),
        };

        let layouts = calculate(
            &tree,
            Size {
                width: 180.0,
                height: 40.0,
            },
        )
        .expect("layout");

        assert_eq!(layouts[&2].width, 80.0);
        assert_eq!(layouts[&3].width, 60.0);
        assert_eq!(layouts[&4].width, 40.0);
        assert_eq!(layouts[&3].x, 80.0);
        assert_eq!(layouts[&4].x, 140.0);
    }

    #[test]
    fn flex_constraints_reject_non_finite_dimensions() {
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
                            (PropKey::FlexGrow, PropValue::Float(1.0)),
                            (PropKey::MinWidth, PropValue::Float(f64::NAN)),
                        ],
                    ),
                ),
            ]),
        };

        assert!(matches!(
            calculate(
                &tree,
                Size {
                    width: 180.0,
                    height: 40.0,
                },
            ),
            Err(LayoutError::InvalidDimension),
        ));
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
    fn dialog_modal_centers_percent_width_card_at_intrinsic_height() {
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
                        NodeKind::Modal,
                        [(PropKey::ModalPresentation, PropValue::Integer(2))],
                    ),
                ),
                (
                    3,
                    node(
                        3,
                        2,
                        0,
                        NodeKind::Column,
                        [
                            (PropKey::WidthPercent, PropValue::Float(88.0)),
                            (PropKey::Padding, PropValue::Float(20.0)),
                            (PropKey::Gap, PropValue::Float(8.0)),
                        ],
                    ),
                ),
                (
                    4,
                    node(
                        4,
                        3,
                        0,
                        NodeKind::Text,
                        [(PropKey::Height, PropValue::Float(40.0))],
                    ),
                ),
                (
                    5,
                    node(
                        5,
                        3,
                        1,
                        NodeKind::Text,
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
        .expect("dialog intrinsic layout");
        let card = layouts[&3];

        assert!((card.width - 316.8).abs() < 0.001);
        assert!((card.x - 21.6).abs() < 0.001);
        assert_eq!(card.height, 108.0);
        assert_eq!(card.y, 266.0);
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

        assert_eq!(text_extent(&text, Axis::Vertical, 70.0, 1.0, None), 60.0);
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

        assert_eq!(
            text_extent(&scalable, Axis::Vertical, 200.0, 1.5, None),
            42.0,
        );
        assert_eq!(text_extent(&fixed, Axis::Vertical, 200.0, 1.5, None), 28.0);
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
            text_extent(&uppercase, Axis::Horizontal, 400.0, 1.0, None)
                > text_extent(&plain, Axis::Horizontal, 400.0, 1.0, None),
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
    fn intrinsic_text_width_tracks_platform_advances_with_subpixel_tolerance() {
        let font_size = 14.0;
        let raw_glyph_width = "Paid"
            .chars()
            .map(|character| estimated_character_width(character, font_size))
            .sum::<f32>();

        assert!(estimated_text_width("Paid", font_size, 0.0) > raw_glyph_width);
        assert_eq!(
            estimated_text_width("Paid", font_size, 0.0) - raw_glyph_width * 1.09,
            0.5,
        );
    }

    #[test]
    fn loaded_font_advances_replace_the_generic_width_estimator() {
        let advances = BTreeMap::from([
            ('S', 0.61),
            ('e', 0.54),
            ('g', 0.56),
            ('u', 0.56),
            ('i', 0.25),
            ('d', 0.57),
            ('o', 0.56),
            ('r', 0.36),
            ('s', 0.49),
        ]);
        let font_size = 15.0;
        let expected = "Seguidores"
            .chars()
            .map(|character| advances[&character] * font_size)
            .sum::<f32>();

        assert_eq!(
            measured_text_width("Seguidores", font_size, 0.0, Some(&advances)),
            expected * 1.02 + 0.5,
        );
    }

    #[test]
    fn loaded_font_intrinsic_width_keeps_platform_subpixel_guard() {
        let advances = BTreeMap::from([
            (' ', 0.25),
            ('?', 0.54),
            ('Q', 0.61),
            ('a', 0.54),
            ('c', 0.49),
            ('e', 0.54),
            ('m', 0.78),
            ('o', 0.56),
            ('p', 0.56),
            ('r', 0.32),
            ('u', 0.56),
        ]);
        let label = "Quer comprar ?";
        let raw = label
            .chars()
            .map(|character| advances[&character] * 16.0)
            .sum::<f32>();
        let intrinsic = measured_text_width(label, 16.0, 0.0, Some(&advances));

        assert!((intrinsic - (raw * 1.02 + 0.5)).abs() < 0.01);
        assert_eq!(
            wrapped_text_lines_with_metrics(label, 16.0, 0.0, intrinsic, Some(&advances)),
            vec![label],
        );
    }

    #[test]
    fn icon_and_packaged_font_label_stay_centered_as_one_group() {
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
                            (PropKey::Height, PropValue::Float(56.0)),
                            (PropKey::Gap, PropValue::Float(8.0)),
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
                        NodeKind::Image,
                        [
                            (PropKey::Width, PropValue::Float(18.0)),
                            (PropKey::Height, PropValue::Float(18.0)),
                        ],
                    ),
                ),
                (
                    3,
                    node(
                        3,
                        1,
                        1,
                        NodeKind::Text,
                        [
                            (PropKey::Text, PropValue::String("Zé Chat".into())),
                            (PropKey::FontSize, PropValue::Float(18.0)),
                            (PropKey::LineHeight, PropValue::Float(24.0)),
                        ],
                    ),
                ),
            ]),
        };
        let metrics = TextMetrics::from([(
            3,
            std::sync::Arc::new(BTreeMap::from([
                ('Z', 0.62),
                ('é', 0.54),
                (' ', 0.25),
                ('C', 0.64),
                ('h', 0.56),
                ('a', 0.53),
                ('t', 0.34),
            ])),
        )]);

        let layouts = calculate_with_text_metrics(
            &tree,
            Size {
                width: 320.0,
                height: 56.0,
            },
            1.1,
            &metrics,
        )
        .expect("centered icon and text layout");
        let icon = layouts[&2];
        let label = layouts[&3];

        assert!((label.x - (icon.x + icon.width) - 8.0).abs() < f32::EPSILON);
        assert!(((icon.x + label.x + label.width) / 2.0 - 160.0).abs() < 0.001);
        assert!(((icon.y + icon.height / 2.0) - 28.0).abs() < 0.001);
        assert!(((label.y + label.height / 2.0) - 28.0).abs() < 0.001);
    }

    #[test]
    fn logical_letter_spacing_is_not_multiplied_by_font_size() {
        let compact = node(
            1,
            0,
            0,
            NodeKind::Text,
            [
                (PropKey::Text, PropValue::String("ABC".into())),
                (PropKey::FontSize, PropValue::Float(20.0)),
                (PropKey::LetterSpacing, PropValue::Float(0.6)),
            ],
        );
        let without_spacing = estimated_text_width("ABC", 20.0, 0.0);
        let with_spacing = text_extent(&compact, Axis::Horizontal, 320.0, 1.0, None);

        assert!((with_spacing - without_spacing - 1.308).abs() < 0.01);
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
                &TextMetrics::new(),
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
                &TextMetrics::new(),
                0,
            )
            .expect("padded text width")
                > 48.0,
        );
    }

    #[test]
    fn intrinsic_switch_matches_react_native_geometry() {
        let switch = node(1, 0, 0, NodeKind::Switch, []);

        assert_eq!(
            leaf_intrinsic(&switch, Axis::Horizontal, 1.0, None),
            DEFAULT_SWITCH_WIDTH,
        );
        assert_eq!(
            leaf_intrinsic(&switch, Axis::Vertical, 1.0, None),
            DEFAULT_SWITCH_HEIGHT,
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

    #[test]
    fn virtual_grid_cells_receive_real_column_bounds() {
        let mut nodes = BTreeMap::new();
        nodes.insert(
            1,
            node(
                1,
                0,
                0,
                NodeKind::VirtualList,
                [
                    (PropKey::ListNumColumns, PropValue::Integer(2)),
                    (PropKey::ListRowHeight, PropValue::Float(160.0)),
                ],
            ),
        );
        for (index, id) in [2_u64, 3, 4].into_iter().enumerate() {
            nodes.insert(id, node(id, 1, index as u32, NodeKind::Column, []));
        }
        let tree = Tree { root: 1, nodes };
        let layout = calculate(
            &tree,
            Size {
                width: 360.0,
                height: 640.0,
            },
        )
        .expect("virtual grid");

        assert_eq!(layout[&2].width, 180.0);
        assert_eq!(layout[&2].height, 160.0);
        assert_eq!(layout[&3].x, 180.0);
        assert_eq!(layout[&3].y, 0.0);
        assert_eq!(layout[&4].x, 0.0);
        assert_eq!(layout[&4].y, 160.0);
    }

    #[test]
    fn virtual_list_uses_authored_cell_heights_and_keeps_row_height_as_estimate() {
        let mut nodes = BTreeMap::new();
        nodes.insert(
            1,
            node(
                1,
                0,
                0,
                NodeKind::VirtualList,
                [(PropKey::ListRowHeight, PropValue::Float(120.0))],
            ),
        );
        nodes.insert(
            2,
            node(
                2,
                1,
                0,
                NodeKind::Column,
                [(PropKey::Height, PropValue::Float(240.0))],
            ),
        );
        nodes.insert(3, node(3, 1, 1, NodeKind::Column, []));
        nodes.insert(
            4,
            node(
                4,
                1,
                2,
                NodeKind::Column,
                [(PropKey::Height, PropValue::Float(180.0))],
            ),
        );
        nodes.insert(
            5,
            node(
                5,
                1,
                3,
                NodeKind::Column,
                [(PropKey::Height, PropValue::Float(60.0))],
            ),
        );
        let tree = Tree { root: 1, nodes };
        let layout = calculate(
            &tree,
            Size {
                width: 360.0,
                height: 640.0,
            },
        )
        .expect("adaptive virtual list");

        assert_eq!(layout[&2].height, 240.0);
        assert_eq!(layout[&3].y, 240.0);
        assert_eq!(layout[&3].height, 120.0);
        assert_eq!(layout[&4].y, 360.0);
        assert_eq!(layout[&4].height, 180.0);
        assert_eq!(layout[&5].y, 540.0);
        assert_eq!(layout[&5].height, 60.0);
    }

    #[test]
    fn horizontal_virtual_list_uses_authored_cell_widths() {
        let mut nodes = BTreeMap::new();
        nodes.insert(
            1,
            node(
                1,
                0,
                0,
                NodeKind::VirtualList,
                [
                    (PropKey::ListHorizontal, PropValue::Boolean(true)),
                    (PropKey::ListRowHeight, PropValue::Float(80.0)),
                ],
            ),
        );
        nodes.insert(
            2,
            node(
                2,
                1,
                0,
                NodeKind::Column,
                [(PropKey::Width, PropValue::Float(140.0))],
            ),
        );
        nodes.insert(3, node(3, 1, 1, NodeKind::Column, []));
        let tree = Tree { root: 1, nodes };
        let layout = calculate(
            &tree,
            Size {
                width: 360.0,
                height: 240.0,
            },
        )
        .expect("adaptive horizontal virtual list");

        assert_eq!(layout[&2].width, 140.0);
        assert_eq!(layout[&3].x, 140.0);
        assert_eq!(layout[&3].width, 80.0);
    }

    #[test]
    fn incremental_layout_skips_clean_stable_subtrees() {
        let mut nodes = BTreeMap::from([(1, node(1, 0, 0, NodeKind::Screen, []))]);
        let mut next_id = 2_u64;
        let mut dirty = 0_u64;
        for branch_index in 0..2 {
            let branch = next_id;
            next_id += 1;
            nodes.insert(
                branch,
                node(
                    branch,
                    1,
                    branch_index,
                    NodeKind::Column,
                    [(PropKey::Height, PropValue::Float(300.0))],
                ),
            );
            for row_index in 0..50 {
                let row = next_id;
                next_id += 1;
                nodes.insert(
                    row,
                    node(
                        row,
                        branch,
                        row_index,
                        NodeKind::Row,
                        [(PropKey::Height, PropValue::Float(6.0))],
                    ),
                );
                for text_index in 0..5 {
                    let text = next_id;
                    next_id += 1;
                    nodes.insert(
                        text,
                        node(
                            text,
                            row,
                            text_index,
                            NodeKind::Text,
                            [(PropKey::Text, PropValue::String("value".to_owned()))],
                        ),
                    );
                    if branch_index == 0 && row_index == 0 && text_index == 0 {
                        dirty = text;
                    }
                }
            }
        }
        let mut tree = Tree { root: 1, nodes };
        let viewport = Size {
            width: 390.0,
            height: 844.0,
        };
        let metrics = TextMetrics::new();
        let previous =
            calculate_with_text_metrics(&tree, viewport, 1.0, &metrics).expect("initial layout");
        tree.nodes
            .get_mut(&dirty)
            .expect("dirty text")
            .properties
            .insert(PropKey::FontSize, PropValue::Float(18.0));
        let (incremental, visited) = calculate_incremental_with_text_metrics(
            &tree,
            viewport,
            1.0,
            &metrics,
            &previous,
            &BTreeSet::from([dirty]),
        )
        .expect("incremental layout");
        let full =
            calculate_with_text_metrics(&tree, viewport, 1.0, &metrics).expect("full layout");

        assert_eq!(incremental, full);
        assert!(visited < tree.nodes.len() / 4, "visited {visited} nodes");
    }

    #[test]
    fn incremental_layout_removes_hidden_dirty_subtrees() {
        let mut tree = Tree {
            root: 1,
            nodes: BTreeMap::from([
                (1, node(1, 0, 0, NodeKind::Screen, [])),
                (2, node(2, 1, 0, NodeKind::Column, [])),
                (3, node(3, 2, 0, NodeKind::Text, [])),
                (4, node(4, 1, 1, NodeKind::Text, [])),
            ]),
        };
        let viewport = Size {
            width: 390.0,
            height: 844.0,
        };
        let metrics = TextMetrics::new();
        let previous =
            calculate_with_text_metrics(&tree, viewport, 1.0, &metrics).expect("initial layout");
        tree.nodes
            .get_mut(&2)
            .expect("container")
            .properties
            .insert(PropKey::Visible, PropValue::Boolean(false));

        let (incremental, _) = calculate_incremental_with_text_metrics(
            &tree,
            viewport,
            1.0,
            &metrics,
            &previous,
            &BTreeSet::from([2]),
        )
        .expect("incremental layout");
        let full =
            calculate_with_text_metrics(&tree, viewport, 1.0, &metrics).expect("full layout");

        assert_eq!(incremental, full);
        assert!(!incremental.contains_key(&2));
        assert!(!incremental.contains_key(&3));
        assert!(incremental.contains_key(&4));
    }
}
