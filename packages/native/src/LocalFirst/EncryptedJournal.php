<?php

declare(strict_types=1);

namespace Pam\Native\LocalFirst;

final class EncryptedJournal
{
    private const int MAX_BYTES = 16_777_216;

    public function __construct(private readonly string $key)
    {
        if (strlen($key) !== 32) {
            throw new \InvalidArgumentException('Local-first encryption requires a 256-bit key.');
        }
    }

    /** @param list<array<string, mixed>> $entries */
    public function seal(array $entries): string
    {
        $plaintext = json_encode($entries, JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES);
        if (strlen($plaintext) > self::MAX_BYTES) {
            throw new \OverflowException('Local-first journal exceeds 16 MiB.');
        }
        $nonce = random_bytes(12);
        $tag = '';
        $ciphertext = openssl_encrypt($plaintext, 'aes-256-gcm', $this->key, OPENSSL_RAW_DATA, $nonce, $tag, 'PAM-NATIVE-LF1');
        if (!is_string($ciphertext) || strlen($tag) !== 16) {
            throw new \RuntimeException('Local-first journal encryption failed.');
        }
        return 'PNL1'.$nonce.$tag.$ciphertext;
    }

    /** @return list<array<string, mixed>> */
    public function open(string $payload): array
    {
        if (!str_starts_with($payload, 'PNL1') || strlen($payload) < 32 || strlen($payload) > self::MAX_BYTES + 32) {
            throw new \InvalidArgumentException('Local-first journal envelope is invalid.');
        }
        $plaintext = openssl_decrypt(
            substr($payload, 32),
            'aes-256-gcm',
            $this->key,
            OPENSSL_RAW_DATA,
            substr($payload, 4, 12),
            substr($payload, 16, 16),
            'PAM-NATIVE-LF1',
        );
        if (!is_string($plaintext)) {
            throw new \RuntimeException('Local-first journal authentication failed.');
        }
        $decoded = json_decode($plaintext, true, 64, JSON_THROW_ON_ERROR);
        if (!is_array($decoded) || !array_is_list($decoded)) {
            throw new \RuntimeException('Local-first journal content is invalid.');
        }
        $entries = [];
        foreach ($decoded as $entry) {
            if (!is_array($entry)) {
                throw new \RuntimeException('Local-first journal entries must be objects.');
            }
            $normalized = [];
            foreach ($entry as $key => $value) {
                if (!is_string($key)) {
                    throw new \RuntimeException('Local-first journal entry keys must be strings.');
                }
                $normalized[$key] = $value;
            }
            $entries[] = $normalized;
        }
        return $entries;
    }
}
