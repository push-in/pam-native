<?php

declare(strict_types=1);

/*
 * The mobile runtimes have no ext-mbstring, so the SDK ships
 * Pam\Native\Polyfill\Mbstring (defined as the global mb_* functions by
 * src/Polyfill/mbstring.php). The fixed expectations below were recorded from
 * PHP 8.5's ext-mbstring and always run. When the host PHP has ext-mbstring
 * (pam does), every polyfilled function is also compared with the real one
 * over a fixed corpus plus seeded random valid and malformed strings, in
 * every supported encoding and substitution mode.
 *
 * Standalone: `pam packages/native/tests/mbstring_polyfill.php`.
 */

use Pam\Native\Polyfill\Mbstring;

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
    $standalone = true;
}

// Defines MB_CASE_* (and the functions) when the host has no ext-mbstring.
require_once __DIR__.'/../src/Polyfill/mbstring.php';

$polyfill = static fn (string $function, mixed ...$arguments): mixed => Mbstring::$function(...$arguments);
$capture = static function (callable $call): array {
    set_error_handler(static fn (): bool => true);
    try {
        return ['value', $call()];
    } catch (Throwable $error) {
        return [$error::class, $error->getMessage()];
    } finally {
        restore_error_handler();
    }
};

// ------------------------------------------------- recorded from ext-mbstring

$expectations = require __DIR__.'/Fixtures/mbstring_expectations.php';
foreach ($expectations as [$function, $arguments, $expected]) {
    if ($expected === null) {
        continue;
    }
    $actual = $capture(static fn (): mixed => $polyfill($function, ...$arguments))[1];
    $assert(
        $actual === $expected,
        sprintf('Polyfilled %s(%s) must return %s, got %s.', $function, json_encode($arguments, JSON_INVALID_UTF8_SUBSTITUTE), var_export($expected, true), var_export($actual, true)),
    );
}

$error = $capture(static fn (): mixed => $polyfill('mb_strlen', 'x', 'SJIS-nope'));
$assert(
    $error === [ValueError::class, 'mb_strlen(): Argument #2 ($encoding) must be a valid encoding, "SJIS-nope" given'],
    'An unknown encoding must raise the same ValueError as ext-mbstring.',
);
$error = $capture(static fn (): mixed => $polyfill('mb_strpos', 'abc', 'a', 4));
$assert(
    $error === [ValueError::class, 'mb_strpos(): Argument #3 ($offset) must be contained in argument #1 ($haystack)'],
    'An out-of-range offset must raise the same ValueError as ext-mbstring.',
);
$polyfill('mb_substitute_character', 'long');
$assert($polyfill('mb_convert_encoding', "aé😀\xFF", 'ASCII') === 'aU+E9U+1F600?', 'Substitution mode "long" must match ext-mbstring.');
$polyfill('mb_substitute_character', 'entity');
$assert($polyfill('mb_convert_encoding', "aé\xFF", 'ASCII') === 'a&#xE9;?', 'Substitution mode "entity" must match ext-mbstring.');
$polyfill('mb_substitute_character', 'none');
$assert($polyfill('mb_scrub', "a\xFFb") === 'ab', 'Substitution mode "none" must drop invalid sequences.');
$polyfill('mb_substitute_character', 0x3F);

$converted = ['x' => "caf\xE9"];
$second = "na\xEFve";
$detected = Mbstring::mb_convert_variables('UTF-8', 'ASCII, ISO-8859-1', $converted, $second);
$assert(
    $detected === 'ISO-8859-1' && $converted === ['x' => 'café'] && $second === 'naïve',
    'mb_convert_variables() must detect once and convert every argument by reference.',
);

// The bootstrap only defines what is missing and keeps the MB_CASE_* values.
$bootstrap = (string) file_get_contents(__DIR__.'/../src/Polyfill/mbstring.php');
foreach (Mbstring::FUNCTIONS as $function) {
    $assert(
        str_contains($bootstrap, "if (!function_exists('{$function}')) {\n    function {$function}("),
        "src/Polyfill/mbstring.php must define {$function}() only when it is missing.",
    );
}
$composer = json_decode((string) file_get_contents(__DIR__.'/../composer.json'), true, flags: JSON_THROW_ON_ERROR);
$rootComposer = json_decode((string) file_get_contents(__DIR__.'/../../../composer.json'), true, flags: JSON_THROW_ON_ERROR);
$assert(
    ($composer['autoload']['files'] ?? []) === ['src/Polyfill/mbstring.php']
        && ($rootComposer['autoload']['files'] ?? []) === ['packages/native/src/Polyfill/mbstring.php'],
    'Both composer.json files must autoload the mbstring polyfill before application code.',
);

// ------------------------------------------- differential, against ext-mbstring

if (extension_loaded('mbstring')) {
    mt_srand(20261006);
    $pools = [
        range(0x20, 0x7E),
        array_merge(range(0xC0, 0xFF), [0x130, 0x131, 0x149, 0x17F, 0x1C4, 0x1C5, 0x1C6, 0x1F0, 0xDF]),
        array_merge(range(0x391, 0x3A9), range(0x3B1, 0x3C9), [0x3C2, 0x390, 0x1F88, 0x1FB3]),
        [0x27, 0x2E, 0x3A, 0xAD, 0x2BC, 0x300, 0x301, 0x345, 0x2019, 0x200D, 0xFE0F],
        array_merge(range(0x3041, 0x3060), range(0x4E00, 0x4E20), range(0xAC00, 0xAC10), range(0xFF01, 0xFF20)),
        [0x1F600, 0x1F44D, 0x1F3FD, 0x1F468, 0x1F469, 0x1F467, 0x10400, 0x10428, 0x1E900],
        [0x20, 0x3000, 0x2003, 0x0A, 0x09, 0xA0, 0x85, 0x180E, 0x00],
        [0x26, 0x23, 0x78, 0x3B, 0x30, 0x31, 0x39, 0x41, 0x46],
    ];
    $random = static function (int $maximum) use ($pools): string {
        $string = '';
        $length = mt_rand(0, $maximum);
        for ($index = 0; $index < $length; $index++) {
            $pool = $pools[mt_rand(0, count($pools) - 1)];
            $string .= mb_chr($pool[mt_rand(0, count($pool) - 1)], 'UTF-8');
        }

        return $string;
    };
    $bytes = static function (int $maximum): string {
        $alphabet = "a?\x80\x8F\x9F\xA0\xBF\xC0\xC2\xC3\xDF\xE0\xE1\xED\xEF\xF0\xF1\xF4\xF5\xFF\xFE";
        $string = '';
        $length = mt_rand(0, $maximum);
        for ($index = 0; $index < $length; $index++) {
            $string .= $alphabet[mt_rand(0, strlen($alphabet) - 1)];
        }

        return $string;
    };
    $corpus = [
        '', 'a', 'Hello World', 'ação', 'ÀÉÎÕÜ çñ', 'straße', 'İstanbul', 'ΣΑΣ ΟΔΟΣ', 'ὈΔΥΣΣΕΎΣ',
        'Ǆemal ǅx ǆ', 'ﬃ ﬁ', "o'neil mc-donald", '日本語テキスト', '한국어', 'emoji 😀👍🏽 👨‍👩‍👧',
        'Ａ全角', "İ\u{307}", "tab\tnl\n", ' trim  ', "\u{3000}x\u{3000}", 'Σ', 'aΣ', 'ΑΣ.Σ', "a\u{AD}Σ\u{AD}",
        "\xFF", "a\x80b", "\xC3", "\xE0\xA0x", "\xF0\x9F\x98", "é\x80x", "\xED\xA0\x80", "\xF4\x90\x80\x80",
        "\xC0\xAF", "\xF1\x80\x41", "\xE1\x80", 'x&#65;&#x42;&#;&#x;&#1234567890123;&#xFFFFFFFFF;&#065',
    ];
    for ($index = 0; $index < 140; $index++) {
        $corpus[] = $random(14);
    }
    for ($index = 0; $index < 60; $index++) {
        $corpus[] = $bytes(9);
    }

    $mismatches = [];
    $compare = static function (string $function, array $arguments) use ($capture, &$mismatches): void {
        $expected = $capture(static fn (): mixed => $function(...$arguments));
        $actual = $capture(static fn (): mixed => Mbstring::$function(...$arguments));
        if ($expected !== $actual && count($mismatches) < 40) {
            $mismatches[] = sprintf(
                '%s(%s): ext %s, polyfill %s',
                $function,
                implode(', ', array_map(static fn (mixed $value): string => is_string($value) ? '0x'.bin2hex($value) : json_encode($value), $arguments)),
                json_encode($expected, JSON_INVALID_UTF8_SUBSTITUTE),
                json_encode($actual, JSON_INVALID_UTF8_SUBSTITUTE),
            );
        }
    };
    $samples = static fn (array $values, int $count): array => array_map(
        static fn (): mixed => $values[mt_rand(0, count($values) - 1)],
        range(1, $count),
    );

    $overshoots = static function (string $string, string $needle, int $offset, bool $reverse): bool {
        $size = strlen($string);
        $forward = static function (int $position, int $steps) use ($string, $size): ?int {
            for ($step = 0; $step < $steps; $step++) {
                if ($position >= $size) {
                    return null;
                }
                $byte = ord($string[$position]);
                $position += $byte >= 0xC2 && $byte < 0xF5 ? ($byte < 0xE0 ? 2 : ($byte < 0xF0 ? 3 : 4)) : 1;
            }

            return $position;
        };
        if ($offset >= 0) {
            return ($forward(0, $offset) ?? 0) > $size;
        }
        if (!$reverse) {
            return false;
        }
        $position = $size;
        for ($remaining = -$offset; $remaining > 0; $position--) {
            if ($position <= 0) {
                return false;
            }
            if ((ord($string[$position - 1]) & 0xC0) !== 0x80) {
                $remaining--;
            }
        }
        $needleLength = strlen($needle) - preg_match_all('/[\x80-\xBF]/', $needle);

        return ($forward($position, $needleLength) ?? 0) > $size;
    };
    $encodings = Mbstring::mb_list_encodings();
    foreach ($corpus as $string) {
        $length = strlen($string);
        $compare('mb_strlen', [$string]);
        $compare('mb_strwidth', [$string]);
        $compare('mb_check_encoding', [$string]);
        $compare('mb_scrub', [$string]);
        $compare('mb_ord', [$string === '' ? 'x' : $string]);
        foreach ([1, 2, 3] as $size) {
            $compare('mb_str_split', [$string, $size]);
        }
        foreach ([-9, -3, -1, 0, 1, 2, 5, 40] as $start) {
            foreach ([null, -4, -1, 0, 1, 3, 100] as $count) {
                $compare('mb_substr', [$string, $start, $count]);
            }
        }
        foreach ([-3, 0, 1, 2, 3, 5] as $start) {
            foreach ([null, -2, 0, 1, 2, 4] as $count) {
                $compare('mb_strcut', [$string, $start, $count]);
            }
        }
        for ($mode = 0; $mode <= 7; $mode++) {
            $compare('mb_convert_case', [$string, $mode]);
        }
        $compare('mb_strtoupper', [$string]);
        $compare('mb_strtolower', [$string]);
        $compare('mb_ucfirst', [$string]);
        $compare('mb_lcfirst', [$string]);
        foreach (['mb_trim', 'mb_ltrim', 'mb_rtrim'] as $function) {
            $compare($function, [$string]);
            $compare($function, [$string, ' aé']);
            $compare($function, [$string, '']);
        }
        foreach ([0, 2, 7, 12] as $padTo) {
            foreach ([' ', 'ab', 'é😀'] as $pad) {
                foreach ([STR_PAD_LEFT, STR_PAD_RIGHT, STR_PAD_BOTH] as $type) {
                    $compare('mb_str_pad', [$string, $padTo, $pad, $type]);
                }
            }
        }
        foreach ([0, 1, 3, -2] as $start) {
            foreach ([0, 1, 4, 9] as $width) {
                foreach (['', '...', '…'] as $marker) {
                    $compare('mb_strimwidth', [$string, $start, $width, $marker]);
                }
            }
        }
        $needles = array_merge(['a', 'é', 'Σ', 'σ', "\x80", '?', 'ß'], $length > 0 ? [substr($string, mt_rand(0, $length - 1), mt_rand(1, 4))] : []);
        if (mb_strlen($string) > 1) {
            $needles[] = mb_substr($string, 1, 2);
        }
        foreach ($needles as $needle) {
            foreach ([0, 1, 3, -1, -3, 50] as $offset) {
                foreach (['mb_strpos', 'mb_strrpos', 'mb_stripos', 'mb_strripos'] as $function) {
                    // ext-mbstring reads past the string when the offset lands inside a
                    // truncated trailing sequence; that result is undefined, so skip it.
                    if (($function === 'mb_strpos' || $function === 'mb_strrpos')
                        && $overshoots($string, $needle, $offset, $function === 'mb_strrpos')) {
                        continue;
                    }
                    $compare($function, [$string, $needle, $offset]);
                }
            }
            foreach (['mb_strstr', 'mb_strrchr', 'mb_stristr', 'mb_strrichr'] as $function) {
                $compare($function, [$string, $needle, false]);
                $compare($function, [$string, $needle, true]);
            }
            $compare('mb_substr_count', [$string, $needle]);
        }
        $compare('mb_encode_numericentity', [$string, [0x80, 0x10FFFF, 0, 0x1FFFFF]]);
        $compare('mb_encode_numericentity', [$string, [0x20, 0x7F, 1, 0xFF, 0x3000, 0x3100, -1, 0xFFFF], 'UTF-8', true]);
        $compare('mb_decode_numericentity', [$string, [0x0, 0x10FFFF, 0, 0x1FFFFF]]);
        $compare('mb_decode_numericentity', [$string, [0x40, 0x50, 1, 0xFF]]);
        foreach ([['UTF-8', 'ISO-8859-1'], ['ASCII', 'UTF-8'], ['UTF-8', 'Windows-1252', 'UTF-16'], 'auto'] as $list) {
            $compare('mb_detect_encoding', [$string, $list]);
            $compare('mb_detect_encoding', [$string, $list, true]);
        }
        foreach ($samples($encodings, 6) as $encoding) {
            $compare('mb_convert_encoding', [$string, $encoding]);
            $compare('mb_convert_encoding', [$string, 'UTF-8', $encoding]);
            $compare('mb_check_encoding', [$string, $encoding]);
        }
    }

    // Every function in every supported encoding, on text that encoding can hold.
    $texts = array_slice($corpus, 0, 24);
    foreach ($encodings as $encoding) {
        foreach ($texts as $text) {
            $string = mb_convert_encoding($text, $encoding, 'UTF-8');
            $length = mb_strlen($string, $encoding);
            $compare('mb_strlen', [$string, $encoding]);
            $compare('mb_strwidth', [$string, $encoding]);
            $compare('mb_scrub', [$string, $encoding]);
            $compare('mb_str_split', [$string, 2, $encoding]);
            $compare('mb_substr', [$string, 1, 3, $encoding]);
            $compare('mb_substr', [$string, -3, null, $encoding]);
            $compare('mb_strcut', [$string, 1, 5, $encoding]);
            $compare('mb_strtoupper', [$string, $encoding]);
            $compare('mb_convert_case', [$string, MB_CASE_TITLE, $encoding]);
            $compare('mb_strimwidth', [$string, 0, 5, '', $encoding]);
            $compare('mb_trim', [$string, null, $encoding]);
            $compare('mb_ord', [$string === '' ? mb_convert_encoding('x', $encoding, 'UTF-8') : $string, $encoding]);
            $needle = mb_substr($string, 1, 1, $encoding);
            if ($needle !== '') {
                $compare('mb_strpos', [$string, $needle, 0, $encoding]);
                $compare('mb_strrpos', [$string, $needle, -1, $encoding]);
                $compare('mb_stripos', [$string, $needle, 1, $encoding]);
                $compare('mb_substr_count', [$string, $needle, $encoding]);
            }
            foreach ($samples($encodings, 3) as $target) {
                $compare('mb_convert_encoding', [$string, $target, $encoding]);
            }
            $compare('mb_detect_encoding', [$string, ['ASCII', 'UTF-8', $encoding]]);
        }
        $compare('mb_encoding_aliases', [$encoding]);
        $compare('mb_preferred_mime_name', [$encoding]);
        foreach ([0x41, 0xE9, 0x20AC, 0x3A3, 0x1F600, 0xD800, 0x110000, -1] as $codePoint) {
            $compare('mb_chr', [$codePoint, $encoding]);
        }
        for ($index = 0; $index < 12; $index++) {
            $garbage = $bytes(9);
            $compare('mb_strlen', [$garbage, $encoding]);
            $compare('mb_scrub', [$garbage, $encoding]);
            $compare('mb_check_encoding', [$garbage, $encoding]);
            $compare('mb_convert_encoding', [$garbage, 'UTF-8', $encoding]);
        }
    }

    // Substitution modes and settings.
    foreach (['none', 'long', 'entity', 0x2A, 0xE9, 0x1F600, 0x3F] as $substitute) {
        mb_substitute_character($substitute);
        Mbstring::mb_substitute_character($substitute);
        $compare('mb_substitute_character', []);
        foreach (array_slice($corpus, 20, 40) as $string) {
            foreach (['ASCII', 'ISO-8859-1', 'UTF-16', 'Windows-1251', 'UTF-8'] as $encoding) {
                $compare('mb_convert_encoding', [$string, $encoding]);
            }
            $compare('mb_scrub', [$string]);
            $compare('mb_substr', [$string, 1, 3]);
            $compare('mb_strtolower', [$string]);
        }
    }
    foreach (['bogus', -1, 0xD800, 0x110000] as $substitute) {
        $compare('mb_substitute_character', [$substitute]);
    }
    foreach (['UTF-8', 'utf8', 'latin1', 'nope'] as $encoding) {
        $compare('mb_internal_encoding', [$encoding]);
        $compare('mb_internal_encoding', []);
    }
    mb_internal_encoding('UTF-8');
    Mbstring::mb_internal_encoding('UTF-8');
    foreach (['Russian', 'tr', 'Klingon', 'Armenian', 'ja', 'neutral'] as $language) {
        $compare('mb_language', [$language]);
        $compare('mb_language', []);
        if ($language !== 'ja') {
            $compare('mb_detect_encoding', ["\xC1\xD2\xC7", 'auto']);
            $compare('mb_detect_order', ['auto']);
            $compare('mb_detect_order', []);
        }
    }
    foreach (['ASCII, UTF-8', ['UTF-8', 'Windows-1252'], 'auto', 'nope', []] as $order) {
        $compare('mb_detect_order', [$order]);
        $compare('mb_detect_order', []);
    }
    mb_detect_order('ASCII, UTF-8');
    Mbstring::mb_detect_order('ASCII, UTF-8');
    foreach ([[], [1, 2, 3], ['x', 1, 2, 3]] as $map) {
        $compare('mb_encode_numericentity', ['abc', $map]);
    }
    $compare('mb_str_split', ['abc', 0]);
    $compare('mb_str_pad', ['abc', 6, '']);
    $compare('mb_str_pad', ['abc', 6, ' ', 7]);
    $compare('mb_convert_case', ['abc', 8]);
    $compare('mb_substr_count', ['abc', '']);
    $compare('mb_strimwidth', ['abc', 4, 2]);
    $compare('mb_ord', ['']);
    $compare('mb_convert_encoding', [['ação', 'k' => ['é', 1, null]], 'ISO-8859-1', 'UTF-8']);
    $compare('mb_check_encoding', [['ação', ["\xFF"]]]);
    $compare('mb_detect_encoding', ['abc', 'nope']);
    $compare('mb_detect_encoding', ['abc', ['8bit']]);
    $compare('mb_convert_encoding', ['abc', 'UTF-8', 'nope']);
    $compare('mb_convert_encoding', ['abc', 'nope']);

    $assert(
        $mismatches === [],
        "The mbstring polyfill must behave like ext-mbstring:\n".implode("\n", $mismatches),
    );
}

if (isset($standalone)) {
    echo "mbstring polyfill tests passed.\n";
}
