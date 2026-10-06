<?php

declare(strict_types=1);

/*
 * Regenerates packages/native/tests/Fixtures/crypto-vectors.json from a PHP
 * build with ext-sodium and ext-openssl: `pam scripts/generate-crypto-vectors.php`.
 *
 * Every expected result comes from libsodium (Ed25519) and OpenSSL
 * (AES-256-GCM). The PHP SDK tests, the Android tests (JVM and emulator) and
 * the iOS XCTests replay the same file against Pam\Native\Crypto's native
 * backends, which must give identical bytes and identical accept/reject
 * decisions. Inputs are derived from fixed seeds, so the file is stable.
 *
 * The Ed25519 set covers libsodium's verification rules beyond plain
 * RFC 8032: S must be canonical (S < L), R and A must not be one of the
 * small-order encodings, A must be canonical (y < p) and decode, and the
 * check is cofactorless ([S]B - [h]A must encode to exactly R). The
 * mixed-order vectors (A plus an order-8 point) are built with bcmath below.
 */

if (!function_exists('sodium_crypto_sign_verify_detached') || !function_exists('openssl_encrypt') || !extension_loaded('bcmath')) {
    fwrite(STDERR, "generate-crypto-vectors: needs ext-sodium, ext-openssl and ext-bcmath (use `pam`).\n");
    exit(1);
}

$target = dirname(__DIR__).'/packages/native/tests/Fixtures/crypto-vectors.json';

$bytes = static function (string $label, int $length): string {
    $output = '';
    for ($block = 0; strlen($output) < $length; $block++) {
        $output .= hash('sha512', $label."\0".$block, true);
    }

    return substr($output, 0, $length);
};

// Minimal Ed25519 arithmetic (RFC 8032 section 5.1) for the vectors libsodium cannot build.
final class Ed25519Reference
{
    public const string P = '57896044618658097711785492504343953926634992332820282019728792003956564819949';
    public const string L = '7237005577332262213973186563042994240857116359379907606001950938285454250989';

    public static function mod(string $value, string $modulus = self::P): string
    {
        $result = bcmod($value, $modulus, 0);

        return bccomp($result, '0') < 0 ? bcadd($result, $modulus, 0) : $result;
    }

    public static function inverse(string $value): string
    {
        return bcpowmod(self::mod($value), bcsub(self::P, '2', 0), self::P, 0);
    }

    public static function d(): string
    {
        return self::mod(bcmul('-121665', self::inverse('121666'), 0));
    }

    public static function fromLittleEndian(string $bytes): string
    {
        $value = '0';
        for ($index = strlen($bytes) - 1; $index >= 0; $index--) {
            $value = bcadd(bcmul($value, '256', 0), (string) ord($bytes[$index]), 0);
        }

        return $value;
    }

    public static function toLittleEndian(string $value): string
    {
        $bytes = '';
        for ($index = 0; $index < 32; $index++) {
            $bytes .= chr((int) bcmod($value, '256', 0));
            $value = bcdiv($value, '256', 0);
        }

        return $bytes;
    }

    /** @return array{string, string, string, string} */
    public static function decode(string $encoded): array
    {
        $last = ord($encoded[31]);
        $sign = $last >> 7;
        $y = self::fromLittleEndian(substr($encoded, 0, 31).chr($last & 0x7F));
        $y2 = bcmul($y, $y, 0);
        $x2 = self::mod(bcmul(bcsub($y2, '1', 0), self::inverse(bcadd(bcmul(self::d(), $y2, 0), '1', 0)), 0));
        $x = bcpowmod($x2, bcdiv(bcadd(self::P, '3', 0), '8', 0), self::P, 0);
        if (self::mod(bcsub(bcmul($x, $x, 0), $x2, 0)) !== '0') {
            $x = self::mod(bcmul($x, bcpowmod('2', bcdiv(bcsub(self::P, '1', 0), '4', 0), self::P, 0), 0));
        }
        if (self::mod(bcsub(bcmul($x, $x, 0), $x2, 0)) !== '0') {
            throw new RuntimeException('Not a curve point.');
        }
        if ((int) bcmod($x, '2', 0) !== $sign) {
            $x = self::mod(bcsub('0', $x, 0));
        }

        return [$x, $y, '1', self::mod(bcmul($x, $y, 0))];
    }

    /** @param array{string, string, string, string} $point */
    public static function encode(array $point): string
    {
        $inverse = self::inverse($point[2]);
        $x = self::mod(bcmul($point[0], $inverse, 0));
        $y = self::mod(bcmul($point[1], $inverse, 0));
        $encoded = self::toLittleEndian($y);

        return substr($encoded, 0, 31).chr(ord($encoded[31]) | ((int) bcmod($x, '2', 0) << 7));
    }

    /**
     * @param array{string, string, string, string} $a
     * @param array{string, string, string, string} $b
     * @return array{string, string, string, string}
     */
    public static function add(array $a, array $b): array
    {
        $p = static fn (string $value): string => self::mod($value);
        $termA = $p(bcmul(bcsub($a[1], $a[0], 0), bcsub($b[1], $b[0], 0), 0));
        $termB = $p(bcmul(bcadd($a[1], $a[0], 0), bcadd($b[1], $b[0], 0), 0));
        $termC = $p(bcmul(bcmul(bcmul($a[3], '2', 0), self::d(), 0), $b[3], 0));
        $termD = $p(bcmul(bcmul($a[2], '2', 0), $b[2], 0));
        $e = bcsub($termB, $termA, 0);
        $f = bcsub($termD, $termC, 0);
        $g = bcadd($termD, $termC, 0);
        $h = bcadd($termB, $termA, 0);

        return [$p(bcmul($e, $f, 0)), $p(bcmul($g, $h, 0)), $p(bcmul($f, $g, 0)), $p(bcmul($e, $h, 0))];
    }

    /**
     * @param array{string, string, string, string} $point
     * @return array{string, string, string, string}
     */
    public static function multiply(string $scalar, array $point): array
    {
        $result = ['0', '1', '1', '0'];
        while (bccomp($scalar, '0') > 0) {
            if (bcmod($scalar, '2', 0) === '1') {
                $result = self::add($result, $point);
            }
            $point = self::add($point, $point);
            $scalar = bcdiv($scalar, '2', 0);
        }

        return $result;
    }

    /** @return array{string, string, string, string} */
    public static function base(): array
    {
        $y = self::mod(bcmul('4', self::inverse('5'), 0));

        return self::decode(self::toLittleEndian($y));
    }

    public static function scalar(string $bytes): string
    {
        return self::mod(self::fromLittleEndian($bytes), self::L);
    }
}

$hex = static fn (string $value): string => bin2hex($value);
$ed25519 = [];
$addSignature = static function (string $name, string $publicKey, string $signature, string $message, ?bool $expected = null) use (&$ed25519, $hex): void {
    $valid = strlen($signature) === 64 && strlen($publicKey) === 32
        && sodium_crypto_sign_verify_detached($signature, $message, $publicKey);
    if ($expected !== null && $valid !== $expected) {
        throw new RuntimeException("libsodium disagrees with the construction of {$name}.");
    }
    $ed25519[] = [
        'name' => $name,
        'publicKey' => $hex($publicKey),
        'signature' => $hex($signature),
        'message' => $hex($message),
        'valid' => $valid,
    ];
};

// RFC 8032 section 7.1 TEST 1-3.
$rfc = [
    ['9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60', '', 'e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b'],
    ['4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb', '72', '92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00'],
    ['c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7', 'af82', '6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a'],
];
foreach ($rfc as $index => [$seed, $message, $signature]) {
    $pair = sodium_crypto_sign_seed_keypair(hex2bin($seed));
    if (sodium_crypto_sign_detached(hex2bin($message), sodium_crypto_sign_secretkey($pair)) !== hex2bin($signature)) {
        throw new RuntimeException('libsodium does not reproduce RFC 8032.');
    }
    $addSignature('rfc8032 test '.($index + 1), sodium_crypto_sign_publickey($pair), hex2bin($signature), hex2bin($message), true);
}

$lengths = [0, 1, 31, 32, 33, 64, 127, 128, 1000, 4096];
$signed = [];
foreach ($lengths as $index => $length) {
    $pair = sodium_crypto_sign_seed_keypair($bytes("ed25519 seed {$index}", 32));
    $message = $bytes("ed25519 message {$index}", $length);
    $signature = sodium_crypto_sign_detached($message, sodium_crypto_sign_secretkey($pair));
    $publicKey = sodium_crypto_sign_publickey($pair);
    $signed[] = [$publicKey, $signature, $message, sodium_crypto_sign_secretkey($pair)];
    $addSignature("valid {$length}-byte message", $publicKey, $signature, $message, true);
}

[$publicKey, $signature, $message, $secretKey] = $signed[5];
$flip = static fn (string $value, int $offset, int $mask = 1): string => substr_replace($value, chr(ord($value[$offset]) ^ $mask), $offset, 1);
$addSignature('message bit flipped', $publicKey, $signature, $flip($message, 7), false);
$addSignature('message extended', $publicKey, $signature, $message."\0", false);
$addSignature('R bit flipped', $publicKey, $flip($signature, 3), $message, false);
$addSignature('S bit flipped', $publicKey, $flip($signature, 40), $message, false);
$addSignature('public key bit flipped (other key)', $flip($publicKey, 9), $signature, $message, false);
$addSignature('public key sign bit flipped (-A)', $flip($publicKey, 31, 0x80), $signature, $message, false);
$addSignature('signature of another message', $publicKey, $signed[4][1], $message, false);
$addSignature('signature under another key', $signed[4][0], $signature, $message, false);

// S + L verifies under a lenient (non-canonical S) verifier; libsodium rejects it.
$s = Ed25519Reference::fromLittleEndian(substr($signature, 32));
$addSignature('S + L (non-canonical)', $publicKey, substr($signature, 0, 32).Ed25519Reference::toLittleEndian(bcadd($s, Ed25519Reference::L, 0)), $message, false);
$addSignature('S = L', $publicKey, substr($signature, 0, 32).Ed25519Reference::toLittleEndian(Ed25519Reference::L), $message, false);
$addSignature('S top bits set', $publicKey, $flip($signature, 63, 0xE0), $message, false);
$addSignature('S = L - 1 (canonical, wrong)', $publicKey, substr($signature, 0, 32).Ed25519Reference::toLittleEndian(bcsub(Ed25519Reference::L, '1', 0)), $message, false);

// libsodium's small-order encodings (sign bit ignored).
$smallOrder = [
    'order 4 (y=0)' => '0000000000000000000000000000000000000000000000000000000000000000',
    'identity (y=1)' => '0100000000000000000000000000000000000000000000000000000000000000',
    'order 8 (a)' => '26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc05',
    'order 8 (b)' => 'c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac037a',
    'order 2 (y=p-1)' => 'ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f',
    'y=p (non-canonical 0)' => 'edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f',
    'y=p+1 (non-canonical 1)' => 'eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f',
];
$identity = hex2bin($smallOrder['identity (y=1)']);
foreach ($smallOrder as $label => $encoding) {
    foreach ([0, 0x80] as $signBit) {
        $point = hex2bin($encoding);
        $point[31] = chr(ord($point[31]) | $signBit);
        $suffix = $signBit === 0 ? '' : ', sign bit set';
        // A small-order key with R = identity and S = 0 satisfies the
        // equation for every message: a verifier without the check accepts.
        $addSignature("small-order public key {$label}{$suffix}", $point, $identity.str_repeat("\0", 32), $message, false);
        $addSignature("small-order public key {$label}{$suffix}, R = A", $point, $point.str_repeat("\0", 32), $message, false);
        $addSignature("small-order R {$label}{$suffix}", $publicKey, $point.substr($signature, 32), $message, false);
    }
}

// Non-canonical (y >= p) and undecodable public keys.
foreach (['efffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f', 'f0ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f', 'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff', 'f3ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff'] as $index => $encoding) {
    $addSignature("non-canonical public key {$index}", hex2bin($encoding), $signature, $message, false);
}
$undecodable = 0;
for ($index = 0; $undecodable < 3; $index++) {
    $candidate = $bytes("ed25519 off-curve {$index}", 32);
    $candidate[31] = chr(ord($candidate[31]) & 0x7F);
    try {
        Ed25519Reference::decode($candidate);
    } catch (RuntimeException) {
        $addSignature("public key not on the curve {$undecodable}", $candidate, $signature, $message, false);
        $undecodable++;
    }
}

// Mixed-order public key A + T (T of order 8). A cofactorless verifier
// (libsodium) accepts a signature only when h is a multiple of 8; a
// cofactored one accepts both. Both outcomes are pinned.
$secret = hash('sha512', substr($signed[2][3], 0, 32), true);
$clamped = $secret;
$clamped[0] = chr(ord($clamped[0]) & 248);
$clamped[31] = chr((ord($clamped[31]) & 127) | 64);
$a = Ed25519Reference::fromLittleEndian(substr($clamped, 0, 32));
$base = Ed25519Reference::base();
if (Ed25519Reference::encode(Ed25519Reference::multiply($a, $base)) !== $signed[2][0]) {
    throw new RuntimeException('The bcmath reference does not match libsodium.');
}
$mixed = Ed25519Reference::encode(Ed25519Reference::add(
    Ed25519Reference::decode($signed[2][0]),
    Ed25519Reference::decode(hex2bin($smallOrder['order 8 (b)'])),
));
$found = [true => false, false => false];
for ($counter = 0; !$found[true] || !$found[false]; $counter++) {
    $mixedMessage = "mixed-order public key {$counter}";
    $r = Ed25519Reference::scalar(hash('sha512', 'mixed nonce '.$counter, true));
    $encodedR = Ed25519Reference::encode(Ed25519Reference::multiply($r, $base));
    $h = Ed25519Reference::scalar(hash('sha512', $encodedR.$mixed.$mixedMessage, true));
    $cofactorless = bcmod($h, '8', 0) === '0';
    if ($found[$cofactorless]) {
        continue;
    }
    $found[$cofactorless] = true;
    $sValue = Ed25519Reference::mod(bcadd($r, bcmul($h, $a, 0), 0), Ed25519Reference::L);
    $addSignature(
        $cofactorless ? 'mixed-order public key, h multiple of 8 (cofactorless accepts)' : 'mixed-order public key, h not multiple of 8 (only cofactored accepts)',
        $mixed,
        $encodedR.Ed25519Reference::toLittleEndian($sValue),
        $mixedMessage,
        $cofactorless,
    );
}

$aes = [];
$aesFailures = [];
$seal = static function (string $plaintext, string $key, string $nonce, string $aad): string {
    $tag = '';
    $ciphertext = openssl_encrypt($plaintext, 'aes-256-gcm', $key, OPENSSL_RAW_DATA, $nonce, $tag, $aad, 16);
    if (!is_string($ciphertext) || strlen($tag) !== 16) {
        throw new RuntimeException('OpenSSL failed to seal.');
    }

    return $ciphertext.$tag;
};
$addAes = static function (string $name, string $key, string $nonce, string $aad, string $plaintext) use (&$aes, $seal, $hex): void {
    $sealed = $seal($plaintext, $key, $nonce, $aad);
    if (function_exists('sodium_crypto_aead_aes256gcm_is_available') && sodium_crypto_aead_aes256gcm_is_available()
        && sodium_crypto_aead_aes256gcm_encrypt($plaintext, $aad, $nonce, $key) !== $sealed) {
        throw new RuntimeException('libsodium and OpenSSL disagree on AES-256-GCM.');
    }
    $aes[] = [
        'name' => $name,
        'key' => $hex($key),
        'nonce' => $hex($nonce),
        'aad' => $hex($aad),
        'plaintext' => $hex($plaintext),
        'ciphertext' => $hex($sealed),
    ];
};

// McGrew & Viega, GCM test case 16 (AES-256, 96-bit IV, AAD).
$addAes(
    'gcm spec test case 16',
    hex2bin('feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308'),
    hex2bin('cafebabefacedbaddecaf888'),
    hex2bin('feedfacedeadbeeffeedfacedeadbeefabaddad2'),
    hex2bin('d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a721c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b39'),
);
if ($aes[0]['ciphertext'] !== '522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555d1aa8cb08e48590dbb3da7b08b1056828838c5f61e6393ba7a0abcc9f662'.'76fc6ece0f4e1768cddf8853bb2d551b') {
    throw new RuntimeException('OpenSSL does not reproduce the GCM specification.');
}
$aads = ['' => '', 'journal' => 'PAM-NATIVE-LF1', 'long' => null];
foreach ([0, 1, 15, 16, 17, 31, 32, 33, 64, 255, 1000, 4096] as $index => $length) {
    foreach ($aads as $label => $aad) {
        $aad ??= $bytes("aes aad {$index}", 300);
        $addAes(
            "{$length}-byte plaintext, {$label} aad",
            $bytes("aes key {$index}", 32),
            $bytes("aes nonce {$index} {$label}", 12),
            $aad,
            $bytes("aes plaintext {$index}", $length),
        );
    }
}

$key = $bytes('aes failure key', 32);
$nonce = $bytes('aes failure nonce', 12);
$plaintext = $bytes('aes failure plaintext', 100);
$sealed = $seal($plaintext, $key, $nonce, 'PAM-NATIVE-LF1');
$addFailure = static function (string $name, string $failureKey, string $failureNonce, string $aad, string $ciphertext) use (&$aesFailures, $hex): void {
    if (strlen($ciphertext) >= 16 && openssl_decrypt(substr($ciphertext, 0, -16), 'aes-256-gcm', $failureKey, OPENSSL_RAW_DATA, $failureNonce, substr($ciphertext, -16), $aad) !== false) {
        throw new RuntimeException("OpenSSL opened the failure vector {$name}.");
    }
    $aesFailures[] = [
        'name' => $name,
        'key' => $hex($failureKey),
        'nonce' => $hex($failureNonce),
        'aad' => $hex($aad),
        'ciphertext' => $hex($ciphertext),
    ];
};
$addFailure('tag bit flipped', $key, $nonce, 'PAM-NATIVE-LF1', $flip($sealed, strlen($sealed) - 1));
$addFailure('ciphertext bit flipped', $key, $nonce, 'PAM-NATIVE-LF1', $flip($sealed, 10));
$addFailure('aad changed', $key, $nonce, 'PAM-NATIVE-LF2', $sealed);
$addFailure('aad missing', $key, $nonce, '', $sealed);
$addFailure('wrong key', $flip($key, 0), $nonce, 'PAM-NATIVE-LF1', $sealed);
$addFailure('wrong nonce', $key, $flip($nonce, 11), 'PAM-NATIVE-LF1', $sealed);
$addFailure('truncated by one byte', $key, $nonce, 'PAM-NATIVE-LF1', substr($sealed, 0, -1));
$addFailure('ciphertext without its tag', $key, $nonce, 'PAM-NATIVE-LF1', substr($sealed, 0, -16));
$addFailure('only a tag (wrong)', $key, $nonce, 'PAM-NATIVE-LF1', substr($sealed, -16));
$addFailure('shorter than a tag', $key, $nonce, 'PAM-NATIVE-LF1', substr($sealed, 0, 15));
$addFailure('empty', $key, $nonce, 'PAM-NATIVE-LF1', '');

$document = [
    'version' => 1,
    'source' => sprintf('libsodium %s, %s', SODIUM_LIBRARY_VERSION, OPENSSL_VERSION_TEXT),
    'ed25519' => $ed25519,
    'aes256gcm' => $aes,
    'aes256gcmOpenFailures' => $aesFailures,
];
file_put_contents($target, json_encode($document, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES | JSON_THROW_ON_ERROR)."\n");
fprintf(STDOUT, "%s: %d Ed25519, %d AES-256-GCM, %d AES-256-GCM failure vectors\n", $target, count($ed25519), count($aes), count($aesFailures));
