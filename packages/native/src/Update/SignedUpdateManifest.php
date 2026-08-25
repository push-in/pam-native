<?php

declare(strict_types=1);

namespace Pam\Native\Update;

use InvalidArgumentException;
use JsonException;

final readonly class SignedUpdateManifest
{
    /** @param list<string> $capabilities */
    public function __construct(
        public string $buildIdentifier,
        public string $bundleSha256,
        public int $abiVersion,
        public int $protocolVersion,
        public UpdateChannel $channel,
        public int $rolloutBasisPoints,
        public array $capabilities,
    ) {
        if (preg_match('/^[a-f0-9]{64}$/D', $buildIdentifier) !== 1 || preg_match('/^[a-f0-9]{64}$/D', $bundleSha256) !== 1) {
            throw new InvalidArgumentException('Update build and bundle identifiers must be lowercase SHA-256 values.');
        }
        if ($abiVersion < 1 || $protocolVersion < 1 || $rolloutBasisPoints < 0 || $rolloutBasisPoints > 10_000) {
            throw new InvalidArgumentException('Update compatibility or rollout is invalid.');
        }
        if (count($capabilities) > 256 || $capabilities !== array_values(array_unique($capabilities))) {
            throw new InvalidArgumentException('Update capabilities must be a bounded unique list.');
        }
        foreach ($capabilities as $capability) {
            if (!is_string($capability) || preg_match('/^[a-z][a-z0-9]*(?:[._-][a-z0-9]+){1,7}$/D', $capability) !== 1) {
                throw new InvalidArgumentException('Update capability is invalid.');
            }
        }
    }

    /** @return array<string,mixed> */
    public function payload(): array
    {
        return [
            'abiVersion' => $this->abiVersion,
            'buildId' => $this->buildIdentifier,
            'bundleSha256' => $this->bundleSha256,
            'capabilities' => $this->capabilities,
            'channel' => $this->channel->value,
            'protocolVersion' => $this->protocolVersion,
            'rolloutBasisPoints' => $this->rolloutBasisPoints,
            'version' => 1,
        ];
    }

    /** @throws JsonException */
    public function canonicalJson(): string
    {
        return json_encode($this->payload(), JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES);
    }

    /** @param array<string,mixed> $payload */
    public static function fromPayload(array $payload): self
    {
        $expected = ['abiVersion', 'buildId', 'bundleSha256', 'capabilities', 'channel', 'protocolVersion', 'rolloutBasisPoints', 'version'];
        $keys = array_keys($payload);
        sort($keys, SORT_STRING);
        if ($keys !== $expected || ($payload['version'] ?? null) !== 1 || !is_array($payload['capabilities'])) {
            throw new InvalidArgumentException('Update manifest schema is invalid.');
        }
        return new self(
            (string) $payload['buildId'],
            (string) $payload['bundleSha256'],
            (int) $payload['abiVersion'],
            (int) $payload['protocolVersion'],
            UpdateChannel::from((int) $payload['channel']),
            (int) $payload['rolloutBasisPoints'],
            array_values($payload['capabilities']),
        );
    }
}
