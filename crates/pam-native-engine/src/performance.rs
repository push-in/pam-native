use std::collections::BTreeMap;
use std::time::{Duration, Instant};

pub const HISTOGRAM_BUCKETS_MICROS: [u64; 16] = [
    10,
    25,
    50,
    100,
    250,
    500,
    1_000,
    2_000,
    4_000,
    8_000,
    12_000,
    16_000,
    24_000,
    40_000,
    80_000,
    u64::MAX,
];

#[derive(Clone, Copy, Debug, Eq, Ord, PartialEq, PartialOrd)]
#[repr(u8)]
pub enum PerformanceStage {
    Decode = 1,
    Reconcile = 2,
    Layout = 3,
    Encode = 4,
    Reactive = 5,
    Scheduler = 6,
    Memory = 7,
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct StageSnapshot {
    pub samples: u64,
    pub total_micros: u64,
    pub maximum_micros: u64,
    pub buckets: [u64; HISTOGRAM_BUCKETS_MICROS.len()],
}

impl StageSnapshot {
    #[must_use]
    pub fn percentile_micros(&self, percentile: u8) -> u64 {
        if self.samples == 0 {
            return 0;
        }
        let percentile = percentile.clamp(1, 100) as u64;
        let target = self.samples.saturating_mul(percentile).div_ceil(100);
        let mut observed = 0_u64;
        for (index, count) in self.buckets.iter().enumerate() {
            observed = observed.saturating_add(*count);
            if observed >= target {
                return HISTOGRAM_BUCKETS_MICROS[index].min(self.maximum_micros);
            }
        }
        self.maximum_micros
    }
}

#[derive(Debug, Default)]
pub struct PerformanceObserver {
    stages: BTreeMap<PerformanceStage, StageSnapshot>,
    frames: u64,
    deadline_misses: u64,
    coalesced_commands: u64,
    reused_bytes: u64,
}

impl PerformanceObserver {
    pub fn record(&mut self, stage: PerformanceStage, duration: Duration) {
        let micros = u64::try_from(duration.as_micros()).unwrap_or(u64::MAX);
        let snapshot = self.stages.entry(stage).or_default();
        snapshot.samples = snapshot.samples.saturating_add(1);
        snapshot.total_micros = snapshot.total_micros.saturating_add(micros);
        snapshot.maximum_micros = snapshot.maximum_micros.max(micros);
        let bucket = HISTOGRAM_BUCKETS_MICROS
            .iter()
            .position(|boundary| micros <= *boundary)
            .unwrap_or(HISTOGRAM_BUCKETS_MICROS.len() - 1);
        snapshot.buckets[bucket] = snapshot.buckets[bucket].saturating_add(1);
    }

    #[must_use]
    pub fn measure(&mut self, stage: PerformanceStage) -> PerformanceMeasure<'_> {
        PerformanceMeasure {
            observer: self,
            stage,
            started: Instant::now(),
        }
    }

    pub fn frame_completed(&mut self, elapsed: Duration, budget: Duration) {
        self.frames = self.frames.saturating_add(1);
        if elapsed > budget {
            self.deadline_misses = self.deadline_misses.saturating_add(1);
        }
    }

    pub fn commands_coalesced(&mut self, count: usize) {
        self.coalesced_commands = self
            .coalesced_commands
            .saturating_add(u64::try_from(count).unwrap_or(u64::MAX));
    }

    pub fn bytes_reused(&mut self, count: usize) {
        self.reused_bytes = self
            .reused_bytes
            .saturating_add(u64::try_from(count).unwrap_or(u64::MAX));
    }

    #[must_use]
    pub fn snapshot(&self) -> PerformanceSnapshot {
        PerformanceSnapshot {
            stages: self.stages.clone(),
            frames: self.frames,
            deadline_misses: self.deadline_misses,
            coalesced_commands: self.coalesced_commands,
            reused_bytes: self.reused_bytes,
        }
    }
}

pub struct PerformanceMeasure<'a> {
    observer: &'a mut PerformanceObserver,
    stage: PerformanceStage,
    started: Instant,
}

impl Drop for PerformanceMeasure<'_> {
    fn drop(&mut self) {
        self.observer.record(self.stage, self.started.elapsed());
    }
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct PerformanceSnapshot {
    pub stages: BTreeMap<PerformanceStage, StageSnapshot>,
    pub frames: u64,
    pub deadline_misses: u64,
    pub coalesced_commands: u64,
    pub reused_bytes: u64,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn snapshots_bounded_histograms_and_tail_percentiles() {
        let mut observer = PerformanceObserver::default();
        for micros in [10, 20, 40, 80, 160, 320, 640, 1_280, 2_560, 5_120] {
            observer.record(PerformanceStage::Decode, Duration::from_micros(micros));
        }
        observer.frame_completed(Duration::from_millis(18), Duration::from_millis(16));
        observer.commands_coalesced(7);
        observer.bytes_reused(4_096);

        let snapshot = observer.snapshot();
        let decode = &snapshot.stages[&PerformanceStage::Decode];
        assert_eq!(decode.samples, 10);
        assert!(decode.percentile_micros(50) <= 500);
        assert!(decode.percentile_micros(95) >= 4_000);
        assert_eq!(snapshot.deadline_misses, 1);
        assert_eq!(snapshot.coalesced_commands, 7);
        assert_eq!(snapshot.reused_bytes, 4_096);
    }
}
