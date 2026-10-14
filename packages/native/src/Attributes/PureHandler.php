<?php

declare(strict_types=1);

namespace Pam\Native\Attributes;

use Attribute;

/**
 * Marks an event handler that does not need a re-render by itself.
 *
 * A template method handler (`on:progress="tick"`) or element closure
 * (`#[PureHandler] fn () => ...`) carrying this attribute does not mark its
 * component dirty. The runtime renders after it only when something really
 * changed: the component's own state, a tracked store/signal it read, or an
 * explicit `requestRender()`/`markForRender()`. Otherwise the event costs the
 * handler call and nothing else (no tree walk, no encode, no patch).
 */
#[Attribute(Attribute::TARGET_METHOD | Attribute::TARGET_FUNCTION)]
final class PureHandler
{
}
