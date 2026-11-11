<?php

declare(strict_types=1);

namespace Pam\Native;

use Pam\Native\Internal\Wire;

use function is_string;

/**
 * A key pressed on a remote control, game pad or hardware keyboard.
 */
final readonly class KeyEvent
{
    public function __construct(
        /**
         * The key as the W3C UI Events `key` value: "ArrowUp", "Enter",
         * "MediaPlayPause", "ContextMenu", "a"… The remote's select button is
         * "Enter"; its menu button is "ContextMenu".
         */
        public string $key,
        public bool $shift = false,
        public bool $control = false,
        public bool $alt = false,
        public bool $meta = false,
        /** True while the key is held and the system repeats it. */
        public bool $repeat = false,
    ) {
    }

    public static function fromPayload(string $payload): self
    {
        $values = $payload === '' ? [] : Wire::decodeMap($payload);

        return new self(
            is_string($values['key'] ?? null) ? $values['key'] : '',
            ($values['shift'] ?? false) === true,
            ($values['control'] ?? false) === true,
            ($values['alt'] ?? false) === true,
            ($values['meta'] ?? false) === true,
            ($values['repeat'] ?? false) === true,
        );
    }

    /** True for the four arrow keys (the remote's D-pad). */
    public function isArrow(): bool
    {
        return in_array($this->key, ['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight'], true);
    }

    /** True for the media transport keys (play, pause, stop, seek, track). */
    public function isMedia(): bool
    {
        return str_starts_with($this->key, 'Media');
    }
}
