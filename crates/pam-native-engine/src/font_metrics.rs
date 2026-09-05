use std::collections::{BTreeMap, BTreeSet};
use std::fs;
use std::path::{Component, Path, PathBuf};
use std::sync::Arc;

use pam_native_protocol::{NodeKind, PropKey, PropValue, Tree};

pub(crate) type GlyphAdvances = Arc<BTreeMap<char, f32>>;
pub(crate) type TextMetrics = BTreeMap<u64, GlyphAdvances>;

#[derive(Debug, Default)]
pub(crate) struct FontMetricsCache {
    asset_root: Option<PathBuf>,
    fonts: BTreeMap<FontInstance, CachedFont>,
    node_fonts: BTreeMap<u64, FontInstance>,
    metrics: TextMetrics,
}

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
struct FontInstance {
    family: String,
    weight: u16,
}

#[derive(Debug)]
struct CachedFont {
    bytes: Vec<u8>,
    advances: BTreeMap<char, f32>,
    snapshot: GlyphAdvances,
}

impl FontMetricsCache {
    pub(crate) fn set_asset_root(&mut self, asset_root: impl Into<PathBuf>) {
        let asset_root = asset_root.into();
        if self.asset_root.as_ref() != Some(&asset_root) {
            self.asset_root = Some(asset_root);
            self.fonts.clear();
            self.node_fonts.clear();
            self.metrics.clear();
        }
    }

    pub(crate) fn measure_tree(&mut self, tree: &Tree) -> &TextMetrics {
        self.node_fonts.clear();
        self.metrics.clear();
        let ids = tree.nodes.keys().copied().collect::<Vec<_>>();
        self.measure_nodes(tree, &ids)
    }

    pub(crate) fn measure_nodes(&mut self, tree: &Tree, ids: &[u64]) -> &TextMetrics {
        let mut required_characters = BTreeMap::<FontInstance, BTreeSet<char>>::new();
        let mut changed_nodes = Vec::new();
        for id in ids {
            self.node_fonts.remove(id);
            self.metrics.remove(id);
            let Some(node) = tree
                .nodes
                .get(id)
                .filter(|node| node.kind == NodeKind::Text)
            else {
                continue;
            };
            let Some(PropValue::String(family)) = node.properties.get(&PropKey::FontFamily) else {
                continue;
            };
            let Some(PropValue::String(text)) = node.properties.get(&PropKey::Text) else {
                continue;
            };
            let weight = node
                .properties
                .get(&PropKey::FontWeight)
                .and_then(PropValue::as_number)
                .unwrap_or(400.0)
                .clamp(1.0, 1000.0) as u16;
            let family = FontInstance {
                family: family.clone(),
                weight,
            };
            self.node_fonts.insert(node.id, family.clone());
            changed_nodes.push(node.id);
            let characters = required_characters.entry(family.clone()).or_default();
            for character in text.chars() {
                characters.insert(character);
                characters.extend(character.to_lowercase());
                characters.extend(character.to_uppercase());
            }
        }

        let mut snapshots = BTreeMap::<FontInstance, GlyphAdvances>::new();
        for (family, characters) in required_characters {
            let Some(path) = self.resolve_asset_font(&family.family) else {
                continue;
            };
            let cached = self
                .fonts
                .entry(family.clone())
                .or_insert_with(|| CachedFont {
                    bytes: fs::read(path).unwrap_or_default(),
                    advances: BTreeMap::new(),
                    snapshot: Arc::new(BTreeMap::new()),
                });
            if cached.bytes.is_empty() {
                continue;
            }
            let Ok(mut face) = ttf_parser::Face::parse(&cached.bytes, 0) else {
                continue;
            };
            face.set_variation(
                ttf_parser::Tag::from_bytes(b"wght"),
                f32::from(family.weight),
            );
            let units_per_em = f32::from(face.units_per_em());
            let mut changed = false;
            for character in characters {
                if cached.advances.contains_key(&character) {
                    continue;
                }
                let advance = {
                    face.glyph_index(character)
                        .and_then(|glyph| face.glyph_hor_advance(glyph))
                        .map_or(0.0, |advance| f32::from(advance) / units_per_em)
                };
                cached.advances.insert(character, advance);
                changed = true;
            }
            if changed {
                cached.snapshot = Arc::new(cached.advances.clone());
            }
            snapshots.insert(family, Arc::clone(&cached.snapshot));
        }

        for node in changed_nodes {
            let Some(family) = self.node_fonts.get(&node) else {
                continue;
            };
            if let Some(font) = snapshots.get(family).cloned() {
                self.metrics.insert(node, font);
            }
        }
        &self.metrics
    }

    fn resolve_asset_font(&self, family: &str) -> Option<PathBuf> {
        let relative = family.strip_prefix("asset://")?;
        let relative = Path::new(relative);
        if relative.is_absolute()
            || relative
                .components()
                .any(|component| !matches!(component, Component::Normal(_) | Component::CurDir))
        {
            return None;
        }
        Some(self.asset_root.as_ref()?.join(relative))
    }
}

#[cfg(test)]
mod tests {
    use super::FontMetricsCache;
    use pam_native_protocol::{Node, NodeKind, PropKey, PropValue, Tree};
    use std::collections::BTreeMap;
    use std::path::PathBuf;

    #[test]
    fn rejects_asset_paths_that_escape_the_application_root() {
        let mut cache = FontMetricsCache::default();
        cache.set_asset_root(PathBuf::from("/tmp/pam-app"));

        assert!(
            cache
                .resolve_asset_font("asset://assets/font.ttf")
                .is_some()
        );
        assert!(
            cache
                .resolve_asset_font("asset://../private/font.ttf")
                .is_none()
        );
        assert!(cache.resolve_asset_font("/private/font.ttf").is_none());
    }

    #[test]
    fn incremental_measurement_only_reindexes_dirty_text_nodes() {
        let text = |id, value: &str| Node {
            id,
            parent: 1,
            index: id as u32,
            kind: NodeKind::Text,
            properties: BTreeMap::from([
                (
                    PropKey::FontFamily,
                    PropValue::String("asset://fonts/app.ttf".to_owned()),
                ),
                (PropKey::Text, PropValue::String(value.to_owned())),
            ]),
        };
        let mut tree = Tree {
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
                (2, text(2, "first")),
                (3, text(3, "second")),
            ]),
        };
        let mut cache = FontMetricsCache::default();
        cache.measure_tree(&tree);
        assert_eq!(cache.node_fonts.len(), 2);

        tree.nodes
            .get_mut(&2)
            .expect("text node")
            .properties
            .remove(&PropKey::FontFamily);
        cache.measure_nodes(&tree, &[2]);

        assert!(!cache.node_fonts.contains_key(&2));
        assert!(cache.node_fonts.contains_key(&3));
    }
    #[test]
    fn variable_weight_updates_intrinsic_width_without_changing_font_family() {
        let mut cache = FontMetricsCache::default();
        cache.set_asset_root(PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures"));
        let mut tree = Tree {
            root: 1,
            nodes: BTreeMap::from([(
                1,
                Node {
                    id: 1,
                    parent: 0,
                    index: 0,
                    kind: NodeKind::Text,
                    properties: BTreeMap::from([
                        (
                            PropKey::FontFamily,
                            PropValue::String("asset://fonts/Inter.ttf".into()),
                        ),
                        (PropKey::Text, PropValue::String("linkinpay".into())),
                        (PropKey::FontWeight, PropValue::Integer(400)),
                    ]),
                },
            )]),
        };
        let width = |metrics: &super::TextMetrics| -> f32 {
            "linkinpay".chars().map(|c| metrics[&1][&c]).sum()
        };
        // Shared fixture values asserted by the Android instrumentation test.
        for (weight, expected) in [
            (400, 268.34375),
            (500, 273.875),
            (600, 279.34375),
            (700, 284.875),
        ] {
            tree.nodes
                .get_mut(&1)
                .unwrap()
                .properties
                .insert(PropKey::FontWeight, PropValue::Integer(weight));
            assert_eq!(width(cache.measure_tree(&tree)) * 64.0, expected);
        }
        tree.nodes
            .get_mut(&1)
            .unwrap()
            .properties
            .insert(PropKey::FontWeight, PropValue::Integer(400));
        let regular = width(cache.measure_tree(&tree));
        tree.nodes
            .get_mut(&1)
            .unwrap()
            .properties
            .insert(PropKey::FontWeight, PropValue::Integer(700));
        let bold = width(cache.measure_nodes(&tree, &[1]));
        assert!(
            bold > regular,
            "bold {bold} must be measured separately from regular {regular}"
        );
        tree.nodes
            .get_mut(&1)
            .unwrap()
            .properties
            .insert(PropKey::FontWeight, PropValue::Integer(400));
        assert_eq!(width(cache.measure_nodes(&tree, &[1])), regular);
    }
    #[test]
    fn automatic_row_layout_reflows_variable_font_after_weight_and_scale_changes() {
        let mut cache = FontMetricsCache::default();
        cache.set_asset_root(PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures"));
        let mut tree = Tree {
            root: 1,
            nodes: BTreeMap::from([
                (
                    1,
                    Node {
                        id: 1,
                        parent: 0,
                        index: 0,
                        kind: NodeKind::Row,
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
                        properties: BTreeMap::from([
                            (
                                PropKey::FontFamily,
                                PropValue::String("asset://fonts/Inter.ttf".into()),
                            ),
                            (PropKey::Text, PropValue::String("linkinpay".into())),
                            (PropKey::FontSize, PropValue::Integer(22)),
                            (PropKey::FontWeight, PropValue::Integer(400)),
                        ]),
                    },
                ),
            ]),
        };
        let mut widths = Vec::new();
        for (weight, scale) in [(400, 1.0), (700, 1.0), (700, 1.5)] {
            tree.nodes
                .get_mut(&2)
                .unwrap()
                .properties
                .insert(PropKey::FontWeight, PropValue::Integer(weight));
            let layouts = crate::layout::calculate_with_text_metrics(
                &tree,
                crate::layout::Size {
                    width: 384.0,
                    height: 800.0,
                },
                scale,
                cache.measure_nodes(&tree, &[2]),
            )
            .unwrap();
            widths.push(layouts[&2].width);
        }
        assert!(
            widths[1] > widths[0],
            "weight change must resize the automatic box: {widths:?}"
        );
        assert!(
            widths[2] > widths[1],
            "accessibility scaling must resize the automatic box: {widths:?}"
        );
        tree.nodes
            .get_mut(&1)
            .unwrap()
            .properties
            .insert(PropKey::AlignItems, PropValue::Integer(1));
        let available = widths[0] + (widths[1] - widths[0]) * 0.25;
        tree.nodes
            .get_mut(&2)
            .unwrap()
            .properties
            .insert(PropKey::Width, PropValue::Float(f64::from(available)));
        let mut heights = Vec::new();
        for weight in [400, 700] {
            tree.nodes
                .get_mut(&2)
                .unwrap()
                .properties
                .insert(PropKey::FontWeight, PropValue::Integer(weight));
            let layouts = crate::layout::calculate_with_text_metrics(
                &tree,
                crate::layout::Size {
                    width: 384.0,
                    height: 800.0,
                },
                1.0,
                cache.measure_nodes(&tree, &[2]),
            )
            .unwrap();
            heights.push(layouts[&2].height);
        }
        assert!(
            heights[1] > heights[0],
            "heavier text must wrap and grow vertically in a constrained box: {heights:?}"
        );
    }
}
