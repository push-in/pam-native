<?php

declare(strict_types=1);

namespace Pam\Native\Dom;

final class MutationObserver
{
    private bool $connected = true;

    /** @internal */
    public function __construct(
        private readonly Document $document,
        private readonly int $id,
    ) {
    }

    public function connected(): bool
    {
        return $this->connected;
    }

    public function disconnect(): void
    {
        if (!$this->connected) {
            return;
        }
        $this->connected = false;
        $this->document->disconnectObserver($this->id);
    }

    public function __destruct()
    {
        $this->disconnect();
    }
}
