<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

use Pam\Native\Element;

/**
 * Where a compiled component tag placed its component's element in the
 * owner's last render, with what the call site applied to it (class,
 * attributes, static slot), so the owner can place a new element of that
 * component without running its template (see GranularRender).
 *
 * @internal
 */
final class ComponentPlacement
{
    /**
     * @param array<string, mixed> $values evaluated attribute values of the tag
     * @param array<string, mixed> $data render data at the tag
     * @param array<string, string|bool> $authored attributes as authored
     */
    public function __construct(
        public readonly object $owner,
        public readonly string $tag,
        public readonly array $values,
        public readonly array $data,
        public readonly array $authored,
        public readonly ?string $resolvedClass,
        public readonly mixed $sheet,
        public readonly int $styleRevision,
        /** Render pass of the owner's real render that placed it. */
        public readonly int $pass,
        public string $slot,
        public Element $element,
        public Element $decorated,
        public Element $placed,
    ) {
    }
}
