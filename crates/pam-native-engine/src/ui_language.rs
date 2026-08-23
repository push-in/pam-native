use std::collections::BTreeSet;

use crate::reactive::{Instruction, ReactiveError, SignalId, Worklet};

pub const UI_IR_VERSION: u16 = 2;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u8)]
pub enum InteractionState {
    Pressed = 1,
    Focused = 2,
    Disabled = 3,
    Selected = 4,
    Checked = 5,
    Hovered = 6,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u8)]
pub enum QueryKind {
    Viewport = 1,
    Container = 2,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u8)]
pub enum QueryAxis {
    Width = 1,
    Height = 2,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u8)]
pub enum QueryComparison {
    Minimum = 1,
    Maximum = 2,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct ResponsiveQuery {
    pub kind: QueryKind,
    pub axis: QueryAxis,
    pub comparison: QueryComparison,
    pub threshold: f32,
}

impl ResponsiveQuery {
    #[must_use]
    pub fn matches(self, width: f32, height: f32) -> bool {
        if !self.threshold.is_finite() || self.threshold < 0.0 {
            return false;
        }
        let actual = match self.axis {
            QueryAxis::Width => width,
            QueryAxis::Height => height,
        };
        match self.comparison {
            QueryComparison::Minimum => actual >= self.threshold,
            QueryComparison::Maximum => actual <= self.threshold,
        }
    }
}

#[derive(Clone, Debug, Eq, Ord, PartialEq, PartialOrd)]
pub enum StableKey {
    Integer(i64),
    String(String),
}

#[derive(Debug, Default)]
pub struct StableKeySet {
    keys: BTreeSet<StableKey>,
}

impl StableKeySet {
    pub fn insert(&mut self, key: StableKey) -> Result<(), UiLanguageError> {
        if matches!(&key, StableKey::String(value) if value.is_empty() || value.len() > 1_024) {
            return Err(UiLanguageError::InvalidStableKey);
        }
        if !self.keys.insert(key) {
            return Err(UiLanguageError::DuplicateStableKey);
        }
        Ok(())
    }
}

/// Compiles a two-point compositor animation to the existing native worklet VM.
pub fn animation_worklet(progress: SignalId, from: f64, to: f64) -> Result<Worklet, ReactiveError> {
    Worklet::compile(vec![
        Instruction::Constant(from),
        Instruction::Signal(progress),
        Instruction::Constant(to - from),
        Instruction::Multiply,
        Instruction::Add,
    ])
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct AccessibilityMetrics {
    pub width: f32,
    pub height: f32,
    pub interactive: bool,
    pub labelled: bool,
}

impl AccessibilityMetrics {
    pub fn audit(self) -> Result<(), UiLanguageError> {
        if self.interactive && !self.labelled {
            return Err(UiLanguageError::MissingAccessibilityLabel);
        }
        if self.interactive && (self.width < 44.0 || self.height < 44.0) {
            return Err(UiLanguageError::TouchTargetTooSmall);
        }
        Ok(())
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum UiLanguageError {
    DuplicateStableKey,
    InvalidStableKey,
    MissingAccessibilityLabel,
    TouchTargetTooSmall,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn language_two_capabilities_are_stable_and_native_safe() {
        assert_eq!(UI_IR_VERSION, 2);
        assert!(
            ResponsiveQuery {
                kind: QueryKind::Viewport,
                axis: QueryAxis::Width,
                comparison: QueryComparison::Minimum,
                threshold: 768.0,
            }
            .matches(1_024.0, 600.0)
        );

        let mut keys = StableKeySet::default();
        keys.insert(StableKey::String("product-1".into())).unwrap();
        assert_eq!(
            keys.insert(StableKey::String("product-1".into())),
            Err(UiLanguageError::DuplicateStableKey),
        );

        assert!(animation_worklet(1, 0.0, 1.0).is_ok());
        assert_eq!(
            AccessibilityMetrics {
                width: 32.0,
                height: 32.0,
                interactive: true,
                labelled: true,
            }
            .audit(),
            Err(UiLanguageError::TouchTargetTooSmall),
        );
    }
}
