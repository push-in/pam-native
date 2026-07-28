<?php

declare(strict_types=1);

namespace Pam\Native\Store;

use Pam\Native\State;
use RuntimeException;

final class StatePersistence implements StorePersistence
{
    public function load(string $key): ?array
    {
        $payload = State::get('store.'.$key);
        if ($payload === null) {
            return null;
        }
        if (!is_array($payload) || !is_int($payload['version'] ?? null) || !is_array($payload['state'] ?? null)) {
            throw new RuntimeException("Persisted store {$key} is invalid.");
        }

        return ['version' => $payload['version'], 'state' => $payload['state']];
    }

    public function save(string $key, int $version, array $state): void
    {
        State::set('store.'.$key, ['version' => $version, 'state' => $state]);
    }

    public function forget(string $key): void
    {
        State::forget('store.'.$key);
    }
}
