<?php

declare(strict_types=1);

namespace Pam\Native\Attributes;

use Attribute;

/**
 * Opts a component out of render memoization: it re-renders on every root
 * render even when its own state, props and tracked dependencies did not
 * change (for example when its template reads mutable external services).
 */
#[Attribute(Attribute::TARGET_CLASS)]
final readonly class AlwaysRender
{
}
