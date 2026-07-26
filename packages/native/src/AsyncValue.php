<?php

declare(strict_types=1);

namespace Pam\Native;

use InvalidArgumentException;

final readonly class AsyncValue
{
    private function __construct(
        public AsyncStatus $status,
        public mixed $data = null,
        public ?string $message = null,
        public bool $retryable = false,
    ) {
        if ($message !== null && strlen($message) > 16_384) {
            throw new InvalidArgumentException('Async state messages cannot exceed 16 KiB.');
        }
    }

    public static function loading(mixed $previous = null): self
    {
        return new self(AsyncStatus::Loading, $previous);
    }

    public static function content(mixed $data): self
    {
        return new self(AsyncStatus::Content, $data);
    }

    public static function empty(?string $message = null): self
    {
        return new self(AsyncStatus::Empty, message: $message);
    }

    public static function error(
        string $message,
        bool $retryable = true,
        mixed $previous = null,
    ): self {
        return new self(AsyncStatus::Error, $previous, $message, $retryable);
    }

    public static function offline(
        mixed $cached = null,
        string $message = 'You are offline.',
    ): self {
        return new self(AsyncStatus::Offline, $cached, $message, true);
    }

    public static function stale(
        mixed $data,
        string $message = 'Showing saved content.',
    ): self {
        return new self(AsyncStatus::Stale, $data, $message, true);
    }

    public function hasData(): bool
    {
        return $this->data !== null;
    }

    public function isBusy(): bool
    {
        return $this->status === AsyncStatus::Loading;
    }
}
