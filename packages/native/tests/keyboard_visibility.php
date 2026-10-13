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
