use std::collections::{BTreeMap, BTreeSet};

use pam_native_protocol::{Mutation, PropKey, PropValue};

pub type SignalId = u32;
pub type WorkletId = u32;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u8)]
pub enum WorkletOperation {
    Signal = 1,
    Constant = 2,
    Add = 3,
    Subtract = 4,
    Multiply = 5,
    Divide = 6,
    Minimum = 7,
    Maximum = 8,
    Clamp = 9,
    Negate = 10,
    Absolute = 11,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Instruction {
    Signal(SignalId),
    Constant(f64),
    Add,
    Subtract,
    Multiply,
    Divide,
    Minimum,
    Maximum,
    Clamp,
    Negate,
    Absolute,
}

impl Instruction {
    #[must_use]
    pub fn operation(self) -> WorkletOperation {
        match self {
            Self::Signal(_) => WorkletOperation::Signal,
            Self::Constant(_) => WorkletOperation::Constant,
            Self::Add => WorkletOperation::Add,
            Self::Subtract => WorkletOperation::Subtract,
            Self::Multiply => WorkletOperation::Multiply,
            Self::Divide => WorkletOperation::Divide,
            Self::Minimum => WorkletOperation::Minimum,
            Self::Maximum => WorkletOperation::Maximum,
            Self::Clamp => WorkletOperation::Clamp,
            Self::Negate => WorkletOperation::Negate,
            Self::Absolute => WorkletOperation::Absolute,
        }
    }
}

#[derive(Clone, Debug)]
pub struct Worklet {
    instructions: Vec<Instruction>,
    dependencies: BTreeSet<SignalId>,
    maximum_stack: usize,
}

impl Worklet {
    pub fn compile(instructions: Vec<Instruction>) -> Result<Self, ReactiveError> {
        if instructions.is_empty() || instructions.len() > 1_024 {
            return Err(ReactiveError::InvalidProgram);
        }
        let mut depth = 0_usize;
        let mut maximum_stack = 0_usize;
        let mut dependencies = BTreeSet::new();
        for instruction in &instructions {
            match instruction {
                Instruction::Signal(id) => {
                    if *id == 0 {
                        return Err(ReactiveError::InvalidSignal);
                    }
                    dependencies.insert(*id);
                    depth = depth.saturating_add(1);
                }
                Instruction::Constant(value) => {
                    if !value.is_finite() {
                        return Err(ReactiveError::NonFiniteValue);
                    }
                    depth = depth.saturating_add(1);
                }
                Instruction::Negate | Instruction::Absolute => {
                    if depth < 1 {
                        return Err(ReactiveError::StackUnderflow);
                    }
                }
                Instruction::Clamp => {
                    if depth < 3 {
                        return Err(ReactiveError::StackUnderflow);
                    }
                    depth -= 2;
                }
                _ => {
                    if depth < 2 {
                        return Err(ReactiveError::StackUnderflow);
                    }
                    depth -= 1;
                }
            }
            maximum_stack = maximum_stack.max(depth);
        }
        if depth != 1 || maximum_stack > 64 {
            return Err(ReactiveError::InvalidProgram);
        }
        Ok(Self {
            instructions,
            dependencies,
            maximum_stack,
        })
    }

    fn execute(
        &self,
        signals: &BTreeMap<SignalId, f64>,
        stack: &mut Vec<f64>,
    ) -> Result<f64, ReactiveError> {
        stack.clear();
        if stack.capacity() < self.maximum_stack {
            stack.reserve(self.maximum_stack - stack.capacity());
        }
        for instruction in &self.instructions {
            match *instruction {
                Instruction::Signal(id) => {
                    stack.push(*signals.get(&id).ok_or(ReactiveError::UnknownSignal(id))?)
                }
                Instruction::Constant(value) => stack.push(value),
                Instruction::Negate => unary(stack, |value| -value)?,
                Instruction::Absolute => unary(stack, f64::abs)?,
                Instruction::Add => binary(stack, |left, right| left + right)?,
                Instruction::Subtract => binary(stack, |left, right| left - right)?,
                Instruction::Multiply => binary(stack, |left, right| left * right)?,
                Instruction::Divide => binary(stack, |left, right| {
                    if right.abs() <= f64::EPSILON {
                        0.0
                    } else {
                        left / right
                    }
                })?,
                Instruction::Minimum => binary(stack, f64::min)?,
                Instruction::Maximum => binary(stack, f64::max)?,
                Instruction::Clamp => {
                    let maximum = stack.pop().ok_or(ReactiveError::StackUnderflow)?;
                    let minimum = stack.pop().ok_or(ReactiveError::StackUnderflow)?;
                    let value = stack.pop().ok_or(ReactiveError::StackUnderflow)?;
                    stack.push(value.clamp(minimum, maximum));
                }
            }
        }
        let value = stack.pop().ok_or(ReactiveError::StackUnderflow)?;
        if value.is_finite() {
            Ok(value)
        } else {
            Err(ReactiveError::NonFiniteValue)
        }
    }
}

fn unary(stack: &mut Vec<f64>, operation: impl FnOnce(f64) -> f64) -> Result<(), ReactiveError> {
    let value = stack.pop().ok_or(ReactiveError::StackUnderflow)?;
    stack.push(operation(value));
    Ok(())
}

fn binary(
    stack: &mut Vec<f64>,
    operation: impl FnOnce(f64, f64) -> f64,
) -> Result<(), ReactiveError> {
    let right = stack.pop().ok_or(ReactiveError::StackUnderflow)?;
    let left = stack.pop().ok_or(ReactiveError::StackUnderflow)?;
    stack.push(operation(left, right));
    Ok(())
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct NativeBinding {
    pub node: u64,
    pub property: PropKey,
    pub worklet: WorkletId,
}

#[derive(Debug, Default)]
pub struct ReactiveRuntime {
    signals: BTreeMap<SignalId, f64>,
    worklets: BTreeMap<WorkletId, Worklet>,
    bindings: Vec<NativeBinding>,
    bindings_by_worklet: BTreeMap<WorkletId, Vec<NativeBinding>>,
    dependencies: BTreeMap<SignalId, BTreeSet<WorkletId>>,
    last_values: BTreeMap<(u64, PropKey), f64>,
    evaluation_stack: Vec<f64>,
}

impl ReactiveRuntime {
    pub fn define_signal(&mut self, id: SignalId, value: f64) -> Result<(), ReactiveError> {
        if id == 0 {
            return Err(ReactiveError::InvalidSignal);
        }
        if !value.is_finite() {
            return Err(ReactiveError::NonFiniteValue);
        }
        self.signals.insert(id, value);
        Ok(())
    }

    pub fn define_worklet(&mut self, id: WorkletId, worklet: Worklet) -> Result<(), ReactiveError> {
        if id == 0 {
            return Err(ReactiveError::InvalidProgram);
        }
        if let Some(previous) = self.worklets.insert(id, worklet) {
            for signal in previous.dependencies {
                if let Some(worklets) = self.dependencies.get_mut(&signal) {
                    worklets.remove(&id);
                }
            }
        }
        for signal in &self.worklets[&id].dependencies {
            self.dependencies.entry(*signal).or_default().insert(id);
        }
        Ok(())
    }

    pub fn bind(&mut self, binding: NativeBinding) -> Result<(), ReactiveError> {
        if binding.node == 0 || !self.worklets.contains_key(&binding.worklet) {
            return Err(ReactiveError::UnknownWorklet(binding.worklet));
        }
        if let Some(previous) = self
            .bindings
            .iter()
            .find(|current| current.node == binding.node && current.property == binding.property)
            .copied()
            && let Some(bindings) = self.bindings_by_worklet.get_mut(&previous.worklet)
        {
            bindings.retain(|current| {
                current.node != binding.node || current.property != binding.property
            });
            if bindings.is_empty() {
                self.bindings_by_worklet.remove(&previous.worklet);
            }
        }
        self.bindings
            .retain(|current| current.node != binding.node || current.property != binding.property);
        self.bindings.push(binding);
        self.bindings_by_worklet
            .entry(binding.worklet)
            .or_default()
            .push(binding);
        Ok(())
    }

    pub fn update_signal(
        &mut self,
        id: SignalId,
        value: f64,
    ) -> Result<Vec<Mutation>, ReactiveError> {
        if !value.is_finite() {
            return Err(ReactiveError::NonFiniteValue);
        }
        let current = self
            .signals
            .get_mut(&id)
            .ok_or(ReactiveError::UnknownSignal(id))?;
        if current.to_bits() == value.to_bits() {
            return Ok(Vec::new());
        }
        *current = value;
        let Some(affected) = self.dependencies.get(&id) else {
            return Ok(Vec::new());
        };
        let mut mutations = Vec::new();
        for worklet in affected {
            let value =
                self.worklets[worklet].execute(&self.signals, &mut self.evaluation_stack)?;
            for binding in self.bindings_by_worklet.get(worklet).into_iter().flatten() {
                let key = (binding.node, binding.property);
                if self
                    .last_values
                    .get(&key)
                    .is_some_and(|last| last.to_bits() == value.to_bits())
                {
                    continue;
                }
                self.last_values.insert(key, value);
                mutations.push(Mutation::Update {
                    id: binding.node,
                    key: binding.property,
                    value: Some(PropValue::Float(value)),
                });
            }
        }
        Ok(mutations)
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub enum ReactiveError {
    InvalidSignal,
    UnknownSignal(SignalId),
    UnknownWorklet(WorkletId),
    InvalidProgram,
    StackUnderflow,
    NonFiniteValue,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn executes_native_bindings_without_a_php_frame() {
        let mut runtime = ReactiveRuntime::default();
        runtime.define_signal(1, 0.0).expect("signal");
        runtime
            .define_worklet(
                1,
                Worklet::compile(vec![
                    Instruction::Constant(1.0),
                    Instruction::Signal(1),
                    Instruction::Subtract,
                    Instruction::Constant(0.0),
                    Instruction::Constant(1.0),
                    Instruction::Clamp,
                ])
                .expect("worklet"),
            )
            .expect("define");
        runtime
            .bind(NativeBinding {
                node: 7,
                property: PropKey::Opacity,
                worklet: 1,
            })
            .expect("bind");

        let mutations = runtime.update_signal(1, 0.25).expect("update");
        assert_eq!(mutations.len(), 1);
        assert_eq!(
            mutations[0],
            Mutation::Update {
                id: 7,
                key: PropKey::Opacity,
                value: Some(PropValue::Float(0.75)),
            }
        );
        assert!(runtime.update_signal(1, 0.25).expect("same").is_empty());
    }

    #[test]
    fn rejects_unbounded_or_malformed_programs() {
        assert_eq!(
            Worklet::compile(vec![Instruction::Add]).expect_err("underflow"),
            ReactiveError::StackUnderflow,
        );
        assert!(Worklet::compile(vec![Instruction::Constant(f64::NAN)]).is_err());
    }
}
