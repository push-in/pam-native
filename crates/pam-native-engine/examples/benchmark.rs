use std::collections::BTreeMap;
use std::env;
use std::hint::black_box;
use std::process::ExitCode;
use std::time::Instant;

use pam_native_engine::Engine;
use pam_native_protocol::{
    Node, NodeKind, Patch, PatchOperation, PropKey, PropValue, PropertyPatch, Tree,
};

const NODE_COUNT: u64 = 250;
const ITERATIONS: u64 = 2_000;

fn frame(label: &str) -> Vec<u8> {
    let mut nodes = BTreeMap::from([(
        1,
        Node {
            id: 1,
            parent: 0,
            index: 0,
            kind: NodeKind::Screen,
            properties: BTreeMap::new(),
        },
    )]);
    for id in 2..=NODE_COUNT {
        nodes.insert(
            id,
            Node {
                id,
                parent: 1,
                index: (id - 2) as u32,
                kind: NodeKind::Text,
                properties: BTreeMap::from([(
                    PropKey::Text,
                    PropValue::String(if id == 2 {
                        label.to_owned()
                    } else {
                        format!("Static row {id}")
                    }),
                )]),
            },
        );
    }
    Tree { root: 1, nodes }
        .encode()
        .expect("valid benchmark tree")
}

const DEFAULT_FULL_TREE_BUDGET_NS: u128 = 1_000_000;
const DEFAULT_PATCH_BUDGET_NS: u128 = 500_000;

fn budget(name: &str, fallback: u128) -> u128 {
    env::var(name)
        .ok()
        .and_then(|value| value.parse().ok())
        .unwrap_or(fallback)
}

fn main() -> ExitCode {
    let frames = [frame("A"), frame("B")];
    let mut engine = Engine::new();
    black_box(engine.commit(&frames[0]).expect("warm-up"));

    let started = Instant::now();
    let mut output_bytes = 0_usize;
    for iteration in 0..ITERATIONS {
        let output = engine
            .commit(black_box(&frames[(iteration & 1) as usize]))
            .expect("benchmark commit");
        output_bytes = output_bytes.saturating_add(black_box(output.len()));
    }
    let elapsed = started.elapsed();
    let full_tree_ns = elapsed.as_nanos() / u128::from(ITERATIONS);
    println!(
        "full-tree: nodes={NODE_COUNT} iterations={ITERATIONS} total_ms={:.3} ns_per_commit={} output_bytes={output_bytes}",
        elapsed.as_secs_f64() * 1_000.0,
        full_tree_ns,
    );

    let patches = ["A", "B"].map(|label| {
        Patch {
            operations: vec![PatchOperation::Update(PropertyPatch {
                id: 2,
                key: PropKey::Text,
                value: Some(PropValue::String(label.to_owned())),
            })],
        }
        .encode()
        .expect("valid patch")
    });
    let mut engine = Engine::new();
    black_box(engine.commit(&frames[0]).expect("patch warm-up"));
    let started = Instant::now();
    let mut output_bytes = 0_usize;
    for iteration in 0..ITERATIONS {
        let output = engine
            .commit(black_box(&patches[(iteration & 1) as usize]))
            .expect("patch benchmark commit");
        output_bytes = output_bytes.saturating_add(black_box(output.len()));
    }
    let elapsed = started.elapsed();
    let patch_ns = elapsed.as_nanos() / u128::from(ITERATIONS);
    println!(
        "property-patch: nodes={NODE_COUNT} iterations={ITERATIONS} total_ms={:.3} ns_per_commit={} output_bytes={output_bytes}",
        elapsed.as_secs_f64() * 1_000.0,
        patch_ns,
    );

    if env::args().any(|argument| argument == "--check") {
        let full_tree_budget = budget("PAM_PERF_ENGINE_FULL_TREE_NS", DEFAULT_FULL_TREE_BUDGET_NS);
        let patch_budget = budget("PAM_PERF_ENGINE_PATCH_NS", DEFAULT_PATCH_BUDGET_NS);
        let mut failures = Vec::new();
        if full_tree_ns > full_tree_budget {
            failures.push(format!(
                "full-tree {full_tree_ns}ns exceeds {full_tree_budget}ns",
            ));
        }
        if patch_ns > patch_budget {
            failures.push(format!(
                "property-patch {patch_ns}ns exceeds {patch_budget}ns",
            ));
        }
        if !failures.is_empty() {
            eprintln!(
                "PAM engine performance contract failed: {}",
                failures.join(", ")
            );
            return ExitCode::FAILURE;
        }
        println!(
            "performance-contract: passed full_tree_budget_ns={full_tree_budget} patch_budget_ns={patch_budget}",
        );
    }
    ExitCode::SUCCESS
}
