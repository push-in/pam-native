//! Prints engine frames for a captured render frame.
//! usage: layout_dump <frame> <width> <height> [left top right bottom]
use std::{collections::BTreeMap, env, fs};

use pam_native_engine::Engine;
use pam_native_protocol::{Mutation, Tree, decode_batch};

fn main() {
    let args = env::args().collect::<Vec<_>>();
    let frame = fs::read(&args[1]).expect("frame");
    let width = args[2].parse::<f32>().unwrap();
    let height = args[3].parse::<f32>().unwrap();
    let mut engine = Engine::new();
    engine.set_viewport(width, height).unwrap();
    if args.len() >= 8 {
        let inset = |index: usize| args[index].parse::<f32>().unwrap();
        engine
            .set_safe_area_insets(inset(4), inset(5), inset(6), inset(7))
            .unwrap();
    }
    let tree = Tree::decode(&frame).expect("tree");
    let batch = engine.commit(&frame).expect("commit");
    let mut layouts = BTreeMap::new();
    for mutation in decode_batch(&batch).expect("batch") {
        if let Mutation::Layout { id, frame } = mutation {
            layouts.insert(id, frame);
        }
    }
    fn walk(
        tree: &Tree,
        layouts: &BTreeMap<u64, pam_native_protocol::Layout>,
        id: u64,
        depth: usize,
        children: &BTreeMap<u64, Vec<u64>>,
    ) {
        let node = &tree.nodes[&id];
        let frame = layouts.get(&id);
        let text = match node.properties.get(&pam_native_protocol::PropKey::Text) {
            Some(pam_native_protocol::PropValue::String(value)) => {
                value.chars().take(24).collect::<String>()
            }
            _ => String::new(),
        };
        if depth < 14 {
            println!(
                "{}{} {:?} {:?} {}",
                "  ".repeat(depth),
                id,
                node.kind,
                frame.map(|f| (f.x, f.y, f.width, f.height)),
                text
            );
        }
        for child in children.get(&id).map_or(&[][..], Vec::as_slice) {
            walk(tree, layouts, *child, depth + 1, children);
        }
    }
    let mut children = BTreeMap::<u64, Vec<u64>>::new();
    let mut nodes = tree.nodes.values().collect::<Vec<_>>();
    nodes.sort_by_key(|node| (node.parent, node.index));
    for node in nodes {
        children.entry(node.parent).or_default().push(node.id);
    }
    walk(&tree, &layouts, tree.root, 0, &children);
}
