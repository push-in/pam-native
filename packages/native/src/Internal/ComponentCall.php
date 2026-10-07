<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

use Pam\Native\Component;
use Pam\Native\Element;

/**
 * The evaluated inputs of one compiled component call site and what they
 * produced, so an identical call skips prop diffing, configuration,
 * contract validation and decoration.
 *
 * @internal
 */
final class ComponentCall
{
    /**
     * @param array<string, mixed> $values evaluated attribute values
     * @param array<string, mixed> $inherited inherited text styles
     * @param array<string, mixed> $props constructor props the instance holds
     */
    public function __construct(
        public readonly int $plan,
        public readonly array $values,
        public readonly array $inherited,
        public readonly string $cacheKey,
        public readonly Component $instance,
        public readonly array $props,
        public ?Element $element = null,
        public ?Element $decorated = null,
    ) {
    }
}
