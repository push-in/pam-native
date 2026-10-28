<?php

declare(strict_types=1);

use Pam\Native\EventKind;
use Pam\Native\Internal\Runtime;
use Pam\Native\System\Keyboard;

Runtime::shutdown();
$keyboardChanges = [];
$subscription = Keyboard::onVisibilityChange(static function (bool $visible) use (&$keyboardChanges): void {
    $keyboardChanges[] = $visible;
});
$assert(!Keyboard::isVisible(), 'Keyboard starts hidden.');
Runtime::dispatchEvent(0, EventKind::KeyboardVisibility->value, '1');
Runtime::dispatchEvent(0, EventKind::KeyboardVisibility->value, '1');
$assert(Keyboard::isVisible() && $keyboardChanges === [true], 'Native animation frames notify only a visibility transition.');
Runtime::dispatchEvent(0, EventKind::KeyboardVisibility->value, '0');
$assert(!Keyboard::isVisible() && $keyboardChanges === [true, false], 'Back hides the keyboard without requiring input blur.');
Keyboard::offVisibilityChange($subscription);
Runtime::dispatchEvent(0, EventKind::KeyboardVisibility->value, '1');
$assert(Keyboard::isVisible() && count($keyboardChanges) === 2, 'Unsubscribed consumers do not run; synchronous state stays current.');
Runtime::dispatchEvent(0, EventKind::AppState->value, '3');
Runtime::dispatchEvent(0, EventKind::KeyboardVisibility->value, '1');
$assert(!Keyboard::isVisible(), 'Background ignores late insets and clears visibility.');
Runtime::dispatchEvent(0, EventKind::AppState->value, '1');
Runtime::dispatchEvent(0, EventKind::KeyboardVisibility->value, '1');
$assert(Keyboard::isVisible(), 'Resume accepts the host keyboard snapshot.');
$second = 0;
$first = Keyboard::onVisibilityChange(static function () use (&$second): void {
    Keyboard::offVisibilityChange($second);
});
$second = Keyboard::onVisibilityChange(static function () use (&$keyboardChanges): void { $keyboardChanges[] = 'stale'; });
Runtime::dispatchEvent(0, EventKind::KeyboardVisibility->value, '0');
$assert(!in_array('stale', $keyboardChanges, true), 'Unsubscription during dispatch prevents later callbacks.');
Runtime::shutdown();
Runtime::dispatchEvent(0, EventKind::KeyboardVisibility->value, '1');
$assert(count($keyboardChanges) === 2, 'Runtime shutdown removes subscriptions.');
Runtime::shutdown();

// First-class method callbacks dirty only their component, keeping large
// sibling timelines memoized while a composer changes its bottom padding.
$keyboardOwner = new class extends \Pam\Native\Component {
    public bool $visible = false;
    public function changed(bool $visible): void { $this->visible = $visible; }
    public function render(): \Pam\Native\Element { return \Pam\Native\UI\Text::make('composer'); }
};
$ownerSubscription = Keyboard::onVisibilityChange($keyboardOwner->changed(...));
$epoch = \Pam\Native\Internal\DependencyTracker::epoch();
Runtime::dispatchEvent(0, EventKind::KeyboardVisibility->value, '1');
$assert($keyboardOwner->visible && \Pam\Native\Internal\DependencyTracker::isDirty($keyboardOwner), 'Bound listeners dirty their component.');
$assert(\Pam\Native\Internal\DependencyTracker::epoch() === $epoch, 'Keyboard changes do not invalidate unrelated timelines.');
$ownerReference = WeakReference::create($keyboardOwner);
Keyboard::offVisibilityChange($ownerSubscription);
unset($keyboardOwner);
gc_collect_cycles();
$assert($ownerReference->get() === null, 'Unsubscribe releases the component callback.');
Runtime::shutdown();

// Keyboard height (React Native Keyboard.metrics / keyboardWill* events):
// hosts send "<visible>;<heightDp>;<durationMs>" once per settled change.
$metricsSeen = [];
$visibilitySeen = [];
$metricsSubscription = Keyboard::onChange(static function (\Pam\Native\KeyboardMetrics $metrics) use (&$metricsSeen): void {
    $metricsSeen[] = [$metrics->visible, $metrics->height, $metrics->durationMs];
});
$visibilitySubscription = Keyboard::onVisibilityChange(static function (bool $visible) use (&$visibilitySeen): void {
    $visibilitySeen[] = $visible;
});
$assert(Keyboard::height() === 0.0 && Keyboard::lastHeight() === 0.0, 'Keyboard height starts at zero.');
Runtime::dispatchEvent(0, EventKind::KeyboardVisibility->value, '1;301.25;250');
$assert(Keyboard::isVisible() && Keyboard::height() === 301.3 && Keyboard::metrics()->durationMs === 250, 'Visible payload carries the settled height and animation duration.');
Runtime::dispatchEvent(0, EventKind::KeyboardVisibility->value, '1;301.3;0');
$assert(count($metricsSeen) === 1, 'An unchanged settled height is not republished.');
Runtime::dispatchEvent(0, EventKind::KeyboardVisibility->value, '1;345;0');
$assert($metricsSeen === [[true, 301.3, 250], [true, 345.0, 0]] && $visibilitySeen === [true], 'A height change (suggestion strip) notifies height listeners only.');
Runtime::dispatchEvent(0, EventKind::KeyboardVisibility->value, '0;0;200');
$assert(!Keyboard::isVisible() && Keyboard::height() === 0.0 && Keyboard::lastHeight() === 345.0, 'Hide clears the height and keeps the last keyboard height for replacement panels.');
$assert($visibilitySeen === [true, false] && end($metricsSeen) === [false, 0.0, 200], 'Hide notifies both subscriptions once.');
Runtime::dispatchEvent(0, EventKind::KeyboardVisibility->value, '1');
$assert(Keyboard::isVisible() && Keyboard::height() === 0.0, 'A legacy visibility-only host keeps working without a height.');
Runtime::dispatchEvent(0, EventKind::KeyboardVisibility->value, '0;nan;-4');
Runtime::dispatchEvent(0, EventKind::KeyboardVisibility->value, '1;99999;99999');
$assert(Keyboard::height() === 10000.0 && Keyboard::metrics()->durationMs === 5000, 'Untrusted payload values are clamped.');
Runtime::dispatchEvent(0, EventKind::AppState->value, '3');
$assert(!Keyboard::isVisible() && Keyboard::height() === 0.0, 'Background clears the keyboard height.');
Runtime::dispatchEvent(0, EventKind::KeyboardVisibility->value, '1;280;0');
$assert(Keyboard::height() === 0.0, 'Background ignores late keyboard frames.');
Runtime::dispatchEvent(0, EventKind::AppState->value, '1');
Keyboard::offChange($metricsSubscription);
Keyboard::offVisibilityChange($visibilitySubscription);
$count = count($metricsSeen);
Runtime::dispatchEvent(0, EventKind::KeyboardVisibility->value, '1;280;0');
$assert(count($metricsSeen) === $count && Keyboard::height() === 280.0, 'offChange stops notifications; state stays current.');
Runtime::shutdown();
$assert(Keyboard::height() === 0.0 && Keyboard::lastHeight() === 0.0, 'Runtime shutdown resets keyboard metrics.');
