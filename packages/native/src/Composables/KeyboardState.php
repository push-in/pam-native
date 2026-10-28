<?php

declare(strict_types=1);

namespace Pam\Native\Composables;

use Pam\Native\KeyboardMetrics;
use Pam\Native\System\Keyboard;

/**
 * `useKeyboard()`: the settled soft keyboard (one update per show, hide or
 * resize, never per animation frame).
 *
 * @property-read bool $visible
 * @property-read float $height points from the window bottom, 0 while hidden
 * @property-read int $durationMs platform animation length, 0 when unknown
 */
final class KeyboardState extends ReactiveObject
{
    /** @param SharedSource<KeyboardMetrics> $source */
    public function __construct(private readonly SharedSource $source)
    {
    }

    protected function properties(): array
    {
        return ['visible', 'height', 'durationMs'];
    }

    protected function read(string $name): mixed
    {
        $metrics = $this->source->signal->get();

        return match ($name) {
            'visible' => $metrics->visible,
            'height' => $metrics->height,
            default => $metrics->durationMs,
        };
    }

    public function dismiss(): void
    {
        Keyboard::dismiss();
    }
}
