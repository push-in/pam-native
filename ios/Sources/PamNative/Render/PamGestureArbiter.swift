import UIKit

private var pamBlocksNavigationGestureKey: UInt8 = 0

extension UIView {
    /// `blocksNavigationGesture`: while the finger is on this view the
    /// navigator's back gesture does not begin, not even from the edge.
    var pamBlocksNavigationGesture: Bool {
        get { (objc_getAssociatedObject(self, &pamBlocksNavigationGestureKey) as? Bool) ?? false }
        set {
            objc_setAssociatedObject(
                self,
                &pamBlocksNavigationGestureKey,
                newValue ? true : nil,
                .OBJC_ASSOCIATION_RETAIN_NONATOMIC
            )
        }
    }
}

/// A gesture delegate that owns drags in some directions (a PAM pan or
/// swipe detector): see `PamGestureArbiter`.
protocol PamDragOwning: AnyObject {
    /// How strongly `recognizer` owns a drag along `horizontal` (else
    /// vertical) whose finger moves toward `sign` (+1 right/down, -1
    /// left/up).
    func dragOwnership(of recognizer: UIGestureRecognizer, horizontal: Bool, sign: CGFloat) -> Int
}

/// Who owns a drag under the finger, so no gesture steals another's
/// (`require(toFail:)`, react-native-gesture-handler's native view gestures,
/// Android `ViewPager.canScroll`). A container gesture (the navigator's
/// swipe back, a sheet's drag to close, a pan around content) asks before it
/// begins and yields to the content under the finger that would consume the
/// drag itself:
///
/// - `directional`: a scroll view that can still scroll the way the finger
///   moves (a pager with a previous page, a tab strip scrolled away from its
///   start), a pan or swipe detector whose direction admits the motion, a
///   slider.
/// - `exclusive`: content that owns every drag over it, even where a
///   container gesture has priority (the navigator's edge): a drawing
///   surface, a zoomed photo, a view marked `blocksNavigationGesture`.
///
/// The walk follows the hit-test chain (the views whose recognizers get the
/// touch), from the hit view up. It runs once per gesture decision.
enum PamGestureArbiter {
    static let noOwner = 0
    static let directional = 1
    static let exclusive = 2

    /// The strongest ownership among `hit` and its superviews up to, not
    /// including, `root`.
    static func ownership(
        from hit: UIView?,
        upTo root: UIView,
        horizontal: Bool,
        sign: CGFloat,
        navigation: Bool
    ) -> Int {
        var best = noOwner
        var candidate = hit
        while let view = candidate, view !== root {
            best = max(best, own(view, horizontal: horizontal, sign: sign, navigation: navigation))
            if best >= exclusive { return best }
            candidate = view.superview
        }
        return best
    }

    /// Like `ownership`, counting scroll views only: a pan around content (a
    /// dismiss drag) yields to a list that can still scroll, never to
    /// another detector, which composes with it as configured.
    static func scrollOwnership(from hit: UIView?, upTo root: UIView, horizontal: Bool, sign: CGFloat) -> Int {
        var candidate = hit
        while let view = candidate, view !== root {
            if let scroll = view as? UIScrollView, scroll.isScrollEnabled,
               canScroll(scroll, horizontal: horizontal, sign: sign) {
                return directional
            }
            candidate = view.superview
        }
        return noOwner
    }

    /// The ownership `view` itself declares, without its subviews.
    static func own(_ view: UIView, horizontal: Bool, sign: CGFloat, navigation: Bool) -> Int {
        if navigation && view.pamBlocksNavigationGesture { return exclusive }
        if view is PamDrawingCanvas, view.isUserInteractionEnabled { return exclusive }
        var best = noOwner
        if let scroll = view as? UIScrollView, scroll.isScrollEnabled,
           canScroll(scroll, horizontal: horizontal, sign: sign) {
            best = directional
        }
        #if !os(tvOS)
        if horizontal, let slider = view as? UISlider, slider.isEnabled {
            best = max(best, directional)
        }
        #endif
        for recognizer in view.gestureRecognizers ?? [] where recognizer.isEnabled {
            guard let owner = recognizer.delegate as? PamDragOwning else { continue }
            best = max(best, owner.dragOwnership(of: recognizer, horizontal: horizontal, sign: sign))
            if best >= exclusive { return best }
        }
        return best
    }

    /// Whether `scroll` can still move its content the way a finger moving
    /// toward `sign` drags it (a finger moving right reveals what is left).
    static func canScroll(_ scroll: UIScrollView, horizontal: Bool, sign: CGFloat) -> Bool {
        let inset = scroll.adjustedContentInset
        if horizontal {
            if sign > 0 { return scroll.contentOffset.x > -inset.left + 0.5 }
            return scroll.contentOffset.x < scroll.contentSize.width + inset.right - scroll.bounds.width - 0.5
        }
        if sign > 0 { return scroll.contentOffset.y > -inset.top + 0.5 }
        return scroll.contentOffset.y < scroll.contentSize.height + inset.bottom - scroll.bounds.height - 0.5
    }
}
