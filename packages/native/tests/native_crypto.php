<?php

declare(strict_types=1);

/*
 * Pam\Native\Crypto must give libsodium's and OpenSSL's exact bytes and
 * decisions whichever backend runs: the extensions on desktop, the
 * Android/iOS host (pam_native_crypto()) on a device.
 *
 * - Fixtures/crypto-vectors.json (scripts/generate-crypto-vectors.php) is
 *   replayed through every backend, and re-derived live from ext-sodium and
 *   ext-openssl when the host PHP has them (pam does).
 * - The native path runs against a fake of the host function that follows
 *   its C contract (operation codes, argument order, string|bool|null).
 * - UpdateVerifier and EncryptedJournal interoperate across backends and
 *   fail closed when no backend exists, as on a device with an old host.
 *
 * Standalone: `pam packages/native/tests/native_crypto.php`.
 */

use Pam\Native\Crypto;
use Pam\Native\CryptoBackend;
use Pam\Native\CryptoUnavailableException;
use Pam\Native\LocalFirst\EncryptedJournal;
use Pam\Native\Update\SignedUpdateManifest;
use Pam\Native\Update\UpdateChannel;
use Pam\Native\Update\UpdateDecisionStatus;
use Pam\Native\Update\UpdateVerifier;

if (!isset($assert)) {
    spl_autoload_register(static function (string $class): void {
        $prefix = 'Pam\\Native\\';
        if (str_starts_with($class, $prefix)) {
            $path = __DIR__.'/../src/'.str_replace('\\', '/', substr($class, strlen($prefix))).'.php';
            if (is_file($path)) {
                require $path;
            }
        }
    });
    $assert = static function (bool $condition, string $message): void {
        if (!$condition) {
            throw new RuntimeException($message);
        }
    };
}

final class NativeCryptoFake
{
    /** @var list<int> operations received, in order */
    public static array $operations = [];

    /** Simulates a host without a crypto provider (the function returns null). */
    public static bool $missingProvider = false;
}

if (!function_exists('pam_native_crypto')) {
    /**
     * Fake of the host function (pam_android_bridge.cpp / pam_native_ios_bridge.cpp):
     * 1 = Ed25519 verify (key = public key, nonce = signature, input = message) -> bool;
     * 2 = AES-256-GCM seal (input = plaintext) -> ciphertext . tag | false;
     * 3 = AES-256-GCM open (input = ciphertext . tag) -> plaintext | false.
     */
    function pam_native_crypto(int $operation, string $key, string $nonce, string $aad, string $input): string|bool|null
    {
        NativeCryptoFake::$operations[] = $operation;
        if (NativeCryptoFake::$missingProvider) {
            return null;
        }

        return match ($operation) {
            1 => strlen($key) === 32 && strlen($nonce) === 64 && sodium_crypto_sign_verify_detached($nonce, $input, $key),
            2 => strlen($key) !== 32 || strlen($nonce) !== 12 ? false : (static function () use ($input, $key, $nonce, $aad): string|false {
                $tag = '';
                $ciphertext = openssl_encrypt($input, 'aes-256-gcm', $key, OPENSSL_RAW_DATA, $nonce, $tag, $aad, 16);

                return is_string($ciphertext) ? $ciphertext.$tag : false;
            })(),
            3 => strlen($key) !== 32 || strlen($nonce) !== 12 || strlen($input) < 16 ? false
                : openssl_decrypt(substr($input, 0, -16), 'aes-256-gcm', $key, OPENSSL_RAW_DATA, $nonce, substr($input, -16), $aad),
            default => false,
        };
    }
}

$vectors = json_decode((string) file_get_contents(__DIR__.'/Fixtures/crypto-vectors.json'), true, 16, JSON_THROW_ON_ERROR);
$assert(is_array($vectors) && ($vectors['version'] ?? null) === 1, 'The crypto vectors must load.');

$hasExtensions = function_exists('sodium_crypto_sign_verify_detached') && function_exists('openssl_encrypt');
$replay = static function (string $label) use ($vectors, $assert): void {
    $failures = [];
    foreach ($vectors['ed25519'] as $vector) {
        $verified = Crypto::ed25519Verify(hex2bin($vector['signature']), hex2bin($vector['message']), hex2bin($vector['publicKey']));
        if ($verified !== $vector['valid']) {
            $failures[] = "Ed25519 {$vector['name']}: ".var_export($verified, true);
        }
    }
    foreach ($vectors['aes256gcm'] as $vector) {
        [$key, $nonce, $aad, $plaintext, $sealed] = array_map('hex2bin', [$vector['key'], $vector['nonce'], $vector['aad'], $vector['plaintext'], $vector['ciphertext']]);
        if (Crypto::aes256GcmEncrypt($plaintext, $key, $nonce, $aad) !== $sealed) {
            $failures[] = "AES-256-GCM seal {$vector['name']}";
        }
        if (Crypto::aes256GcmDecrypt($sealed, $key, $nonce, $aad) !== $plaintext) {
            $failures[] = "AES-256-GCM open {$vector['name']}";
        }
    }
    foreach ($vectors['aes256gcmOpenFailures'] as $vector) {
        if (Crypto::aes256GcmDecrypt(hex2bin($vector['ciphertext']), hex2bin($vector['key']), hex2bin($vector['nonce']), hex2bin($vector['aad'])) !== null) {
            $failures[] = "AES-256-GCM failure {$vector['name']} opened";
        }
    }
    $assert($failures === [], "{$label} backend disagrees with libsodium/OpenSSL:\n".implode("\n", $failures));
};

$assert(
    count($vectors['ed25519']) >= 70
        && count(array_filter($vectors['ed25519'], static fn (array $vector): bool => $vector['valid'])) >= 14
        && in_array('mixed-order public key, h not multiple of 8 (only cofactored accepts)', array_column($vectors['ed25519'], 'name'), true)
        && in_array('S + L (non-canonical)', array_column($vectors['ed25519'], 'name'), true)
        && count($vectors['aes256gcm']) >= 30 && count($vectors['aes256gcmOpenFailures']) >= 10,
    'The crypto vectors must cover valid, malformed, non-canonical, small-order and mixed-order Ed25519 cases and AES-256-GCM failures.',
);

// The Android instrumented tests read a copy packaged as an androidTest asset.
$androidCopy = __DIR__.'/../../../android/app/src/androidTest/assets/crypto-vectors.json';
if (is_file(__DIR__.'/../../../android/app/build.gradle.kts')) {
    $assert(
        is_file($androidCopy) && hash_file('sha256', $androidCopy) === hash_file('sha256', __DIR__.'/Fixtures/crypto-vectors.json'),
        'android/app/src/androidTest/assets/crypto-vectors.json must be a copy of packages/native/tests/Fixtures/crypto-vectors.json.',
    );
}

if ($hasExtensions) {
    // The recorded vectors are what the extensions in this PHP build return.
    $mismatches = [];
    foreach ($vectors['ed25519'] as $vector) {
        $signature = hex2bin($vector['signature']);
        $publicKey = hex2bin($vector['publicKey']);
        if (sodium_crypto_sign_verify_detached($signature, hex2bin($vector['message']), $publicKey) !== $vector['valid']) {
            $mismatches[] = $vector['name'];
        }
    }
    foreach ($vectors['aes256gcm'] as $vector) {
        $tag = '';
        $ciphertext = openssl_encrypt(hex2bin($vector['plaintext']), 'aes-256-gcm', hex2bin($vector['key']), OPENSSL_RAW_DATA, hex2bin($vector['nonce']), $tag, hex2bin($vector['aad']), 16);
        if ($ciphertext.$tag !== hex2bin($vector['ciphertext'])) {
            $mismatches[] = $vector['name'];
        }
    }
    $assert($mismatches === [], "ext-sodium/ext-openssl no longer reproduce the recorded vectors:\n".implode("\n", $mismatches));

    Crypto::useBackend(null);
    $assert(
        Crypto::ed25519Backend() === CryptoBackend::Extension && Crypto::aes256GcmBackend() === CryptoBackend::Extension,
        'Loaded extensions must be preferred over the native host.',
    );
    $replay('Extension');

    // Seeded random inputs: the native path and the extensions agree byte for byte.
    mt_srand(20261006);
    $randomBytes = static fn (int $length): string => $length === 0 ? '' : implode('', array_map(static fn (): string => chr(mt_rand(0, 255)), range(1, $length)));
    for ($round = 0; $round < 40; $round++) {
        $pair = sodium_crypto_sign_seed_keypair($randomBytes(32));
        $message = $randomBytes(mt_rand(0, 2048));
        $signature = sodium_crypto_sign_detached($message, sodium_crypto_sign_secretkey($pair));
        $tampered = $signature;
        $offset = mt_rand(0, 63);
        $tampered[$offset] = chr(ord($tampered[$offset]) ^ (1 << mt_rand(0, 7)));
        $key = $randomBytes(32);
        $nonce = $randomBytes(12);
        $aad = $randomBytes(mt_rand(0, 64));
        $plaintext = $randomBytes(mt_rand(0, 4096));
        $results = [];
        foreach ([CryptoBackend::Extension, CryptoBackend::Native] as $backend) {
            Crypto::useBackend($backend);
            $sealed = Crypto::aes256GcmEncrypt($plaintext, $key, $nonce, $aad);
            $results[$backend->name] = [
                Crypto::ed25519Verify($signature, $message, sodium_crypto_sign_publickey($pair)),
                Crypto::ed25519Verify($tampered, $message, sodium_crypto_sign_publickey($pair)),
                $sealed,
                Crypto::aes256GcmDecrypt($sealed, $key, $nonce, $aad),
                Crypto::aes256GcmDecrypt($sealed, $key, $nonce, $aad."\0"),
            ];
        }
        $assert(
            $results['Extension'] === $results['Native'] && $results['Native'][0] === true
                && $results['Native'][1] === ($tampered === $signature) && $results['Native'][3] === $plaintext && $results['Native'][4] === null,
            "Native and extension crypto must agree on random input (round {$round}).",
        );
    }
    Crypto::useBackend(null);
}

$journalKey = str_repeat('k', 32);
$manifest = new SignedUpdateManifest(
    buildIdentifier: str_repeat('a', 64),
    bundleSha256: str_repeat('0', 64),
    abiVersion: \Pam\Native\Protocol::ABI_VERSION,
    protocolVersion: \Pam\Native\Protocol::VERSION,
    channel: UpdateChannel::Stable,
    rolloutBasisPoints: 10_000,
    capabilities: ['wire.binary.v1'],
);
$manifestJson = $manifest->canonicalJson();

if (!$hasExtensions) {
    // The fake host needs ext-sodium/ext-openssl; only the fail-closed checks below run.
    goto unavailable;
}

// The native host path: every vector through pam_native_crypto().
Crypto::useBackend(CryptoBackend::Native);
NativeCryptoFake::$operations = [];
$replay('Native');
$assert(
    count(NativeCryptoFake::$operations) === count($vectors['ed25519']) + 2 * count($vectors['aes256gcm'])
        + count(array_filter($vectors['aes256gcmOpenFailures'], static fn (array $vector): bool => strlen($vector['ciphertext']) >= 32))
        && array_values(array_unique(NativeCryptoFake::$operations)) === [1, 2, 3],
    'The native backend must route every well-formed call through pam_native_crypto() with operations 1-3.',
);

NativeCryptoFake::$operations = [];
$assert(
    Crypto::ed25519Verify(str_repeat("\1", 63), 'm', str_repeat("\2", 32)) === false
        && Crypto::ed25519Verify(str_repeat("\1", 64), 'm', str_repeat("\2", 31)) === false
        && Crypto::aes256GcmDecrypt(str_repeat("\0", 15), str_repeat('k', 32), str_repeat('n', 12)) === null
        && NativeCryptoFake::$operations === [],
    'Malformed signatures, keys and envelopes must be rejected before reaching the host.',
);
foreach ([[str_repeat('k', 31), str_repeat('n', 12)], [str_repeat('k', 32), str_repeat('n', 16)]] as [$badKey, $badNonce]) {
    try {
        Crypto::aes256GcmEncrypt('x', $badKey, $badNonce);
        $assert(false, 'AES-256-GCM must reject a key that is not 32 bytes and a nonce that is not 12 bytes.');
    } catch (InvalidArgumentException) {
    }
}

// EncryptedJournal: an envelope sealed by one backend opens on the other,
// and tampering is detected on both.
$entries = [['id' => 1, 'value' => 'secret', 'nested' => ['ação' => true]], ['id' => 2, 'value' => str_repeat('x', 5000)]];
$backends = [CryptoBackend::Extension, CryptoBackend::Native];
foreach ($backends as $sealer) {
    foreach ($backends as $opener) {
        Crypto::useBackend($sealer);
        $envelope = (new EncryptedJournal($journalKey))->seal($entries);
        Crypto::useBackend($opener);
        $opened = (new EncryptedJournal($journalKey))->open($envelope);
        $tampered = substr_replace($envelope, chr(ord($envelope[40]) ^ 1), 40, 1);
        try {
            (new EncryptedJournal($journalKey))->open($tampered);
            $rejected = false;
        } catch (RuntimeException $error) {
            $rejected = $error->getMessage() === 'Local-first journal authentication failed.';
        }
        $assert(
            $opened === $entries && str_starts_with($envelope, 'PNL1') && !str_contains($envelope, 'secret') && $rejected,
            "A journal sealed with the {$sealer->name} backend must open with the {$opener->name} backend and reject tampering.",
        );
    }
}
// Envelope layout: 'PNL1' . nonce . tag . ciphertext, AAD 'PAM-NATIVE-LF1'.
Crypto::useBackend(CryptoBackend::Native);
$envelope = (new EncryptedJournal($journalKey))->seal([['a' => 1]]);
$assert(
    Crypto::aes256GcmDecrypt(substr($envelope, 32).substr($envelope, 16, 16), $journalKey, substr($envelope, 4, 12), 'PAM-NATIVE-LF1') === '[{"a":1}]',
    'The local-first journal envelope layout must stay PNL1 . nonce . tag . ciphertext.',
);

// UpdateVerifier through the native host.
$pair = sodium_crypto_sign_seed_keypair(str_repeat("\7", 32));
$manifestSignature = base64_encode(sodium_crypto_sign_detached($manifestJson, sodium_crypto_sign_secretkey($pair)));
$manifestKey = base64_encode(sodium_crypto_sign_publickey($pair));
$badSignature = base64_encode(sodium_crypto_sign_detached($manifestJson.' ', sodium_crypto_sign_secretkey($pair)));
$statuses = [];
foreach ([CryptoBackend::Extension, CryptoBackend::Native] as $backend) {
    Crypto::useBackend($backend);
    NativeCryptoFake::$operations = [];
    $statuses[$backend->name] = [
        UpdateVerifier::evaluate($manifestJson, $manifestSignature, $manifestKey, str_repeat('b', 64), 0)->status,
        UpdateVerifier::evaluate($manifestJson, $badSignature, $manifestKey, str_repeat('b', 64), 0)->status,
        UpdateVerifier::evaluate($manifestJson, $manifestSignature, base64_encode(hex2bin('0100000000000000000000000000000000000000000000000000000000000000')), str_repeat('b', 64), 0)->status,
        UpdateVerifier::evaluate($manifestJson, base64_encode('short'), $manifestKey, str_repeat('b', 64), 0)->status,
        NativeCryptoFake::$operations,
    ];
}
$assert(
    $statuses['Native'][0] === UpdateDecisionStatus::Approved
        && array_slice($statuses['Native'], 1, 3) === array_fill(0, 3, UpdateDecisionStatus::InvalidSignature)
        && array_slice($statuses['Native'], 0, 4) === array_slice($statuses['Extension'], 0, 4)
        && $statuses['Native'][4] === [1, 1, 1] && $statuses['Extension'][4] === [],
    'OTA manifests must verify identically through libsodium and the native host.',
);

unavailable:
// No backend (a device whose host predates pam_native_crypto()): fail closed
// with an explanation instead of "Call to undefined function sodium_*".
Crypto::useBackend(CryptoBackend::Unavailable);
$unavailable = UpdateVerifier::evaluate($manifestJson, base64_encode(str_repeat("\0", 64)), base64_encode(str_repeat("\0", 32)), str_repeat('b', 64), 0);
$journalError = null;
try {
    (new EncryptedJournal($journalKey))->seal([['a' => 1]]);
} catch (CryptoUnavailableException $error) {
    $journalError = $error->getMessage();
}
$assert(
    $unavailable->status === UpdateDecisionStatus::InvalidSignature && str_contains($unavailable->message, 'pam_native_crypto()')
        && is_string($journalError) && str_contains($journalError, 'AES-256-GCM'),
    'Without a crypto backend, updates must be refused and journals must throw CryptoUnavailableException.',
);
Crypto::useBackend(CryptoBackend::Native);
NativeCryptoFake::$missingProvider = true;
try {
    Crypto::ed25519Verify(str_repeat("\0", 64), '', str_repeat("\0", 32));
    $missingProvider = null;
} catch (CryptoUnavailableException $error) {
    $missingProvider = $error->getMessage();
}
NativeCryptoFake::$missingProvider = false;
$assert(
    is_string($missingProvider) && str_contains($missingProvider, 'no crypto provider'),
    'A host function without a provider (null) must raise CryptoUnavailableException.',
);
Crypto::useBackend(null);
