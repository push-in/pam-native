<?php

declare(strict_types=1);

namespace Pam\Native\Update;

use JsonException;
use Pam\Native\Protocol;
use Throwable;

final class UpdateVerifier
{
    private const int MAX_MANIFEST_BYTES = 65_536;

    private function __construct()
    {
    }

    public static function evaluate(
        string $manifestJson,
        string $signatureBase64,
        string $publicKeyBase64,
        string $currentBuildIdentifier,
        int $rolloutBucket,
    ): UpdateDecision {
        if (strlen($manifestJson) > self::MAX_MANIFEST_BYTES || $rolloutBucket < 0 || $rolloutBucket >= 10_000) {
            return new UpdateDecision(UpdateDecisionStatus::InvalidManifest, 'Update input is outside safety bounds.');
        }
        try {
            $payload = json_decode($manifestJson, true, 64, JSON_THROW_ON_ERROR);
            if (!is_array($payload) || array_is_list($payload)) {
                throw new \InvalidArgumentException('Expected a manifest object.');
            }
            $manifest = SignedUpdateManifest::fromPayload($payload);
            if (!hash_equals($manifest->canonicalJson(), $manifestJson)) {
                throw new \InvalidArgumentException('Manifest is not canonical JSON.');
            }
        } catch (Throwable $error) {
            return new UpdateDecision(UpdateDecisionStatus::InvalidManifest, $error->getMessage());
        }

        $signature = base64_decode($signatureBase64, true);
        $publicKey = base64_decode($publicKeyBase64, true);
        if (!is_string($signature) || strlen($signature) !== SODIUM_CRYPTO_SIGN_BYTES
            || !is_string($publicKey) || strlen($publicKey) !== SODIUM_CRYPTO_SIGN_PUBLICKEYBYTES
            || !sodium_crypto_sign_verify_detached($signature, $manifestJson, $publicKey)) {
            return new UpdateDecision(UpdateDecisionStatus::InvalidSignature, 'Update signature verification failed.');
        }
        if ($manifest->abiVersion !== Protocol::ABI_VERSION || !Protocol::supports($manifest->protocolVersion)
            || array_diff($manifest->capabilities, Protocol::CAPABILITIES) !== []) {
            return new UpdateDecision(UpdateDecisionStatus::Incompatible, 'Update requires an incompatible runtime contract.', $manifest);
        }
        if (hash_equals($currentBuildIdentifier, $manifest->buildIdentifier)) {
            return new UpdateDecision(UpdateDecisionStatus::AlreadyCurrent, 'The update is already active.', $manifest);
        }
        if ($rolloutBucket >= $manifest->rolloutBasisPoints) {
            return new UpdateDecision(UpdateDecisionStatus::OutsideRollout, 'The installation is outside this rollout.', $manifest);
        }
        return new UpdateDecision(UpdateDecisionStatus::Approved, 'Update is verified and compatible.', $manifest);
    }

    public static function verifyBundle(string $path, SignedUpdateManifest $manifest): bool
    {
        return is_file($path) && ($hash = hash_file('sha256', $path)) !== false
            && hash_equals($manifest->bundleSha256, $hash);
    }
}
