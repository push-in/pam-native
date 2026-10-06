//! Presentation surfaces and their safe areas.
//!
//! A `Modal`, `BottomSheet` or sheet-presented navigation route is drawn on
//! its own presentation surface. A `SafeAreaView` inside it must use the
//! insets of that surface, not the insets of the activity/root window: an
//! Android dialog window that fits the system bars already starts below the
//! status bar, so applying the window's top inset again would pad it twice.
//!
//! Every surface resolves, per edge, whether it extends under the system UI
//! of the window (real inset), is laid out between the system bars (the
//! surface viewport excludes the inset and the edge inset is zero), or never
//! reaches that edge (zero inset, unchanged viewport, e.g. a sheet top).

use pam_native_protocol::{Layout, Node, NodeKind, PropKey, PropValue};

/// How the host presents surfaces that create their own window.
#[repr(u32)]
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub enum SurfacePolicy {
    /// Surfaces are views inside the host window (iOS). Full-screen and
    /// dialog modals overlay every system inset; bottom sheets and
    /// page/form-sheet routes never reach the top edge.
    #[default]
    InWindow = 0,
    /// Every `Modal`/`BottomSheet` is its own window (Android `Dialog`) that
    /// fits the system bars unless it is `statusBarTranslucent` or
    /// `navigationBarTranslucent`; then it is edge-to-edge and every edge
    /// extends under the system bars (Android 14 and older).
    SystemWindows = 1,
    /// Every `Modal`/`BottomSheet` is its own edge-to-edge window that
    /// extends under every system bar whatever its translucency (Android 15+
    /// enforced edge-to-edge). Sheets still never reach the top edge.
    EdgeToEdgeWindows = 2,
}

impl SurfacePolicy {
    #[must_use]
    pub const fn from_raw(value: u32) -> Option<Self> {
        match value {
            0 => Some(Self::InWindow),
            1 => Some(Self::SystemWindows),
            2 => Some(Self::EdgeToEdgeWindows),
            _ => None,
        }
    }
}

const PRESENTATION_SHEET: i64 = 3;
const NAVIGATION_MODAL: i64 = 2;
const NAVIGATION_FORM_SHEET: i64 = 7;

/// Layout area of a surface (root coordinates) and the safe-area insets its
/// `SafeAreaView`s receive (`None` when the host does not feed insets).
#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct Surface {
    pub viewport: Layout,
    pub insets: Option<[f32; 4]>,
}

fn flag(node: &Node, key: PropKey) -> bool {
    matches!(node.properties.get(&key), Some(PropValue::Boolean(true)))
}

fn integer(node: &Node, key: PropKey) -> Option<i64> {
    match node.properties.get(&key) {
        Some(PropValue::Integer(value)) => Some(*value),
        _ => None,
    }
}

/// The surface of a `Modal` (any presentation, including `BottomSheet`).
pub(crate) fn modal_surface(
    modal: &Node,
    window: Layout,
    window_insets: Option<[f32; 4]>,
    policy: SurfacePolicy,
) -> Surface {
    let Some(insets) = window_insets else {
        return Surface {
            viewport: window,
            insets: None,
        };
    };
    let sheet = integer(modal, PropKey::ModalPresentation) == Some(PRESENTATION_SHEET);
    let extends_under_system_bars = match policy {
        SurfacePolicy::InWindow | SurfacePolicy::EdgeToEdgeWindows => true,
        SurfacePolicy::SystemWindows => {
            flag(modal, PropKey::ModalStatusBarTranslucent)
                || flag(modal, PropKey::ModalNavigationBarTranslucent)
        }
    };
    if extends_under_system_bars {
        let [left, top, right, bottom] = insets;
        return Surface {
            viewport: window,
            // A sheet rests at the bottom and its snap points resolve below
            // the top inset, so its top edge never reaches the status bar.
            insets: Some([left, if sheet { 0.0 } else { top }, right, bottom]),
        };
    }
    // The window is laid out between the system bars: its content area
    // excludes every inset, and none of its edges is under system UI.
    let [left, top, right, bottom] = insets;
    Surface {
        viewport: Layout {
            x: window.x + left,
            y: window.y + top,
            width: (window.width - left - right).max(0.0),
            height: (window.height - top - bottom).max(0.0),
        },
        insets: Some([0.0; 4]),
    }
}

/// Insets for the active (last) route of a `NavigationHost` whose
/// presentation is a sheet: iOS page/form sheets and Android form sheets
/// start below the status bar, so their top inset is zero. Other edges keep
/// the window insets (applied where the route frame touches them).
pub(crate) fn navigation_route_insets(
    host: &Node,
    window_insets: Option<[f32; 4]>,
    policy: SurfacePolicy,
) -> Option<Option<[f32; 4]>> {
    debug_assert_eq!(host.kind, NodeKind::NavigationHost);
    let insets = window_insets?;
    let presentation = integer(host, PropKey::NavigationPresentation)?;
    let sheet = match policy {
        SurfacePolicy::InWindow => {
            matches!(presentation, NAVIGATION_MODAL | NAVIGATION_FORM_SHEET)
        }
        // Android routes are views inside the activity; only the form sheet
        // rests below the status bar.
        SurfacePolicy::SystemWindows | SurfacePolicy::EdgeToEdgeWindows => {
            presentation == NAVIGATION_FORM_SHEET
        }
    };
    sheet.then_some(Some([insets[0], 0.0, insets[2], insets[3]]))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::BTreeMap;

    fn modal(props: &[(PropKey, PropValue)]) -> Node {
        Node {
            id: 2,
            parent: 1,
            index: 0,
            kind: NodeKind::Modal,
            properties: props.iter().cloned().collect::<BTreeMap<_, _>>(),
        }
    }

    const WINDOW: Layout = Layout {
        x: 0.0,
        y: 0.0,
        width: 400.0,
        height: 800.0,
    };
    const INSETS: [f32; 4] = [0.0, 24.0, 0.0, 48.0];

    #[test]
    fn in_window_full_screen_modal_overlays_every_inset() {
        let surface = modal_surface(&modal(&[]), WINDOW, Some(INSETS), SurfacePolicy::InWindow);
        assert_eq!(surface.viewport, WINDOW);
        assert_eq!(surface.insets, Some(INSETS));
    }

    #[test]
    fn in_window_sheet_never_reaches_the_top_edge() {
        let sheet = modal(&[(PropKey::ModalPresentation, PropValue::Integer(3))]);
        let surface = modal_surface(&sheet, WINDOW, Some(INSETS), SurfacePolicy::InWindow);
        assert_eq!(surface.insets, Some([0.0, 0.0, 0.0, 48.0]));
    }

    #[test]
    fn system_window_modal_fits_the_system_bars_unless_translucent() {
        let fitted = modal_surface(
            &modal(&[]),
            WINDOW,
            Some(INSETS),
            SurfacePolicy::SystemWindows,
        );
        assert_eq!(fitted.insets, Some([0.0; 4]));
        assert_eq!(
            fitted.viewport,
            Layout {
                x: 0.0,
                y: 24.0,
                width: 400.0,
                height: 728.0
            }
        );
        for key in [
            PropKey::ModalStatusBarTranslucent,
            PropKey::ModalNavigationBarTranslucent,
        ] {
            let translucent = modal(&[(key, PropValue::Boolean(true))]);
            let surface = modal_surface(
                &translucent,
                WINDOW,
                Some(INSETS),
                SurfacePolicy::SystemWindows,
            );
            assert_eq!(surface.viewport, WINDOW);
            assert_eq!(surface.insets, Some(INSETS));
        }
    }

    #[test]
    fn enforced_edge_to_edge_windows_overlay_every_inset_whatever_the_translucency() {
        let fitted = modal_surface(
            &modal(&[]),
            WINDOW,
            Some(INSETS),
            SurfacePolicy::EdgeToEdgeWindows,
        );
        assert_eq!(fitted.viewport, WINDOW);
        assert_eq!(fitted.insets, Some(INSETS));
        let sheet = modal(&[(PropKey::ModalPresentation, PropValue::Integer(3))]);
        let surface = modal_surface(
            &sheet,
            WINDOW,
            Some(INSETS),
            SurfacePolicy::EdgeToEdgeWindows,
        );
        assert_eq!(surface.insets, Some([0.0, 0.0, 0.0, 48.0]));
        assert_eq!(
            SurfacePolicy::from_raw(2),
            Some(SurfacePolicy::EdgeToEdgeWindows)
        );
        assert_eq!(SurfacePolicy::from_raw(3), None);
    }

    #[test]
    fn system_window_sheet_bottom_follows_the_window() {
        let fitted = modal(&[(PropKey::ModalPresentation, PropValue::Integer(3))]);
        let surface = modal_surface(&fitted, WINDOW, Some(INSETS), SurfacePolicy::SystemWindows);
        assert_eq!(surface.insets, Some([0.0; 4]));
        assert!((surface.viewport.height - 728.0).abs() < f32::EPSILON);
        let edge_to_edge = modal(&[
            (PropKey::ModalPresentation, PropValue::Integer(3)),
            (
                PropKey::ModalNavigationBarTranslucent,
                PropValue::Boolean(true),
            ),
        ]);
        let surface = modal_surface(
            &edge_to_edge,
            WINDOW,
            Some(INSETS),
            SurfacePolicy::SystemWindows,
        );
        assert_eq!(surface.viewport, WINDOW);
        assert_eq!(surface.insets, Some([0.0, 0.0, 0.0, 48.0]));
    }

    #[test]
    fn hosts_without_engine_insets_keep_the_window_viewport() {
        let surface = modal_surface(&modal(&[]), WINDOW, None, SurfacePolicy::SystemWindows);
        assert_eq!(
            surface,
            Surface {
                viewport: WINDOW,
                insets: None
            }
        );
    }

    #[test]
    fn sheet_routes_drop_only_the_top_inset() {
        let host = |presentation| Node {
            id: 3,
            parent: 1,
            index: 0,
            kind: NodeKind::NavigationHost,
            properties: [(
                PropKey::NavigationPresentation,
                PropValue::Integer(presentation),
            )]
            .into_iter()
            .collect(),
        };
        let sheet = Some(Some([0.0, 0.0, 0.0, 48.0]));
        assert_eq!(
            navigation_route_insets(&host(2), Some(INSETS), SurfacePolicy::InWindow),
            sheet
        );
        assert_eq!(
            navigation_route_insets(&host(7), Some(INSETS), SurfacePolicy::InWindow),
            sheet
        );
        assert_eq!(
            navigation_route_insets(&host(4), Some(INSETS), SurfacePolicy::InWindow),
            None
        );
        assert_eq!(
            navigation_route_insets(&host(2), Some(INSETS), SurfacePolicy::SystemWindows),
            None
        );
        assert_eq!(
            navigation_route_insets(&host(7), Some(INSETS), SurfacePolicy::SystemWindows),
            sheet
        );
        assert_eq!(
            navigation_route_insets(&host(7), None, SurfacePolicy::InWindow),
            None
        );
    }
}
