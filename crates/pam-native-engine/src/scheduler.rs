use std::cmp::Ordering;
use std::collections::{BinaryHeap, HashMap};
use std::time::{Duration, Instant};

#[derive(Clone, Copy, Debug, Eq, Ord, PartialEq, PartialOrd)]
#[repr(u8)]
pub enum TaskPriority {
    Input = 1,
    Animation = 2,
    VisibleRender = 3,
    Navigation = 4,
    Prefetch = 5,
    Background = 6,
    Idle = 7,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum RefreshRate {
    Hertz60 = 1,
    Hertz90 = 2,
    Hertz120 = 3,
    Hertz144 = 4,
}

impl RefreshRate {
    #[must_use]
    pub fn frame_budget(self) -> Duration {
        match self {
            Self::Hertz60 => Duration::from_nanos(16_666_667),
            Self::Hertz90 => Duration::from_nanos(11_111_111),
            Self::Hertz120 => Duration::from_nanos(8_333_333),
            Self::Hertz144 => Duration::from_nanos(6_944_444),
        }
    }

    #[must_use]
    pub fn closest(frames_per_second: f64) -> Self {
        if frames_per_second >= 132.0 {
            Self::Hertz144
        } else if frames_per_second >= 105.0 {
            Self::Hertz120
        } else if frames_per_second >= 75.0 {
            Self::Hertz90
        } else {
            Self::Hertz60
        }
    }
}

pub type TaskId = u64;

pub struct ScheduledTask {
    id: TaskId,
    priority: TaskPriority,
    sequence: u64,
    estimated_cost: Duration,
    coalesce: Option<u64>,
    work: Option<Box<dyn FnOnce() + Send + 'static>>,
}

impl std::fmt::Debug for ScheduledTask {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("ScheduledTask")
            .field("id", &self.id)
            .field("priority", &self.priority)
            .field("sequence", &self.sequence)
            .field("estimated_cost", &self.estimated_cost)
            .field("coalesce", &self.coalesce)
            .finish_non_exhaustive()
    }
}

impl PartialEq for ScheduledTask {
    fn eq(&self, other: &Self) -> bool {
        self.id == other.id
    }
}

impl Eq for ScheduledTask {}

impl PartialOrd for ScheduledTask {
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}

impl Ord for ScheduledTask {
    fn cmp(&self, other: &Self) -> Ordering {
        other
            .priority
            .cmp(&self.priority)
            .then_with(|| other.sequence.cmp(&self.sequence))
    }
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct DrainReport {
    pub executed: usize,
    pub cancelled: usize,
    pub remaining: usize,
    pub yielded: bool,
}

#[derive(Debug)]
pub struct FrameScheduler {
    queue: BinaryHeap<ScheduledTask>,
    coalesced: HashMap<u64, TaskId>,
    next_id: TaskId,
    sequence: u64,
    maximum_tasks: usize,
    refresh_rate: RefreshRate,
    safety_margin: Duration,
}

impl Default for FrameScheduler {
    fn default() -> Self {
        Self {
            queue: BinaryHeap::new(),
            coalesced: HashMap::new(),
            next_id: 1,
            sequence: 0,
            maximum_tasks: 4_096,
            refresh_rate: RefreshRate::Hertz60,
            safety_margin: Duration::from_micros(1_500),
        }
    }
}

impl FrameScheduler {
    pub fn set_refresh_rate(&mut self, frames_per_second: f64) {
        self.refresh_rate = RefreshRate::closest(frames_per_second);
    }

    pub fn schedule(
        &mut self,
        priority: TaskPriority,
        estimated_cost: Duration,
        coalesce: Option<u64>,
        work: impl FnOnce() + Send + 'static,
    ) -> Option<TaskId> {
        if self.queue.len() >= self.maximum_tasks {
            return None;
        }
        let id = self.next_id;
        self.next_id = self.next_id.saturating_add(1);
        self.sequence = self.sequence.saturating_add(1);
        if let Some(key) = coalesce {
            self.coalesced.insert(key, id);
        }
        self.queue.push(ScheduledTask {
            id,
            priority,
            sequence: self.sequence,
            estimated_cost,
            coalesce,
            work: Some(Box::new(work)),
        });
        Some(id)
    }

    pub fn drain_frame(&mut self) -> DrainReport {
        let started = Instant::now();
        let budget = self
            .refresh_rate
            .frame_budget()
            .saturating_sub(self.safety_margin);
        let mut report = DrainReport::default();

        while let Some(mut task) = self.queue.pop() {
            if let Some(key) = task.coalesce {
                if self.coalesced.get(&key) != Some(&task.id) {
                    report.cancelled = report.cancelled.saturating_add(1);
                    continue;
                }
                self.coalesced.remove(&key);
            }
            if report.executed > 0
                && started.elapsed().saturating_add(task.estimated_cost) > budget
                && task.priority > TaskPriority::Animation
            {
                self.queue.push(task);
                report.yielded = true;
                break;
            }
            if let Some(work) = task.work.take() {
                work();
            }
            report.executed = report.executed.saturating_add(1);
        }
        report.remaining = self.queue.len();
        report
    }
}

#[cfg(test)]
mod tests {
    use std::sync::{Arc, Mutex};

    use super::*;

    #[test]
    fn prioritizes_input_and_cancels_obsolete_coalesced_work() {
        let order = Arc::new(Mutex::new(Vec::new()));
        let mut scheduler = FrameScheduler::default();
        for (priority, key, value) in [
            (TaskPriority::Background, Some(8), 1),
            (TaskPriority::Background, Some(8), 2),
            (TaskPriority::Input, None, 3),
        ] {
            let order = Arc::clone(&order);
            scheduler.schedule(priority, Duration::ZERO, key, move || {
                order.lock().expect("order").push(value);
            });
        }
        let report = scheduler.drain_frame();
        assert_eq!(*order.lock().expect("order"), vec![3, 2]);
        assert_eq!(report.executed, 2);
        assert_eq!(report.cancelled, 1);
    }

    #[test]
    fn computes_real_display_budgets() {
        assert_eq!(RefreshRate::closest(120.0), RefreshRate::Hertz120);
        assert_eq!(RefreshRate::closest(144.0), RefreshRate::Hertz144);
        assert_eq!(RefreshRate::closest(90.0), RefreshRate::Hertz90);
        assert_eq!(RefreshRate::closest(59.0), RefreshRate::Hertz60);
        assert!(RefreshRate::Hertz120.frame_budget() < RefreshRate::Hertz60.frame_budget());
        assert!(RefreshRate::Hertz144.frame_budget() < RefreshRate::Hertz120.frame_budget());
    }
}
