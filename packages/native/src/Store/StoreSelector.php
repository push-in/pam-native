<?php

declare(strict_types=1);

namespace Pam\Native\Store;

use Closure;

final class StoreSelector
{
    private ?string $fingerprint = null;
    private mixed $value = null;

    /** @param Closure(Store): mixed $selector */
    public function __construct(
        private readonly Closure $selector,
    ) {
    }

    public function value(Store $store): mixed
    {
        $fingerprint = hash('xxh3', serialize($store->snapshot()));
        if ($this->fingerprint !== $fingerprint) {
            $this->value = ($this->selector)($store);
            $this->fingerprint = $fingerprint;
        }

        return $this->value;
    }
}
