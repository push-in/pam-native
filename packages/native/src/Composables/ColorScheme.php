<?php

declare(strict_types=1);

namespace Pam\Native\Composables;

use Closure;
use InvalidArgumentException;
use Pam\Native\Appearance;
use Pam\Native\AppearanceMode;
use Pam\Native\UserInterfaceAppearance;

/**
 * `useColorScheme()`: the effective scheme and the user's preference.
 *
 * @property-read string $value 'light' or 'dark', the scheme this frame renders with
 * @property-read string $mode 'system', 'light' or 'dark', the persisted preference
 * @property-read bool $dark
 * @property-read UserInterfaceAppearance $appearance
 * @property-read AppearanceMode $preference
 */
final class ColorScheme extends ReactiveObject
{
    /** @param SharedSource<array{0: UserInterfaceAppearance, 1: AppearanceMode}> $source */
    public function __construct(private readonly SharedSource $source)
    {
    }

    protected function properties(): array
    {
        return ['value', 'mode', 'dark', 'appearance', 'preference'];
    }

    protected function read(string $name): mixed
    {
        [$appearance, $mode] = $this->source->signal->get();

        return match ($name) {
            'value' => $appearance === UserInterfaceAppearance::Dark ? 'dark' : 'light',
            'mode' => strtolower($mode->name),
            'dark' => $appearance === UserInterfaceAppearance::Dark,
            'appearance' => $appearance,
            default => $mode,
        };
    }

    /**
     * Selects and persists the preference: 'dark', 'light' or 'system'.
     *
     * @param (Closure(bool): void)|null $completion receives whether the host persisted it
     */
    public function set(string|AppearanceMode $mode, ?Closure $completion = null): void
    {
        $preference = $mode instanceof AppearanceMode ? $mode : match ($mode) {
            'system' => AppearanceMode::System,
            'light' => AppearanceMode::Light,
            'dark' => AppearanceMode::Dark,
            default => throw new InvalidArgumentException("Color scheme must be 'system', 'light' or 'dark', '{$mode}' given."),
        };
        Appearance::set($preference, $completion);
    }

    /** Switches between light and dark (leaving 'system'). */
    public function toggle(): void
    {
        $this->set($this->read('dark') === true ? AppearanceMode::Light : AppearanceMode::Dark);
    }
}
