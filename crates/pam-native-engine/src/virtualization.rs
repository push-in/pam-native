use std::ops::Range;

const MAX_ITEMS: usize = 1_000_000;
const MIN_EXTENT: f32 = 1.0;
const MAX_EXTENT: f32 = 100_000.0;

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Viewport {
    pub offset: f32,
    pub extent: f32,
    pub velocity: f32,
}

#[derive(Clone, Debug, PartialEq)]
pub struct VirtualWindow {
    pub visible: Range<usize>,
    pub mounted: Range<usize>,
    pub leading_offset: f32,
    pub total_extent: f32,
    pub suggested_pool_size: usize,
}

#[derive(Debug)]
pub struct Virtualizer {
    extents: Vec<f32>,
    fenwick: Vec<f32>,
    base_overscan: f32,
    maximum_overscan: f32,
    last_viewport: Option<Viewport>,
}

impl Virtualizer {
    pub fn new(
        item_count: usize,
        estimated_extent: f32,
        base_overscan: f32,
    ) -> Result<Self, VirtualizationError> {
        if item_count > MAX_ITEMS {
            return Err(VirtualizationError::TooManyItems);
        }
        let estimated_extent = valid_extent(estimated_extent)?;
        if !base_overscan.is_finite() || base_overscan < 0.0 {
            return Err(VirtualizationError::InvalidViewport);
        }
        let extents = vec![estimated_extent; item_count];
        let mut virtualizer = Self {
            extents,
            fenwick: vec![0.0; item_count.saturating_add(1)],
            base_overscan,
            maximum_overscan: base_overscan.max(estimated_extent).min(20_000.0) * 8.0,
            last_viewport: None,
        };
        virtualizer.rebuild();
        Ok(virtualizer)
    }

    pub fn replace(
        &mut self,
        item_count: usize,
        estimated_extent: f32,
    ) -> Result<(), VirtualizationError> {
        if item_count > MAX_ITEMS {
            return Err(VirtualizationError::TooManyItems);
        }
        let estimate = valid_extent(estimated_extent)?;
        self.extents.resize(item_count, estimate);
        self.fenwick.resize(item_count.saturating_add(1), 0.0);
        self.rebuild();
        Ok(())
    }

    pub fn measure(&mut self, index: usize, extent: f32) -> Result<f32, VirtualizationError> {
        let extent = valid_extent(extent)?;
        let previous = *self
            .extents
            .get(index)
            .ok_or(VirtualizationError::UnknownItem(index))?;
        let delta = extent - previous;
        if delta.abs() <= f32::EPSILON {
            return Ok(0.0);
        }
        self.extents[index] = extent;
        self.add(index, delta);
        Ok(delta)
    }

    pub fn window(&mut self, viewport: Viewport) -> Result<VirtualWindow, VirtualizationError> {
        if !viewport.offset.is_finite()
            || !viewport.extent.is_finite()
            || !viewport.velocity.is_finite()
            || viewport.extent <= 0.0
        {
            return Err(VirtualizationError::InvalidViewport);
        }
        let total_extent = self.total_extent();
        if self.extents.is_empty() {
            return Ok(VirtualWindow {
                visible: 0..0,
                mounted: 0..0,
                leading_offset: 0.0,
                total_extent,
                suggested_pool_size: 0,
            });
        }

        let offset = viewport
            .offset
            .max(0.0)
            .min((total_extent - viewport.extent).max(0.0));
        let visible_start = self.index_at(offset);
        let visible_end = (self.index_at((offset + viewport.extent).min(total_extent)) + 1)
            .min(self.extents.len());

        let velocity_factor = (viewport.velocity.abs() / viewport.extent).min(4.0);
        let adaptive = (self.base_overscan * (1.0 + velocity_factor))
            .min(self.maximum_overscan)
            .max(self.base_overscan);
        let (leading, trailing) = if viewport.velocity >= 0.0 {
            (adaptive * 0.5, adaptive * 1.5)
        } else {
            (adaptive * 1.5, adaptive * 0.5)
        };
        let mounted_start = self.index_at((offset - leading).max(0.0));
        let mounted_end = (self.index_at((offset + viewport.extent + trailing).min(total_extent))
            + 1)
        .min(self.extents.len());
        self.last_viewport = Some(viewport);

        Ok(VirtualWindow {
            visible: visible_start..visible_end,
            mounted: mounted_start..mounted_end,
            leading_offset: self.prefix_sum(mounted_start),
            total_extent,
            suggested_pool_size: mounted_end
                .saturating_sub(mounted_start)
                .saturating_add(2)
                .min(256),
        })
    }

    #[must_use]
    pub fn anchored_offset(&self, anchor_index: usize, offset_inside_item: f32) -> Option<f32> {
        self.extents.get(anchor_index)?;
        Some((self.prefix_sum(anchor_index) + offset_inside_item).max(0.0))
    }

    #[must_use]
    pub fn total_extent(&self) -> f32 {
        self.prefix_sum(self.extents.len())
    }

    fn rebuild(&mut self) {
        self.fenwick.fill(0.0);
        for index in 0..self.extents.len() {
            self.add(index, self.extents[index]);
        }
    }

    fn add(&mut self, index: usize, delta: f32) {
        let mut cursor = index + 1;
        while cursor < self.fenwick.len() {
            self.fenwick[cursor] += delta;
            cursor += cursor & cursor.wrapping_neg();
        }
    }

    fn prefix_sum(&self, end: usize) -> f32 {
        let mut cursor = end.min(self.extents.len());
        let mut total = 0.0;
        while cursor > 0 {
            total += self.fenwick[cursor];
            cursor &= cursor - 1;
        }
        total
    }

    fn index_at(&self, offset: f32) -> usize {
        if self.extents.is_empty() {
            return 0;
        }
        let target = offset.max(0.0);
        let mut index = 0_usize;
        let mut accumulated = 0.0_f32;
        let mut bit = highest_power_of_two(self.extents.len());
        while bit > 0 {
            let next = index + bit;
            if next < self.fenwick.len() && accumulated + self.fenwick[next] <= target {
                index = next;
                accumulated += self.fenwick[next];
            }
            bit >>= 1;
        }
        index.min(self.extents.len() - 1)
    }
}

fn valid_extent(extent: f32) -> Result<f32, VirtualizationError> {
    if extent.is_finite() && (MIN_EXTENT..=MAX_EXTENT).contains(&extent) {
        Ok(extent)
    } else {
        Err(VirtualizationError::InvalidExtent)
    }
}

fn highest_power_of_two(value: usize) -> usize {
    if value == 0 {
        0
    } else {
        1 << (usize::BITS - 1 - value.leading_zeros())
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum VirtualizationError {
    TooManyItems,
    InvalidExtent,
    InvalidViewport,
    UnknownItem(usize),
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn finds_a_hundred_thousand_item_window_in_logarithmic_storage() {
        let mut virtualizer = Virtualizer::new(100_000, 50.0, 250.0).expect("virtualizer");
        let window = virtualizer
            .window(Viewport {
                offset: 2_500_000.0,
                extent: 800.0,
                velocity: 4_000.0,
            })
            .expect("window");
        assert_eq!(window.visible.start, 50_000);
        assert!(window.visible.len() <= 18);
        assert!(window.mounted.len() < 100);
        assert_eq!(window.total_extent, 5_000_000.0);
    }

    #[test]
    fn variable_measurements_preserve_an_anchor_without_scanning_the_list() {
        let mut virtualizer = Virtualizer::new(10_000, 40.0, 200.0).expect("virtualizer");
        assert_eq!(virtualizer.anchored_offset(5_000, 12.0), Some(200_012.0));
        virtualizer.measure(1_000, 80.0).expect("measure");
        virtualizer.measure(4_999, 20.0).expect("measure");
        assert_eq!(virtualizer.anchored_offset(5_000, 12.0), Some(200_032.0));
    }

    #[test]
    fn forward_velocity_biases_overscan_towards_future_items() {
        let mut virtualizer = Virtualizer::new(1_000, 50.0, 200.0).expect("virtualizer");
        let still = virtualizer
            .window(Viewport {
                offset: 10_000.0,
                extent: 500.0,
                velocity: 0.0,
            })
            .expect("still");
        let fast = virtualizer
            .window(Viewport {
                offset: 10_000.0,
                extent: 500.0,
                velocity: 2_000.0,
            })
            .expect("fast");
        assert!(fast.mounted.end > still.mounted.end);
        assert!(fast.suggested_pool_size > still.suggested_pool_size);
    }
}
