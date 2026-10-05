<?php

declare(strict_types=1);

namespace Pam\Native\Notifications;

use InvalidArgumentException;

/**
 * Message sender shown by conversation notifications.
 *
 * The avatar may be an HTTPS URL (served from the image cache when prefetched),
 * a private sandbox path or a pam-file:/// URI.
 */
final readonly class Person
{
    public function __construct(
        public string $name,
        public ?string $avatar = null,
        public ?string $key = null,
    ) {
        if (trim($name) === '' || strlen($name) > 256) {
            throw new InvalidArgumentException('Person names must contain between 1 and 256 bytes.');
        }
        if ($avatar !== null && strlen($avatar) > 8_192) {
            throw new InvalidArgumentException('Person avatar exceeds 8192 bytes.');
        }
        if ($key !== null && strlen($key) > 256) {
            throw new InvalidArgumentException('Person key exceeds 256 bytes.');
        }
    }

    public static function make(string $name, ?string $avatar = null, ?string $key = null): self
    {
        return new self($name, $avatar, $key);
    }

    /** @return array{name: string, avatar: string, key: string} */
    public function toArray(): array
    {
        return ['name' => $this->name, 'avatar' => $this->avatar ?? '', 'key' => $this->key ?? ''];
    }
}
