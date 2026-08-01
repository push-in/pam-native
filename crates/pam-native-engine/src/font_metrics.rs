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
    fonts: BTreeMap<String, CachedFont>,
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
        }
    }

    pub(crate) fn measure_tree(&mut self, tree: &Tree) -> TextMetrics {
        let mut node_fonts = BTreeMap::<u64, String>::new();
        let mut required_characters = BTreeMap::<String, BTreeSet<char>>::new();
        for node in tree
            .nodes
            .values()
            .filter(|node| node.kind == NodeKind::Text)
        {
            let Some(PropValue::String(family)) = node.properties.get(&PropKey::FontFamily) else {
                continue;
            };
            let Some(PropValue::String(text)) = node.properties.get(&PropKey::Text) else {
                continue;
            };
            node_fonts.insert(node.id, family.clone());
            let characters = required_characters.entry(family.clone()).or_default();
            for character in text.chars() {
                characters.insert(character);
                characters.extend(character.to_lowercase());
                characters.extend(character.to_uppercase());
            }
        }

        let mut snapshots = BTreeMap::<String, GlyphAdvances>::new();
        for (family, characters) in required_characters {
            let Some(path) = self.resolve_asset_font(&family) else {
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
            let Ok(face) = ttf_parser::Face::parse(&cached.bytes, 0) else {
                continue;
            };
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

        node_fonts
            .into_iter()
            .filter_map(|(node, family)| snapshots.get(&family).cloned().map(|font| (node, font)))
            .collect()
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
}
