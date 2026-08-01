use std::collections::BTreeMap;

use pam_native_protocol::{Mutation, PropKey, ProtocolError, encode_batch_into};

#[derive(Clone, Copy, Debug, Eq, Ord, PartialEq, PartialOrd)]
enum CoalescingKey {
    Property(u64, PropKey),
    Layout(u64),
    Move(u64),
    Root,
}

#[derive(Debug)]
pub struct CommandArena {
    bytes: Vec<u8>,
    retained_capacity_limit: usize,
    generations: u64,
    reused_bytes: u64,
}

impl Default for CommandArena {
    fn default() -> Self {
        Self::new(256 * 1024)
    }
}

impl CommandArena {
    #[must_use]
    pub fn new(retained_capacity_limit: usize) -> Self {
        Self {
            bytes: Vec::with_capacity(retained_capacity_limit.min(16 * 1024)),
            retained_capacity_limit,
            generations: 0,
            reused_bytes: 0,
        }
    }

    pub fn encode<'a>(
        &'a mut self,
        commands: &[Mutation],
    ) -> Result<CommandBatch<'a>, ProtocolError> {
        let previous_capacity = self.bytes.capacity();
        let (commands, coalesced) = coalesce(commands);
        encode_batch_into(&mut self.bytes, &commands)?;
        if previous_capacity > 0 && self.bytes.capacity() == previous_capacity {
            self.reused_bytes = self
                .reused_bytes
                .saturating_add(u64::try_from(self.bytes.len()).unwrap_or(u64::MAX));
        }
        self.generations = self.generations.saturating_add(1);
        Ok(CommandBatch {
            bytes: &self.bytes,
            generation: self.generations,
            commands: commands.len(),
            coalesced,
        })
    }

    pub fn trim(&mut self, critical: bool) {
        if critical || self.bytes.capacity() > self.retained_capacity_limit {
            self.bytes = Vec::new();
        } else {
            self.bytes.clear();
        }
    }

    #[must_use]
    pub fn reused_bytes(&self) -> u64 {
        self.reused_bytes
    }
}

#[derive(Clone, Copy, Debug)]
pub struct CommandBatch<'a> {
    pub bytes: &'a [u8],
    pub generation: u64,
    pub commands: usize,
    pub coalesced: usize,
}

fn coalescing_key(mutation: &Mutation) -> Option<CoalescingKey> {
    match mutation {
        Mutation::Update { id, key, .. } => Some(CoalescingKey::Property(*id, *key)),
        Mutation::Layout { id, .. } => Some(CoalescingKey::Layout(*id)),
        Mutation::Move { id, .. } => Some(CoalescingKey::Move(*id)),
        Mutation::SetRoot { .. } => Some(CoalescingKey::Root),
        Mutation::Create(_) | Mutation::Remove { .. } => None,
    }
}

#[must_use]
pub fn coalesce(commands: &[Mutation]) -> (Vec<Mutation>, usize) {
    let mut last = BTreeMap::new();
    for (index, command) in commands.iter().enumerate() {
        if let Some(key) = coalescing_key(command) {
            last.insert(key, index);
        }
    }

    let mut output = Vec::with_capacity(commands.len());
    for (index, command) in commands.iter().enumerate() {
        let keep = coalescing_key(command).is_none_or(|key| last.get(&key) == Some(&index));
        if keep {
            output.push(command.clone());
        }
    }
    let coalesced = commands.len().saturating_sub(output.len());
    (output, coalesced)
}

#[cfg(test)]
mod tests {
    use pam_native_protocol::{Layout, PropValue, decode_batch};

    use super::*;

    #[test]
    fn keeps_only_the_last_idempotent_command_without_reordering_structural_work() {
        let commands = vec![
            Mutation::Update {
                id: 4,
                key: PropKey::Text,
                value: Some(PropValue::String("old".into())),
            },
            Mutation::Remove { id: 9 },
            Mutation::Update {
                id: 4,
                key: PropKey::Text,
                value: Some(PropValue::String("new".into())),
            },
            Mutation::Layout {
                id: 4,
                frame: Layout {
                    x: 0.0,
                    y: 0.0,
                    width: 10.0,
                    height: 10.0,
                },
            },
            Mutation::Layout {
                id: 4,
                frame: Layout {
                    x: 1.0,
                    y: 2.0,
                    width: 30.0,
                    height: 40.0,
                },
            },
        ];
        let (coalesced, removed) = coalesce(&commands);
        assert_eq!(removed, 2);
        assert!(matches!(coalesced[0], Mutation::Remove { id: 9 }));
        assert!(matches!(coalesced[1], Mutation::Update { .. }));
        assert!(matches!(coalesced[2], Mutation::Layout { .. }));
    }

    #[test]
    fn arena_reuses_storage_and_emits_a_v1_compatible_batch() {
        let commands = [Mutation::SetRoot { id: 1 }];
        let mut arena = CommandArena::default();
        let first_generation = arena.encode(&commands).expect("first").generation;
        let second = arena.encode(&commands).expect("second");
        assert_eq!(second.generation, first_generation + 1);
        assert_eq!(decode_batch(second.bytes).expect("decode"), commands);
        assert!(arena.reused_bytes() > 0);
    }
}
