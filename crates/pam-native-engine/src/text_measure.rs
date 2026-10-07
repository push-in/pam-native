//! Host text measurement.
//!
//! React Native measures text with the platform's own layout engine
//! (`StaticLayout` on Android) from inside Yoga's measure callbacks. PAM keeps
//! flexbox in Rust, but a host may install the same kind of callback so text
//! boxes are sized by the exact shaper, font fallback, hinting, line breaker
//! and font-padding rules that later draw them. Without a host measurer the
//! engine keeps its portable glyph-advance estimator.

use std::cell::{Cell, RefCell};
use std::collections::hash_map::DefaultHasher;
use std::collections::{BTreeMap, HashMap};
use std::ffi::c_void;
use std::hash::{Hash, Hasher};

use pam_native_protocol::{Node, PropKey, PropValue};

/// One text box measurement request. All pointers stay valid only for the
/// duration of the callback. Lengths are in bytes; strings are UTF-8.
#[repr(C)]
#[derive(Debug)]
pub struct PamTextMeasureRequest {
    pub node_id: u64,
    pub text: *const u8,
    pub text_length: usize,
    pub spans: *const u8,
    pub spans_length: usize,
    pub font_family: *const u8,
    pub font_family_length: usize,
    pub font_features: *const u8,
    pub font_features_length: usize,
    /// Logical font size before accessibility scaling.
    pub font_size: f32,
    /// Effective accessibility scale already clamped by the node's
    /// `allowFontScaling` / `maxFontSizeMultiplier` contract.
    pub font_scale: f32,
    /// Logical letter spacing in points (0 when unset).
    pub letter_spacing: f32,
    /// Logical line height in points (0 when unset).
    pub line_height: f32,
    /// Available content width in points; non-finite or <= 0 means unbounded.
    pub available_width: f32,
    pub font_weight: u16,
    pub italic: u8,
    pub include_font_padding: u8,
    pub text_transform: u8,
    pub break_strategy: u8,
    pub hyphenation: u8,
    pub reserved: u8,
    /// Maximum rendered lines; 0 means unlimited.
    pub max_lines: u32,
}

/// Measured size in points.
#[repr(C)]
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct PamTextMeasureResult {
    pub width: f32,
    pub height: f32,
    /// Distance from the top of the box to the first baseline, in points.
    pub first_baseline: f32,
    pub line_count: u32,
}

/// Returns non-zero when `result` was written.
pub type PamTextMeasureCallback = unsafe extern "C" fn(
    context: *mut c_void,
    request: *const PamTextMeasureRequest,
    result: *mut PamTextMeasureResult,
) -> i32;

const CACHE_LIMIT: usize = 16_384;

pub(crate) struct HostTextMeasurer {
    callback: PamTextMeasureCallback,
    context: *mut c_void,
    cache: RefCell<HashMap<u64, PamTextMeasureResult>>,
}

impl std::fmt::Debug for HostTextMeasurer {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("HostTextMeasurer")
            .field("cached", &self.cache.borrow().len())
            .finish_non_exhaustive()
    }
}

// SAFETY: the host promises the callback/context pair may be invoked from
// whichever thread currently owns the engine (engines are externally
// synchronized and never used concurrently).
unsafe impl Send for HostTextMeasurer {}

impl HostTextMeasurer {
    pub(crate) fn new(callback: PamTextMeasureCallback, context: *mut c_void) -> Self {
        Self {
            callback,
            context,
            cache: RefCell::new(HashMap::new()),
        }
    }

    pub(crate) fn clear(&self) {
        self.cache.borrow_mut().clear();
    }
}

thread_local! {
    static ACTIVE: Cell<*const HostTextMeasurer> = const { Cell::new(std::ptr::null()) };
    static SAFE_AREA: Cell<Option<[f32; 4]>> = const { Cell::new(None) };
    static KEYBOARD_INSET: Cell<f32> = const { Cell::new(0.0) };
    static SURFACE_POLICY: Cell<crate::surface::SurfacePolicy> =
        const { Cell::new(crate::surface::SurfacePolicy::InWindow) };
    static SURFACE_KEYBOARD_INSETS: RefCell<BTreeMap<u64, f32>> =
        const { RefCell::new(BTreeMap::new()) };
    static SURFACE_KEYBOARD_TOP: Cell<Option<f32>> = const { Cell::new(None) };
}

/// Installs host layout inputs (text measurer, window safe area) for layout
/// calls made on this thread until dropped.
pub(crate) struct ActiveScope {
    previous: *const HostTextMeasurer,
    previous_safe_area: Option<[f32; 4]>,
    previous_keyboard_inset: f32,
    previous_surface_policy: crate::surface::SurfacePolicy,
    previous_surface_keyboard_insets: Option<BTreeMap<u64, f32>>,
}

impl ActiveScope {
    #[cfg(test)]
    pub(crate) fn enter(measurer: Option<&HostTextMeasurer>) -> Self {
        Self::enter_with(measurer, None, 0.0)
    }

    pub(crate) fn enter_with(
        measurer: Option<&HostTextMeasurer>,
        safe_area: Option<[f32; 4]>,
        keyboard_inset: f32,
    ) -> Self {
        let pointer = measurer.map_or(std::ptr::null(), std::ptr::from_ref);
        Self {
            previous: ACTIVE.with(|active| active.replace(pointer)),
            previous_safe_area: SAFE_AREA.with(|area| area.replace(safe_area)),
            previous_keyboard_inset: KEYBOARD_INSET.with(|inset| inset.replace(keyboard_inset)),
            previous_surface_policy: SURFACE_POLICY.with(Cell::get),
            previous_surface_keyboard_insets: None,
        }
    }

    /// Lays out presentation surfaces with `policy` while this scope lives.
    pub(crate) fn with_surface_policy(self, policy: crate::surface::SurfacePolicy) -> Self {
        SURFACE_POLICY.with(|current| current.set(policy));
        self
    }

    /// IME insets of the modal surfaces (by modal node id) while this scope
    /// lives.
    pub(crate) fn with_surface_keyboard_insets(mut self, insets: &BTreeMap<u64, f32>) -> Self {
        let previous = SURFACE_KEYBOARD_INSETS.with(|current| current.replace(insets.clone()));
        if self.previous_surface_keyboard_insets.is_none() {
            self.previous_surface_keyboard_insets = Some(previous);
        }
        self
    }
}

impl Drop for ActiveScope {
    fn drop(&mut self) {
        ACTIVE.with(|active| active.set(self.previous));
        SAFE_AREA.with(|area| area.set(self.previous_safe_area));
        KEYBOARD_INSET.with(|inset| inset.set(self.previous_keyboard_inset));
        SURFACE_POLICY.with(|policy| policy.set(self.previous_surface_policy));
        if let Some(previous) = self.previous_surface_keyboard_insets.take() {
            SURFACE_KEYBOARD_INSETS.with(|insets| *insets.borrow_mut() = previous);
        }
    }
}

/// While a modal surface is laid out: the IME top edge in root coordinates
/// (`None` when its keyboard is hidden). Restored on drop.
pub(crate) struct SurfaceKeyboardScope {
    previous: Option<f32>,
}

impl SurfaceKeyboardScope {
    /// Enters the surface of the `Modal` `surface` whose window ends at
    /// `window_bottom` (root coordinates).
    pub(crate) fn enter(surface: u64, window_bottom: f32) -> Self {
        let top = SURFACE_KEYBOARD_INSETS
            .with(|insets| insets.borrow().get(&surface).copied())
            .filter(|inset| *inset > 0.0)
            .map(|inset| window_bottom - inset);
        Self {
            previous: SURFACE_KEYBOARD_TOP.with(|current| current.replace(top)),
        }
    }
}

impl Drop for SurfaceKeyboardScope {
    fn drop(&mut self) {
        SURFACE_KEYBOARD_TOP.with(|current| current.set(self.previous));
    }
}

/// IME top edge (root coordinates) over the modal surface being laid out.
pub(crate) fn surface_keyboard_top() -> Option<f32> {
    SURFACE_KEYBOARD_TOP.with(Cell::get)
}

/// Replaces the safe area seen by `SafeAreaView`s while a presentation
/// surface (modal window, sheet route) is laid out; restores it on drop.
pub(crate) struct SurfaceInsetsScope {
    previous: Option<[f32; 4]>,
}

impl SurfaceInsetsScope {
    pub(crate) fn enter(insets: Option<[f32; 4]>) -> Self {
        Self {
            previous: SAFE_AREA.with(|area| area.replace(insets)),
        }
    }
}

impl Drop for SurfaceInsetsScope {
    fn drop(&mut self) {
        SAFE_AREA.with(|area| area.set(self.previous));
    }
}

/// How presentation surfaces relate to the window's system bars.
pub(crate) fn surface_policy() -> crate::surface::SurfacePolicy {
    SURFACE_POLICY.with(Cell::get)
}

/// Window safe-area insets (left, top, right, bottom) in points, when the
/// host lets the engine lay out `SafeAreaView` padding.
pub(crate) fn safe_area() -> Option<[f32; 4]> {
    SAFE_AREA.with(Cell::get)
}

/// Visible IME height (points, from the window bottom) used to keep a
/// trailing panning `KeyboardAvoidingView` above the keyboard in layout.
pub(crate) fn keyboard_inset() -> f32 {
    KEYBOARD_INSET.with(Cell::get)
}

fn string(node: &Node, key: PropKey) -> &str {
    match node.properties.get(&key) {
        Some(PropValue::String(value)) => value.as_str(),
        _ => "",
    }
}

fn number(node: &Node, key: PropKey) -> Option<f32> {
    node.properties.get(&key).and_then(PropValue::as_number)
}

fn integer(node: &Node, key: PropKey) -> Option<i64> {
    match node.properties.get(&key) {
        Some(PropValue::Integer(value)) => Some(*value),
        Some(PropValue::Float(value)) if value.is_finite() => Some(*value as i64),
        _ => None,
    }
}

/// Measures `node` through the active host measurer. `font_scale` is the
/// effective (already clamped) accessibility multiplier.
pub(crate) fn measure(
    node: &Node,
    available_width: f32,
    font_scale: f32,
) -> Option<PamTextMeasureResult> {
    let pointer = ACTIVE.with(Cell::get);
    if pointer.is_null() {
        return None;
    }
    // SAFETY: the pointer was installed by an `ActiveScope` that outlives this
    // call on the current thread.
    let measurer = unsafe { &*pointer };
    let text = string(node, PropKey::Text);
    let spans = string(node, PropKey::TextSpans);
    let family = string(node, PropKey::FontFamily);
    let features = string(node, PropKey::FontFeatureSettings);
    let available_width = if available_width.is_finite() && available_width > 0.0 {
        available_width
    } else {
        f32::INFINITY
    };
    let include_font_padding = !matches!(
        node.properties.get(&PropKey::IncludeFontPadding),
        Some(PropValue::Boolean(false))
    );
    let request = PamTextMeasureRequest {
        node_id: node.id,
        text: text.as_ptr(),
        text_length: text.len(),
        spans: spans.as_ptr(),
        spans_length: spans.len(),
        font_family: family.as_ptr(),
        font_family_length: family.len(),
        font_features: features.as_ptr(),
        font_features_length: features.len(),
        font_size: number(node, PropKey::FontSize).unwrap_or(14.0).max(1.0),
        font_scale,
        letter_spacing: number(node, PropKey::LetterSpacing).unwrap_or(0.0),
        line_height: number(node, PropKey::LineHeight).unwrap_or(0.0).max(0.0),
        available_width,
        font_weight: integer(node, PropKey::FontWeight)
            .unwrap_or(400)
            .clamp(1, 1000) as u16,
        italic: u8::from(integer(node, PropKey::FontStyle) == Some(2)),
        include_font_padding: u8::from(include_font_padding),
        text_transform: integer(node, PropKey::TextTransform)
            .unwrap_or(1)
            .clamp(0, 255) as u8,
        break_strategy: integer(node, PropKey::TextBreakStrategy)
            .unwrap_or(0)
            .clamp(0, 255) as u8,
        hyphenation: integer(node, PropKey::TextHyphenationFrequency)
            .unwrap_or(0)
            .clamp(0, 255) as u8,
        reserved: 0,
        max_lines: integer(node, PropKey::NumberOfLines)
            .filter(|lines| *lines > 0)
            .map_or(0, |lines| lines.min(i64::from(u32::MAX)) as u32),
    };
    let key = cache_key(&request, text, spans, family, features);
    if let Some(hit) = measurer.cache.borrow().get(&key) {
        return Some(*hit);
    }
    let mut result = PamTextMeasureResult::default();
    // SAFETY: request and result are valid for the duration of the call; the
    // host contract forbids retaining either pointer.
    let written =
        unsafe { (measurer.callback)(measurer.context, &raw const request, &raw mut result) };
    if written == 0
        || !result.width.is_finite()
        || !result.height.is_finite()
        || result.width < 0.0
        || result.height < 0.0
    {
        return None;
    }
    if !result.first_baseline.is_finite() {
        result.first_baseline = result.height;
    }
    let mut cache = measurer.cache.borrow_mut();
    if cache.len() >= CACHE_LIMIT {
        cache.clear();
    }
    cache.insert(key, result);
    Some(result)
}

fn cache_key(
    request: &PamTextMeasureRequest,
    text: &str,
    spans: &str,
    family: &str,
    features: &str,
) -> u64 {
    let mut hasher = DefaultHasher::new();
    text.hash(&mut hasher);
    spans.hash(&mut hasher);
    family.hash(&mut hasher);
    features.hash(&mut hasher);
    for value in [
        request.font_size,
        request.font_scale,
        request.letter_spacing,
        request.line_height,
        request.available_width,
    ] {
        value.to_bits().hash(&mut hasher);
    }
    (
        request.font_weight,
        request.italic,
        request.include_font_padding,
        request.text_transform,
        request.break_strategy,
        request.hyphenation,
        request.max_lines,
    )
        .hash(&mut hasher);
    hasher.finish()
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use std::collections::BTreeMap;
    thread_local! {
        pub(crate) static CALLS: Cell<usize> = const { Cell::new(0) };
    }

    /// Deterministic fake: 10pt per character, 20pt per line; includes
    /// font padding as 4pt; wraps at the available width.
    pub(crate) unsafe extern "C" fn fake(
        _context: *mut c_void,
        request: *const PamTextMeasureRequest,
        result: *mut PamTextMeasureResult,
    ) -> i32 {
        CALLS.with(|calls| calls.set(calls.get() + 1));
        let request = unsafe { &*request };
        let text = unsafe { std::slice::from_raw_parts(request.text, request.text_length) };
        let characters = std::str::from_utf8(text).unwrap().chars().count() as f32;
        let natural = characters * 10.0;
        let width = natural.min(request.available_width);
        let mut lines = (natural / width.max(1.0)).ceil().max(1.0);
        if request.max_lines > 0 {
            lines = lines.min(request.max_lines as f32);
        }
        let padding = if request.include_font_padding == 1 {
            4.0
        } else {
            0.0
        };
        let line = if request.line_height > 0.0 {
            request.line_height
        } else {
            20.0
        };
        unsafe {
            *result = PamTextMeasureResult {
                width,
                height: lines * line + padding,
                first_baseline: 15.0,
                line_count: lines as u32,
            };
        }
        1
    }

    fn text_node(text: &str) -> Node {
        Node {
            id: 7,
            parent: 0,
            index: 0,
            kind: pam_native_protocol::NodeKind::Text,
            properties: BTreeMap::from([(PropKey::Text, PropValue::String(text.into()))]),
        }
    }

    #[test]
    fn measurer_is_scoped_and_cached() {
        let node = text_node("hello");
        assert!(measure(&node, 100.0, 1.0).is_none());
        let measurer = HostTextMeasurer::new(fake, std::ptr::null_mut());
        let _scope = ActiveScope::enter(Some(&measurer));
        let before = CALLS.with(Cell::get);
        let first = measure(&node, 100.0, 1.0).unwrap();
        let second = measure(&node, 100.0, 1.0).unwrap();
        assert_eq!(first, second);
        assert_eq!(first.width, 50.0);
        assert_eq!(first.height, 24.0);
        assert_eq!(CALLS.with(Cell::get) - before, 1);
        let mut unpadded = node.clone();
        unpadded
            .properties
            .insert(PropKey::IncludeFontPadding, PropValue::Boolean(false));
        assert_eq!(measure(&unpadded, 100.0, 1.0).unwrap().height, 20.0);
    }

    #[test]
    fn layout_uses_host_measurements_for_text_boxes() {
        use pam_native_protocol::{NodeKind, Tree};
        let mut text = text_node("abcdefghij");
        text.parent = 1;
        text.properties
            .insert(PropKey::IncludeFontPadding, PropValue::Boolean(false));
        let tree = Tree {
            root: 1,
            nodes: BTreeMap::from([
                (
                    1,
                    Node {
                        id: 1,
                        parent: 0,
                        index: 0,
                        kind: NodeKind::Column,
                        properties: BTreeMap::from([(PropKey::AlignItems, PropValue::Integer(1))]),
                    },
                ),
                (7, text),
            ]),
        };
        let measurer = HostTextMeasurer::new(fake, std::ptr::null_mut());
        let _scope = ActiveScope::enter(Some(&measurer));
        let layouts = crate::layout::calculate_with_text_metrics(
            &tree,
            crate::layout::Size {
                width: 60.0,
                height: 400.0,
            },
            1.0,
            &crate::font_metrics::TextMetrics::new(),
        )
        .unwrap();
        assert_eq!(layouts[&7].width, 60.0);
        assert_eq!(layouts[&7].height, 40.0);
    }
}
