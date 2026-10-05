<?php

declare(strict_types=1);

namespace Pam\Native\Http;

/** Byte progress of a streamed HTTP request body. */
final readonly class TransferProgress
{
    public function __construct(
        public int $bytesSent,
        public int $totalBytes,
    ) {
    }

    public function fraction(): float
    {
        if ($this->totalBytes <= 0) {
            return 0.0;
        }

        return max(0.0, min(1.0, $this->bytesSent / $this->totalBytes));
    }

    public function percent(): int
    {
        return (int) floor($this->fraction() * 100);
    }

    public function complete(): bool
    {
        return $this->totalBytes > 0 && $this->bytesSent >= $this->totalBytes;
    }
}
