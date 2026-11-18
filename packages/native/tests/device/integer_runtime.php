<?php

declare(strict_types=1);

/*
 * PAM's Android runtimes keep 64-bit PHP integers on 32-bit ABIs
 * (armeabi-v7a, x86): the SDK relies on them for ARGB colors and node ids.
 * Run with scripts/android-php-runtime-test.sh on a 32-bit device or emulator
 * (and on 64-bit ones, where it must pass unchanged).
 */

$failures = [];
$check = static function (bool $condition, string $message) use (&$failures): void {
    if (!$condition) {
        $failures[] = $message;
    }
};

$check(PHP_INT_SIZE === 8, 'PHP_INT_SIZE must be 8, got '.PHP_INT_SIZE);
$check(PHP_INT_MAX === 9223372036854775807, 'PHP_INT_MAX must be 2^63-1');
$check(is_int(0xFF163D2A) && 0xFF163D2A === 4279647530, 'ARGB colors must stay integers');
$check((0xFF163D2A >> 24) === 0xFF && (0xFF163D2A & 0xFFFFFF) === 0x163D2A, 'ARGB channel masks');
$check((1 << 40) === 1099511627776 && (-1 >> 40) === -1, '64-bit shifts');
$check(intdiv(PHP_INT_MAX, 3) === 3074457345618258602, 'intdiv on 64-bit operands');
$check(PHP_INT_MAX + 1 === 9.2233720368547758E+18, 'overflow still promotes to float');
$check((int) '9007199254740993' === 9007199254740993, 'string to int keeps 64 bits');
$check(sprintf('%d|%x', 4294967296, 0xFFFFFFFFFF) === '4294967296|ffffffffff', 'sprintf with 64-bit integers');
$check(json_encode([4294967297]) === '[4294967297]', 'json_encode keeps 64-bit integers');
$check(json_decode('4294967297') === 4294967297, 'json_decode keeps 64-bit integers');
$check(unpack('q', pack('q', -4294967297))[1] === -4294967297, 'pack/unpack q');
$check(array_keys([4294967296 => 'a', 2 => 'b']) === [4294967296, 2], '64-bit array keys');
$check(crc32('The quick brown fox jumped over the lazy dog.') === 2191738434, 'crc32 is unsigned');
$check(random_int(4294967296, 4294967296) === 4294967296, 'random_int with 64-bit bounds');

// Zend keeps flags in the low bits of Bucket offsets (closure `use` and
// static variables): every captured value must come back.
$first = 'first';
$second = 'second';
$third = 3;
$counter = 0;
$closure = static function () use ($first, $second, $third, &$counter): array {
    $counter++;

    return [$first, $second, $third];
};
$check($closure() === ['first', 'second', 3] && $counter === 1, 'closure use variables');
$arrow = static fn (): array => [$first, $second, $third];
$check($arrow() === ['first', 'second', 3], 'arrow function captures');
$statics = static function (): array {
    static $calls = 0;
    static $label = 'label';
    static $big = 4294967296;
    $calls++;

    return [$calls, $label, $big];
};
$statics();
$check($statics() === [2, 'label', 4294967296], 'static variables');

try {
    (static fn (string $value): string => $value)(null);
    $check(false, 'TypeError expected for a null string argument');
} catch (TypeError $error) {
    $check(str_contains($error->getMessage(), 'must be of type string, null given'), 'TypeError message');
}

if ($failures !== []) {
    echo 'PAM_INTEGER_RUNTIME_FAILED '.implode('; ', $failures).PHP_EOL;
    exit(1);
}
echo 'PAM_INTEGER_RUNTIME_OK php='.PHP_VERSION.' int='.PHP_INT_SIZE.' arch='.php_uname('m').PHP_EOL;
