use std::collections::{BTreeMap, BTreeSet};

use pam_native_protocol::Tree;

pub type FiberId = u64;

#[derive(Clone, Copy, Debug, Eq, Ord, PartialEq, PartialOrd)]
#[repr(u8)]
pub enum FiberLane {
    Input = 1,
    Animation = 2,
    VisibleRender = 3,
    Navigation = 4,
    Prefetch = 5,
    Background = 6,
    Idle = 7,
}

impl FiberLane {
    const fn mask(self) -> u8 {
        1 << (self as u8 - 1)
    }
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct FiberWorkBatch {
    pub nodes: Vec<FiberId>,
    pub remaining: usize,
    pub yielded: bool,
}

#[derive(Clone, Debug, Default)]
pub struct FiberRoot {
    root: FiberId,
    parents: BTreeMap<FiberId, FiberId>,
    children: BTreeMap<FiberId, Vec<FiberId>>,
    scheduled: BTreeMap<FiberId, u8>,
    pending: BTreeMap<FiberId, u8>,
}

impl FiberRoot {
    #[must_use]
    pub fn from_tree(tree: &Tree) -> Self {
        let mut root = Self {
            root: tree.root,
            ..Self::default()
        };
        for node in tree.nodes.values() {
            root.parents.insert(node.id, node.parent);
            root.children.entry(node.parent).or_default().push(node.id);
            root.scheduled.insert(node.id, 0);
            root.pending.insert(node.id, 0);
        }
        for children in root.children.values_mut() {
            children.sort_unstable();
        }
        root
    }

    pub fn schedule(&mut self, id: FiberId, lane: FiberLane) -> bool {
        if !self.pending.contains_key(&id) {
            return false;
        }
        let mask = lane.mask();
        *self.scheduled.get_mut(&id).expect("known fiber") |= mask;
        let mut cursor = id;
        loop {
            *self.pending.get_mut(&cursor).expect("known fiber") |= mask;
            let parent = self.parents.get(&cursor).copied().unwrap_or(0);
            if parent == 0 || !self.pending.contains_key(&parent) {
                break;
            }
            cursor = parent;
        }
        true
    }

    #[must_use]
    pub fn take(&self, lane: FiberLane, maximum_nodes: usize) -> FiberWorkBatch {
        if maximum_nodes == 0 || self.root == 0 {
            return FiberWorkBatch {
                remaining: self.pending_count(lane),
                yielded: self.pending_count(lane) > 0,
                ..FiberWorkBatch::default()
            };
        }
        let mask = lane.mask();
        let mut stack = vec![self.root];
        let mut nodes = Vec::with_capacity(maximum_nodes.min(self.pending.len()));
        while let Some(id) = stack.pop() {
            if self.pending.get(&id).copied().unwrap_or(0) & mask == 0 {
                continue;
            }
            if self.scheduled.get(&id).copied().unwrap_or(0) & mask != 0 {
                nodes.push(id);
                if nodes.len() == maximum_nodes {
                    break;
                }
            }
            if let Some(children) = self.children.get(&id) {
                stack.extend(children.iter().rev().copied());
            }
        }
        let remaining = self.pending_count(lane).saturating_sub(nodes.len());
        FiberWorkBatch {
            yielded: remaining > 0,
            remaining,
            nodes,
        }
    }

    pub fn complete(&mut self, ids: &[FiberId], lane: FiberLane) {
        let mask = lane.mask();
        let mut ancestors = BTreeSet::new();
        for id in ids {
            if let Some(scheduled) = self.scheduled.get_mut(id) {
                *scheduled &= !mask;
                if let Some(pending) = self.pending.get_mut(id) {
                    *pending &= !mask;
                }
                let mut parent = self.parents.get(id).copied().unwrap_or(0);
                while parent != 0 {
                    ancestors.insert(parent);
                    parent = self.parents.get(&parent).copied().unwrap_or(0);
                }
            }
        }
        for ancestor in ancestors.into_iter().rev() {
            let own = self.scheduled.get(&ancestor).copied().unwrap_or(0) & mask;
            let child_pending = self
                .children
                .get(&ancestor)
                .into_iter()
                .flatten()
                .any(|child| self.pending.get(child).copied().unwrap_or(0) & mask != 0);
            if let Some(pending) = self.pending.get_mut(&ancestor) {
                if own != 0 || child_pending {
                    *pending |= mask;
                } else {
                    *pending &= !mask;
                }
            }
        }
    }

    #[must_use]
    pub fn pending_count(&self, lane: FiberLane) -> usize {
        let mask = lane.mask();
        self.scheduled
            .values()
            .filter(|pending| **pending & mask != 0)
            .count()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use pam_native_protocol::{Node, NodeKind};

    #[test]
    fn lanes_bubble_and_yield_in_stable_tree_order() {
        let tree = Tree {
            root: 1,
            nodes: BTreeMap::from([
                (
                    1,
                    Node {
                        id: 1,
                        parent: 0,
                        index: 0,
                        kind: NodeKind::Screen,
                        properties: BTreeMap::new(),
                    },
                ),
                (
                    2,
                    Node {
                        id: 2,
                        parent: 1,
                        index: 0,
                        kind: NodeKind::Text,
                        properties: BTreeMap::new(),
                    },
                ),
                (
                    3,
                    Node {
                        id: 3,
                        parent: 1,
                        index: 1,
                        kind: NodeKind::Text,
                        properties: BTreeMap::new(),
                    },
                ),
            ]),
        };
        let mut fibers = FiberRoot::from_tree(&tree);
        assert!(fibers.schedule(3, FiberLane::VisibleRender));
        assert!(fibers.schedule(2, FiberLane::VisibleRender));
        let first = fibers.take(FiberLane::VisibleRender, 1);
        assert_eq!(first.nodes, vec![2]);
        assert!(first.yielded);
        fibers.complete(&first.nodes, FiberLane::VisibleRender);
        let second = fibers.take(FiberLane::VisibleRender, 1);
        assert_eq!(second.nodes, vec![3]);
        fibers.complete(&second.nodes, FiberLane::VisibleRender);
        assert_eq!(fibers.pending_count(FiberLane::VisibleRender), 0);
        assert_eq!(FiberLane::Input as u8, 1);
        assert_eq!(FiberLane::Idle as u8, 7);
    }
}
