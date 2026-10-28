<?php

declare(strict_types=1);

namespace Pam\Native\Composables;

use Pam\Native\AppState;

/**
 * `useAppState()`: foreground/background, like React Native AppState.
 *
 * @property-read AppState $value
 * @property-read bool $active foreground and interactive
 * @property-read bool $background not visible
 */
final class AppStateRef extends ReactiveObject
{
    /** @param SharedSource<AppState> $source */
    public function __construct(private readonly SharedSource $source)
    {
    }

    protected function properties(): array
    {
        return ['value', 'active', 'background'];
    }

    protected function read(string $name): mixed
    {
        $state = $this->source->signal->get();

        return match ($name) {
            'value' => $state,
            'active' => $state === AppState::Active,
            default => $state === AppState::Background,
        };
    }
}
