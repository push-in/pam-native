<?php

declare(strict_types=1);

/*
 * Regenerates the tables of the PHP SDK's mbstring polyfill
 * (packages/native/src/Polyfill/Resources) from a PHP build that has
 * ext-mbstring: `pam scripts/generate-mbstring-polyfill.php`.
 *
 * The tables mirror that build exactly: case mappings, the classes title case
 * and the final-sigma rule use, East Asian widths, the single-byte code pages
 * and the detection weights. Run it after a PHP minor upgrade, then run
 * packages/native/tests/mbstring_polyfill.php.
 */

if (!extension_loaded('mbstring')) {
    fwrite(STDERR, "generate-mbstring-polyfill: needs a PHP build with ext-mbstring (use `pam`).\n");
    exit(1);
}

$target = dirname(__DIR__).'/packages/native/src/Polyfill/Resources';
if (!is_dir($target) && !mkdir($target, 0777, true)) {
    exit(1);
}

$codePoints = static function (): Generator {
    for ($codePoint = 0; $codePoint <= 0x10FFFF; $codePoint++) {
        if ($codePoint < 0xD800 || $codePoint > 0xDFFF) {
            yield $codePoint => mb_chr($codePoint, 'UTF-8');
        }
    }
};

// Simple mappings, plus the full mappings only where they differ.
$modes = [
    'lower' => [MB_CASE_LOWER_SIMPLE, MB_CASE_LOWER],
    'upper' => [MB_CASE_UPPER_SIMPLE, MB_CASE_UPPER],
    'title' => [MB_CASE_TITLE_SIMPLE, MB_CASE_TITLE],
    'fold' => [MB_CASE_FOLD_SIMPLE, MB_CASE_FOLD],
];
$case = [];
foreach ($modes as $name => [$simpleMode, $fullMode]) {
    $simple = [];
    $full = [];
    foreach ($codePoints() as $character) {
        $simpleValue = mb_convert_case($character, $simpleMode, 'UTF-8');
        $fullValue = mb_convert_case($character, $fullMode, 'UTF-8');
        if ($simpleValue !== $character) {
            $simple[$character] = $simpleValue;
        }
        if ($fullValue !== $simpleValue) {
            $full[$character] = $fullValue;
        }
    }
    $case[$name] = $simple;
    $case[$name.'_full'] = $full;
}

/*
 * Title case and the final sigma follow two properties: a Case_Ignorable
 * character keeps the current state, any other Cased character ends a word
 * start and anything else starts a new word. Probe them through MB_CASE_TITLE.
 */
$cased = [];
$ignorable = [];
foreach ($codePoints() as $codePoint => $character) {
    if (str_ends_with(mb_convert_case($character.'a', MB_CASE_TITLE, 'UTF-8'), 'a')) {
        $cased[] = $codePoint;
    } elseif (str_ends_with(mb_convert_case('a'.$character.'a', MB_CASE_TITLE, 'UTF-8'), 'a')) {
        $ignorable[] = $codePoint;
    }
}

$wide = [];
foreach ($codePoints() as $codePoint => $character) {
    if (mb_strwidth($character, 'UTF-8') === 2) {
        $wide[] = $codePoint;
    }
}

$ranges = static function (array $points): array {
    $ranges = [];
    foreach ($points as $point) {
        $last = array_key_last($ranges);
        if ($last !== null && $ranges[$last][1] === $point - 1) {
            $ranges[$last][1] = $point;
            continue;
        }
        $ranges[] = [$point, $point];
    }

    return $ranges;
};
$characterClass = static function (array $ranges): string {
    $class = '';
    foreach ($ranges as [$first, $last]) {
        $class .= $first === $last
            ? sprintf('\x{%X}', $first)
            : sprintf('\x{%X}-\x{%X}', $first, $last);
    }

    return '['.$class.']';
};

/*
 * Encodings the polyfill implements: every Unicode transformation format and
 * every single-byte code page mbstring knows. Multi-byte legacy encodings
 * (SJIS, EUC-*, BIG-5, GB18030, ISO-2022-*, UTF-7...) are left out.
 */
$singleByte = [
    'ISO-8859-1', 'ISO-8859-2', 'ISO-8859-3', 'ISO-8859-4', 'ISO-8859-5', 'ISO-8859-6',
    'ISO-8859-7', 'ISO-8859-8', 'ISO-8859-9', 'ISO-8859-10', 'ISO-8859-13', 'ISO-8859-14',
    'ISO-8859-15', 'ISO-8859-16', 'Windows-1251', 'Windows-1252', 'Windows-1254',
    'CP866', 'CP850', 'KOI8-R', 'KOI8-U', 'ArmSCII-8',
];
$unicode = [
    'UTF-8', 'UTF-16', 'UTF-16BE', 'UTF-16LE', 'UTF-32', 'UTF-32BE', 'UTF-32LE',
    'UCS-2', 'UCS-2BE', 'UCS-2LE', 'UCS-4', 'UCS-4BE', 'UCS-4LE',
];
$encodings = [];
$previousSubstitute = mb_substitute_character();
mb_substitute_character('none');
foreach (array_merge(['ASCII', '8bit'], $unicode, $singleByte) as $encoding) {
    $high = null;
    if (in_array($encoding, $singleByte, true)) {
        $high = [];
        for ($byte = 0; $byte <= 0xFF; $byte++) {
            $decoded = mb_convert_encoding(chr($byte), 'UTF-8', $encoding);
            if ($byte < 0x80) {
                if ($decoded !== chr($byte)) {
                    throw new RuntimeException("{$encoding} does not keep ASCII at 0x".dechex($byte));
                }
                continue;
            }
            $high[] = $decoded === '' ? null : mb_ord($decoded, 'UTF-8');
        }
    }
    // Code points whose encoding is not the inverse of the decoding table.
    $encode = [];
    if ($high !== null) {
        $inverse = [];
        foreach ($high as $index => $mapped) {
            if ($mapped !== null) {
                $inverse[$mapped] ??= 0x80 + $index;
            }
        }
        for ($codePoint = 0; $codePoint < 0x10000; $codePoint++) {
            if ($codePoint >= 0xD800 && $codePoint <= 0xDFFF) {
                continue;
            }
            $encoded = mb_convert_encoding(mb_chr($codePoint, 'UTF-8'), $encoding, 'UTF-8');
            $expected = $codePoint < 0x80 ? $codePoint : ($inverse[$codePoint] ?? null);
            if (($encoded === '' ? null : ord($encoded)) !== $expected) {
                $encode[$codePoint] = $encoded === '' ? null : ord($encoded);
            }
        }
    }
    $encodings[$encoding] = [
        'mime' => (string) @mb_preferred_mime_name($encoding),
        'aliases' => mb_encoding_aliases($encoding),
        'high' => $high,
        'encode' => $encode,
    ];
}
mb_substitute_character($previousSubstitute);

// mb_detect_encoding() penalises 'rare' BMP code points.
$header = @file_get_contents(sprintf(
    'https://raw.githubusercontent.com/php/php-src/php-%s/ext/mbstring/rare_cp_bitvec.h',
    PHP_VERSION,
));
if (!is_string($header) || preg_match_all('/0x([0-9a-f]{8})/', $header, $words) !== 2048) {
    fwrite(STDERR, 'generate-mbstring-polyfill: cannot read rare_cp_bitvec.h for PHP '.PHP_VERSION."\n");
    exit(1);
}
$rare = implode('', array_map(static fn (string $word): string => pack('V', hexdec($word)), $words[1]));

$export = static function (mixed $value) use (&$export): string {
    if (!is_array($value)) {
        return var_export($value, true);
    }
    $items = [];
    $list = array_is_list($value);
    foreach ($value as $key => $item) {
        $items[] = ($list ? '' : var_export($key, true).'=>').$export($item);
    }

    return '['.implode(',', $items).']';
};
$write = static function (string $file, string $comment, array $data) use ($target, $export): void {
    file_put_contents($target.'/'.$file, "<?php\n\n// {$comment}\n"
        .'// Generated by scripts/generate-mbstring-polyfill.php from PHP '.PHP_VERSION
        ." ext-mbstring. Do not edit.\n\nreturn ".$export($data).";\n");
};

$write('case_maps.php', 'Simple case mappings and full-mapping overrides (UTF-8 => UTF-8).', $case);
$write('case_properties.php', 'Cased (and not Case_Ignorable) / Case_Ignorable PCRE classes.', [
    'cased' => $characterClass($ranges($cased)),
    'ignorable' => $characterClass($ranges($ignorable)),
]);
$write('east_asian_width.php', 'PCRE class of the code points mb_strwidth() counts as 2 columns.', [
    'wide' => $characterClass($ranges($wide)),
]);
$write('encodings.php', 'Supported encodings: MIME name, aliases, single-byte high halves (null = undefined) and encoding exceptions.', $encodings);
$write('rare_code_points.php', 'rare_codepoint_bitvec (U+0000-U+FFFF, little-endian 32-bit words) as hex.', [
    'bits' => bin2hex($rare),
]);

printf(
    "mbstring polyfill tables: %d lower, %d upper, %d title, %d fold, %d cased, %d ignorable, %d wide, %d encodings\n",
    count($case['lower']),
    count($case['upper']),
    count($case['title']),
    count($case['fold']),
    count($cased),
    count($ignorable),
    count($wide),
    count($encodings),
);
