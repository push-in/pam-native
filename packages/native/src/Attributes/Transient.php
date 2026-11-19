<?php

declare(strict_types=1);

namespace Pam\Native\Attributes;

use Attribute;

/**
 * Keeps a component property out of the state snapshot that decides whether
 * the component renders again.
 *
 * By default every property a component declares (public, protected and
 * private) is compared after each render: a write to any of them, from a
 * lifecycle hook, a timer, a subscription or a promise callback, renders the
 * component again on the next pass. Mark the bookkeeping fields that render()
 * and the template never read (caches, counters, subscription ids, timer
 * handles, timestamps, "already done" flags) `#[Transient]` so writing them
 * costs nothing.
 *
 * A transient field read by the template does not update the screen when it
 * changes alone: it shows up only on the next render caused by something else.
 * Event handlers, `markForRender()`, `requestRender()`, prop updates and
 * tracked signals/stores still render as before.
 */
#[Attribute(Attribute::TARGET_PROPERTY)]
final readonly class Transient
{
}
