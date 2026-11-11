<?php

declare(strict_types=1);

namespace Pam\Native\Composables;

use Pam\Native\WindowMetrics;

/**
 * `useFormFactor()`: the kind of device and how it is driven. The same app
 * adapts with `p-if="$form->tv"` or a `@media (device-type: tv)` rule, and a
 * `Screen.tv.pam` variant replaces a whole screen on TVs.
 *
 * @property-read string $type 'phone', 'tablet' or 'tv'
 * @property-read bool $tv a TV (Android TV, Google TV, Fire TV, Apple TV)
 * @property-read bool $phone
 * @property-read bool $tablet
 * @property-read bool $remote a remote control or game pad moves the focus
 * @property-read bool $touch the screen is touched
 * @property-read string $inputMode 'touch', 'remote', 'keyboard' or 'mouse'
 * @property-read string $pointer 'coarse', 'fine' or 'none'
 */
final class FormFactor extends ReactiveObject
{
    /** @param SharedSource<WindowMetrics> $source */
    public function __construct(private readonly SharedSource $source)
    {
    }

    protected function properties(): array
    {
        return ['type', 'tv', 'phone', 'tablet', 'remote', 'touch', 'inputMode', 'pointer'];
    }

    protected function read(string $name): mixed
    {
        return $this->source->field('form.'.$name, match ($name) {
            'type' => static fn (WindowMetrics $m): string => $m->deviceType,
            'tv' => static fn (WindowMetrics $m): bool => $m->deviceType === 'tv',
            'phone' => static fn (WindowMetrics $m): bool => $m->deviceType === 'phone',
            'tablet' => static fn (WindowMetrics $m): bool => $m->deviceType === 'tablet',
            'remote' => static fn (WindowMetrics $m): bool => $m->inputMode === 'remote',
            'touch' => static fn (WindowMetrics $m): bool => $m->inputMode === 'touch',
            'inputMode' => static fn (WindowMetrics $m): string => $m->inputMode,
            'pointer' => static fn (WindowMetrics $m): string => $m->pointer,
            default => throw new \LogicException("Unknown form factor property {$name}."),
        })->get();
    }
}
