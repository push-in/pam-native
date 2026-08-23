use std::collections::BTreeMap;

pub type TransactionId = u64;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u8)]
pub enum TransactionPhase {
    Prepared = 1,
    Committed = 2,
    Cancelled = 3,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct RenderTransaction {
    pub id: TransactionId,
    pub generation: u64,
    pub phase: TransactionPhase,
    pub payload: Vec<u8>,
}

#[derive(Debug, Default)]
pub struct TransactionCoordinator {
    generation: u64,
    next_id: TransactionId,
    pending: BTreeMap<TransactionId, RenderTransaction>,
}

impl TransactionCoordinator {
    pub fn prepare(&mut self, payload: Vec<u8>) -> Result<TransactionId, TransactionError> {
        if payload.is_empty() || payload.len() > 16 * 1024 * 1024 {
            return Err(TransactionError::InvalidPayload);
        }
        self.next_id = self.next_id.saturating_add(1).max(1);
        let id = self.next_id;
        self.pending.insert(
            id,
            RenderTransaction {
                id,
                generation: id,
                phase: TransactionPhase::Prepared,
                payload,
            },
        );
        Ok(id)
    }

    pub fn commit(&mut self, id: TransactionId) -> Result<RenderTransaction, TransactionError> {
        let mut transaction = self
            .pending
            .remove(&id)
            .ok_or(TransactionError::UnknownTransaction)?;
        if transaction.generation <= self.generation {
            return Err(TransactionError::ObsoleteTransaction);
        }
        transaction.phase = TransactionPhase::Committed;
        self.generation = transaction.generation;
        self.pending
            .retain(|_, pending| pending.generation > self.generation);
        Ok(transaction)
    }

    pub fn cancel(&mut self, id: TransactionId) -> bool {
        self.pending.remove(&id).is_some()
    }

    #[must_use]
    pub fn generation(&self) -> u64 {
        self.generation
    }

    #[must_use]
    pub fn pending(&self) -> usize {
        self.pending.len()
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum TransactionError {
    InvalidPayload,
    UnknownTransaction,
    ObsoleteTransaction,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn commits_atomically_and_discards_obsolete_work() {
        let mut coordinator = TransactionCoordinator::default();
        let first = coordinator.prepare(vec![1]).expect("first");
        let second = coordinator.prepare(vec![2]).expect("second");
        let committed = coordinator.commit(second).expect("commit latest");
        assert_eq!(committed.payload, vec![2]);
        assert_eq!(committed.phase, TransactionPhase::Committed);
        assert_eq!(coordinator.generation(), 2);
        assert_eq!(coordinator.pending(), 0);
        assert_eq!(
            coordinator.commit(first),
            Err(TransactionError::UnknownTransaction)
        );
    }

    #[test]
    fn bounds_payload_and_supports_cancellation() {
        let mut coordinator = TransactionCoordinator::default();
        assert_eq!(
            coordinator.prepare(Vec::new()),
            Err(TransactionError::InvalidPayload)
        );
        let id = coordinator.prepare(vec![1, 2, 3]).expect("prepare");
        assert!(coordinator.cancel(id));
        assert_eq!(
            coordinator.commit(id),
            Err(TransactionError::UnknownTransaction)
        );
    }
}
