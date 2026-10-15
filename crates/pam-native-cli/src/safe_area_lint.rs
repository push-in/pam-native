//! `pam-native safe-area-lint`: lays out captured render frames with the real
//! engine for one or more inset configurations and fails when text, inputs,
//! buttons, pressables or list ends end inside a system-bar, cutout or
//! home-indicator inset. Meant for CI and app validators.
//!
//! ```text
//! pam-native safe-area-lint <frame>... [--viewport 360x780]
//!     [--insets 0,24,0,48]... [--policy edge-to-edge|system|in-window] [--json]
//!     [--explicit-only]
//! ```
//!
//! `--explicit-only` turns the engine's automatic safe area off and checks
//! that the application clears the insets by itself (renderers before the
//! automatic safe area).
//!
//! Frames are applied in order (a full tree, then optional patches). Each
//! `--insets` (left,top,right,bottom in points) is checked; the default pair
//! is a three-button navigation bar (48) and gesture navigation (24) under a
//! 24 pt status bar.

use std::ffi::OsString;
use std::fmt::Write as _;

use pam_native_engine::{Engine, SafeAreaViolation, SurfacePolicy};

struct Options {
    frames: Vec<OsString>,
    width: f32,
    height: f32,
    insets: Vec<[f32; 4]>,
    policy: SurfacePolicy,
    json: bool,
    explicit_only: bool,
}

fn parse(arguments: impl Iterator<Item = OsString>) -> Result<Options, String> {
    let mut options = Options {
        frames: Vec::new(),
        width: 360.0,
        height: 780.0,
        insets: Vec::new(),
        policy: SurfacePolicy::EdgeToEdgeWindows,
        json: false,
        explicit_only: false,
    };
    let mut arguments = arguments.peekable();
    while let Some(argument) = arguments.next() {
        let text = argument.to_string_lossy().into_owned();
        let mut value = |name: &str| {
            arguments
                .next()
                .map(|value| value.to_string_lossy().into_owned())
                .ok_or_else(|| format!("{name} requires a value"))
        };
        match text.as_str() {
            "--viewport" => {
                let raw = value("--viewport")?;
                let (width, height) = raw
                    .split_once('x')
                    .ok_or("--viewport expects WIDTHxHEIGHT")?;
                options.width = width.parse().map_err(|_| "invalid viewport width")?;
                options.height = height.parse().map_err(|_| "invalid viewport height")?;
            }
            "--insets" => {
                let raw = value("--insets")?;
                let parts = raw
                    .split(',')
                    .map(|part| part.trim().parse::<f32>())
                    .collect::<Result<Vec<_>, _>>()
                    .map_err(|_| "--insets expects left,top,right,bottom")?;
                let [left, top, right, bottom] = parts[..] else {
                    return Err("--insets expects four values".into());
                };
                options.insets.push([left, top, right, bottom]);
            }
            "--policy" => {
                options.policy = match value("--policy")?.as_str() {
                    "edge-to-edge" => SurfacePolicy::EdgeToEdgeWindows,
                    "system" => SurfacePolicy::SystemWindows,
                    "in-window" => SurfacePolicy::InWindow,
                    other => return Err(format!("unknown --policy {other:?}")),
                }
            }
            "--json" => options.json = true,
            "--explicit-only" => options.explicit_only = true,
            flag if flag.starts_with("--") => return Err(format!("unknown option {flag}")),
            _ => options.frames.push(argument),
        }
    }
    if options.frames.is_empty() {
        return Err("safe-area-lint requires at least one frame file".into());
    }
    if options.insets.is_empty() {
        options.insets = vec![[0.0, 24.0, 0.0, 48.0], [0.0, 24.0, 0.0, 24.0]];
    }
    Ok(options)
}

/// Violations of the frames under one inset configuration.
pub fn lint(
    frames: &[Vec<u8>],
    width: f32,
    height: f32,
    insets: [f32; 4],
    policy: SurfacePolicy,
    explicit_only: bool,
) -> Result<Vec<SafeAreaViolation>, String> {
    let mut engine = Engine::new();
    engine.set_automatic_safe_area(!explicit_only);
    engine
        .set_viewport(width, height)
        .map_err(|error| format!("{error:?}"))?;
    engine.set_surface_policy(policy);
    engine
        .set_safe_area_insets(insets[0], insets[1], insets[2], insets[3])
        .map_err(|error| format!("{error:?}"))?;
    for frame in frames {
        engine
            .commit(frame)
            .map_err(|error| format!("frame rejected: {error:?}"))?;
    }
    Ok(engine.safe_area_violations())
}

fn escape(value: &str) -> String {
    let mut result = String::with_capacity(value.len());
    for character in value.chars() {
        match character {
            '"' => result.push_str("\\\""),
            '\\' => result.push_str("\\\\"),
            control if control.is_control() => {
                let _ = write!(result, "\\u{:04x}", u32::from(control));
            }
            other => result.push(other),
        }
    }
    result
}

pub fn run(arguments: impl Iterator<Item = OsString>) -> Result<u8, String> {
    let options = parse(arguments)?;
    let frames = options
        .frames
        .iter()
        .map(|path| {
            std::fs::read(path).map_err(|error| format!("{}: {error}", path.to_string_lossy()))
        })
        .collect::<Result<Vec<_>, _>>()?;
    let mut failed = false;
    let mut json = String::from("[");
    for insets in &options.insets {
        let violations = lint(
            &frames,
            options.width,
            options.height,
            *insets,
            options.policy,
            options.explicit_only,
        )?;
        failed |= !violations.is_empty();
        if options.json {
            if json.len() > 1 {
                json.push(',');
            }
            let _ = write!(
                json,
                "{{\"insets\":[{},{},{},{}],\"violations\":[",
                insets[0], insets[1], insets[2], insets[3]
            );
            for (index, violation) in violations.iter().enumerate() {
                if index > 0 {
                    json.push(',');
                }
                let _ = write!(
                    json,
                    "{{\"id\":{},\"kind\":\"{:?}\",\"content\":\"{:?}\",\"edge\":\"{}\",\"intrusion\":{:.1},\"testId\":{},\"label\":{}}}",
                    violation.id,
                    violation.node_kind,
                    violation.kind,
                    violation.edge_name(),
                    violation.intrusion,
                    violation
                        .test_id
                        .as_deref()
                        .map_or("null".to_string(), |value| format!("\"{}\"", escape(value))),
                    violation
                        .label
                        .as_deref()
                        .map_or("null".to_string(), |value| format!("\"{}\"", escape(value))),
                );
            }
            json.push_str("]}");
        } else {
            for violation in &violations {
                println!(
                    "insets {:?}: node {} ({:?}{}{}) ends {:.1} pt inside the {} inset",
                    insets,
                    violation.id,
                    violation.node_kind,
                    violation
                        .test_id
                        .as_deref()
                        .map(|value| format!(" testId={value}"))
                        .unwrap_or_default(),
                    violation
                        .label
                        .as_deref()
                        .map(|value| format!(" \"{value}\""))
                        .unwrap_or_default(),
                    violation.intrusion,
                    violation.edge_name(),
                );
            }
        }
    }
    if options.json {
        json.push(']');
        println!("{json}");
    } else if !failed {
        println!("safe-area-lint: no content inside the system-bar insets");
    }
    Ok(u8::from(failed))
}

#[cfg(test)]
mod tests {
    use std::collections::BTreeMap;

    use pam_native_protocol::{Node, NodeKind, PropKey, PropValue, Tree};

    use super::*;

    /// A screen whose button sits at the very bottom of the window.
    fn frame() -> Vec<u8> {
        let node = |id, parent, index, kind, properties: Vec<(PropKey, PropValue)>| {
            (
                id,
                Node {
                    id,
                    parent,
                    index,
                    kind,
                    properties: properties.into_iter().collect(),
                },
            )
        };
        Tree {
            root: 1,
            nodes: BTreeMap::from([
                node(
                    1,
                    0,
                    0,
                    NodeKind::Column,
                    vec![(PropKey::FlexGrow, PropValue::Float(1.0))],
                ),
                node(
                    2,
                    1,
                    0,
                    NodeKind::View,
                    vec![(PropKey::FlexGrow, PropValue::Float(1.0))],
                ),
                node(
                    3,
                    1,
                    1,
                    NodeKind::Button,
                    vec![
                        (PropKey::Height, PropValue::Float(48.0)),
                        (PropKey::TestId, PropValue::String("send".into())),
                    ],
                ),
            ]),
        }
        .encode()
        .expect("frame")
    }

    #[test]
    fn explicit_only_reports_the_button_behind_the_bar_and_the_engine_clears_it() {
        let frames = vec![frame()];
        for insets in [[0.0, 24.0, 0.0, 48.0], [0.0, 24.0, 0.0, 24.0]] {
            let explicit = lint(
                &frames,
                360.0,
                780.0,
                insets,
                SurfacePolicy::EdgeToEdgeWindows,
                true,
            )
            .expect("lint");
            assert_eq!(explicit.len(), 1);
            assert_eq!(explicit[0].test_id.as_deref(), Some("send"));
            assert_eq!(explicit[0].edge_name(), "bottom");
            assert!((explicit[0].intrusion - insets[3]).abs() < 0.01);
            let automatic = lint(
                &frames,
                360.0,
                780.0,
                insets,
                SurfacePolicy::EdgeToEdgeWindows,
                false,
            )
            .expect("lint");
            assert!(automatic.is_empty(), "{automatic:?}");
        }
    }

    #[test]
    fn options_parse_insets_viewport_and_modes() {
        let options = parse(
            [
                "frame.bin",
                "--viewport",
                "412x915",
                "--insets",
                "0,32,0,48",
                "--explicit-only",
            ]
            .into_iter()
            .map(OsString::from),
        )
        .expect("options");
        assert_eq!((options.width, options.height), (412.0, 915.0));
        assert_eq!(options.insets, vec![[0.0, 32.0, 0.0, 48.0]]);
        assert!(options.explicit_only);
        let defaults = parse(["frame.bin"].into_iter().map(OsString::from)).expect("defaults");
        assert_eq!(defaults.insets.len(), 2);
        assert!(parse(std::iter::empty()).is_err());
    }
}
