<?php

declare(strict_types=1);

namespace Pam\Native\Store;

use Pam\Native\State;
use RuntimeException;

final readonly class EncryptedStatePersistence implements StorePersistence
{
    private string $key;

    public function __construct(string $key)
    {
        if (!function_exists('sodium_crypto_secretbox')) {
            throw new RuntimeException('Encrypted store persistence requires ext-sodium.');
        }
        $decoded = base64_decode($key, true);
        if (!is_string($decoded) || strlen($decoded) !== SODIUM_CRYPTO_SECRETBOX_KEYBYTES) {
            throw new RuntimeException('Store encryption key must be base64-encoded 256-bit data.');
        }
        $this->key = $decoded;
    }

    public function load(string $key): ?array
    {
        $encoded = State::get('secure-store.'.$key);
        if ($encoded === null) {
            return null;
        }
        if (!is_string($encoded)) {
            throw new RuntimeException("Encrypted store {$key} is invalid.");
        }
        $payload = base64_decode($encoded, true);
        if (!is_string($payload) || strlen($payload) <= SODIUM_CRYPTO_SECRETBOX_NONCEBYTES) {
            throw new RuntimeException("Encrypted store {$key} is invalid.");
        }
        $nonce = substr($payload, 0, SODIUM_CRYPTO_SECRETBOX_NONCEBYTES);
        $plain = sodium_crypto_secretbox_open(substr($payload, SODIUM_CRYPTO_SECRETBOX_NONCEBYTES), $nonce, $this->key);
        if (!is_string($plain)) {
            throw new RuntimeException("Encrypted store {$key} failed authentication.");
        }
        $decoded = json_decode($plain, true, 64, JSON_THROW_ON_ERROR);
        if (!is_array($decoded) || !is_int($decoded['version'] ?? null) || !is_array($decoded['state'] ?? null)) {
            throw new RuntimeException("Encrypted store {$key} is invalid.");
        }

        return ['version' => $decoded['version'], 'state' => $decoded['state']];
    }

    public function save(string $key, int $version, array $state): void
    {
        $plain = json_encode(
            ['version' => $version, 'state' => $state],
            JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES,
        );
        $nonce = random_bytes(SODIUM_CRYPTO_SECRETBOX_NONCEBYTES);
        $cipher = sodium_crypto_secretbox($plain, $nonce, $this->key);
        State::set('secure-store.'.$key, base64_encode($nonce.$cipher));
        sodium_memzero($plain);
    }

    public function forget(string $key): void
    {
        State::forget('secure-store.'.$key);
    }
}
