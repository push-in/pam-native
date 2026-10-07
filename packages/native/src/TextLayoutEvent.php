<?php

declare(strict_types=1);

namespace Pam\Native;

use Pam\Native\Internal\Wire;

use function count;
use function is_array;
use function is_float;
use function is_int;
use function is_string;

/**
 * `on:textLayout` payload: wrapped line count at the rendered width (even
 * when `numberOfLines` truncates), visible line count, truncation flag and
 * per-line widths in dp. Use it for "… mais" captions without measuring a
 * hidden copy of the text.
 */
final readonly class TextLayoutEvent
{
    /** @param list<float> $lineWidths */
    public function __construct(
        public int $lines,
        public int $visibleLines,
        public bool $truncated,
        public float $width,
        public float $height,
        public array $lineWidths,
    ) {
    }

    public static function fromPayload(string $payload): self
    {
        $values = $payload === '' ? [] : Wire::decodeMap($payload);
        $number = static fn (mixed $value): float => is_int($value) || is_float($value) ? (float) $value : 0.0;
        $widths = [];
        $raw = $values['lineWidths'] ?? null;
        if (is_string($raw) && $raw !== '') {
            $decoded = json_decode($raw, true);
            if (is_array($decoded)) {
                foreach ($decoded as $width) {
                    if (is_int($width) || is_float($width)) {
                        $widths[] = (float) $width;
                    }
                }
            }
        }
        $lines = $values['lines'] ?? null;
        $visible = $values['visibleLines'] ?? null;

        return new self(
            is_int($lines) ? $lines : count($widths),
            is_int($visible) ? $visible : (is_int($lines) ? $lines : count($widths)),
            ($values['truncated'] ?? false) === true,
            $number($values['width'] ?? null),
            $number($values['height'] ?? null),
            $widths,
        );
    }

    /** True when the text wraps past [lines] lines (the RN `lines.length > n` check). */
    public function exceeds(int $lines): bool
    {
        return $this->lines > $lines;
    }
}
