<?php

declare(strict_types=1);

namespace Pam\Native\Polyfill;

use ValueError;

use function array_slice;
use function chr;
use function count;
use function in_array;
use function is_array;
use function is_bool;
use function is_float;
use function is_int;
use function is_object;
use function is_string;
use function ord;
use function sprintf;
use function strlen;

/**
 * ext-mbstring in plain PHP for the mobile runtimes.
 *
 * PAM's Android and iOS PHP builds do not compile ext-mbstring, so
 * src/Polyfill/mbstring.php (autoloaded by Composer before any application
 * code) defines every function listed in FUNCTIONS through this class whenever
 * the extension is missing. Behaviour follows PHP 8.5's mbstring: invalid
 * byte sequences are counted and substituted the same way, case mapping uses
 * the full Unicode tables (including the Greek final sigma), widths follow
 * the East Asian Width table and errors are the same ValueErrors.
 *
 * Supported encodings: UTF-8, UTF-16/32 (BE/LE), UCS-2/4 (BE/LE), ASCII, 8bit
 * and every single-byte code page mbstring knows (ISO-8859-*, Windows-125x,
 * KOI8-*, CP866, CP850, ArmSCII-8). Legacy multi-byte encodings (SJIS, EUC-*,
 * BIG-5, GB18030, ISO-2022-*, UTF-7) and the mb_ereg* family are not available.
 *
 * @internal Call the mb_* functions instead.
 */
final class Mbstring
{
    /** Functions defined by src/Polyfill/mbstring.php when ext-mbstring is absent. */
    public const array FUNCTIONS = [
        'mb_check_encoding', 'mb_chr', 'mb_convert_case', 'mb_convert_encoding',
        'mb_convert_variables', 'mb_decode_numericentity', 'mb_detect_encoding',
        'mb_detect_order', 'mb_encode_numericentity', 'mb_encoding_aliases', 'mb_get_info',
        'mb_internal_encoding', 'mb_language', 'mb_lcfirst', 'mb_list_encodings', 'mb_ltrim',
        'mb_ord', 'mb_preferred_mime_name', 'mb_rtrim', 'mb_scrub', 'mb_str_pad', 'mb_str_split',
        'mb_strcut', 'mb_strimwidth', 'mb_stripos', 'mb_stristr', 'mb_strlen', 'mb_strpos',
        'mb_strrchr', 'mb_strrichr', 'mb_strripos', 'mb_strrpos', 'mb_strstr', 'mb_strtolower',
        'mb_strtoupper', 'mb_strwidth', 'mb_substitute_character', 'mb_substr',
        'mb_substr_count', 'mb_trim', 'mb_ucfirst',
    ];

    /** A byte sequence that is invalid in the source encoding. */
    private const int BAD = -1;

    private const int MODE_CHAR = 0;
    private const int MODE_NONE = 1;
    private const int MODE_LONG = 2;
    private const int MODE_ENTITY = 3;

    /** One decoded UTF-8 unit: a valid character (group 1) or one error, grouped like mbstring. */
    private const string UTF8_UNIT = '/([\x00-\x7F]|[\xC2-\xDF][\x80-\xBF]|\xE0[\xA0-\xBF][\x80-\xBF]'
        .'|[\xE1-\xEC\xEE\xEF][\x80-\xBF]{2}|\xED[\x80-\x9F][\x80-\xBF]|\xF0[\x90-\xBF][\x80-\xBF]{2}'
        .'|[\xF1-\xF3][\x80-\xBF]{3}|\xF4[\x80-\x8F][\x80-\xBF]{2})|\xE0[\xA0-\xBF]'
        .'|[\xE1-\xEC\xEE\xEF][\x80-\xBF]|\xED[\x80-\x9F]'
        .'|(?:\xF0[\x90-\xBF]|[\xF1-\xF3][\x80-\xBF]|\xF4[\x80-\x8F])[\x80-\xBF]?|[\x80-\xFF]/';

    private const array TRIM = [
        0x20, 0x0C, 0x0A, 0x0D, 0x09, 0x0B, 0x00, 0xA0, 0x1680, 0x2000, 0x2001, 0x2002, 0x2003,
        0x2004, 0x2005, 0x2006, 0x2007, 0x2008, 0x2009, 0x200A, 0x2028, 0x2029, 0x202F, 0x205F,
        0x3000, 0x85, 0x180E,
    ];

    /** Language => [aliases, extra encodings of its 'auto' detection order]. */
    private const array LANGUAGES = [
        'neutral' => [['neutral'], []],
        'uni' => [['uni', 'universal'], []],
        'Japanese' => [['japanese', 'ja'], []],
        'Korean' => [['korean', 'ko'], []],
        'Simplified Chinese' => [['simplified chinese', 'zh-cn'], []],
        'Traditional Chinese' => [['traditional chinese', 'zh-tw'], []],
        'English' => [['english', 'en'], []],
        'German' => [['german', 'de'], []],
        'Russian' => [['russian', 'ru'], ['KOI8-R', 'Windows-1251', 'CP866']],
        'Ukrainian' => [['ukrainian', 'ua'], ['KOI8-U']],
        'Armenian' => [['armenian', 'hy'], ['ArmSCII-8']],
        'Turkish' => [['turkish', 'tr'], ['Windows-1254', 'ISO-8859-9']],
    ];

    private static string $internalEncoding = 'UTF-8';
    private static int $mode = self::MODE_CHAR;
    private static int $substitute = 0x3F;
    private static string $language = 'neutral';
    /** @var list<string> */
    private static array $autoEncodings = [];
    /** @var list<string>|null */
    private static ?array $detectOrder = null;
    /** @var array<string, array{mime: string, aliases: list<string>, high: list<int|null>|null, encode: array<int, int|null>}>|null */
    private static ?array $encodings = null;
    /** @var array<string, string> */
    private static array $names = [];
    /** @var array<string, array<int, string|null>> */
    private static array $reverse = [];
    /** @var array<string, array<string, string>>|null */
    private static ?array $caseTables = null;
    /** @var array<string, array<string, string>> */
    private static array $caseMaps = [];
    /** @var array<string, int> 0 starts a word, 1 is cased, 2 is case-ignorable */
    private static array $caseClasses = [];
    /** @var array{cased: string, ignorable: string}|null */
    private static ?array $caseProperties = null;
    private static ?string $wide = null;
    private static ?string $rare = null;

    // ---------------------------------------------------------------- settings

    public static function mb_internal_encoding(?string $encoding = null): string|bool
    {
        if ($encoding === null) {
            return self::$internalEncoding;
        }
        self::$internalEncoding = self::requireEncoding($encoding, 'mb_internal_encoding', 1);

        return true;
    }

    public static function mb_language(?string $language = null): string|bool
    {
        if ($language === null) {
            return self::$language;
        }
        $found = self::language($language);
        if ($found === null) {
            // ext-mbstring's failed INI update reports "neutral" afterwards but keeps
            // the previous language's 'auto' detection order.
            self::$language = 'neutral';
            throw new ValueError(sprintf(
                'mb_language(): Argument #1 ($language) must be a valid language, "%s" given',
                $language,
            ));
        }
        self::$language = $found;
        self::$autoEncodings = self::LANGUAGES[$found][1];

        return true;
    }

    /** @return list<string>|true */
    public static function mb_detect_order(array|string|null $encoding = null): array|bool
    {
        if ($encoding === null) {
            return self::$detectOrder ?? ['ASCII', 'UTF-8'];
        }
        $list = self::encodingList($encoding, 'mb_detect_order', 1, 'encoding');
        if ($list === []) {
            throw new ValueError('mb_detect_order(): Argument #1 ($encoding) must specify at least one encoding');
        }
        self::$detectOrder = $list;

        return true;
    }

    public static function mb_substitute_character(string|int|null $substitute_character = null): string|int|bool
    {
        if ($substitute_character === null) {
            return match (self::$mode) {
                self::MODE_NONE => 'none',
                self::MODE_LONG => 'long',
                self::MODE_ENTITY => 'entity',
                default => self::$substitute,
            };
        }
        if (is_string($substitute_character)) {
            $mode = match (strtolower($substitute_character)) {
                'none' => self::MODE_NONE,
                'long' => self::MODE_LONG,
                'entity' => self::MODE_ENTITY,
                default => null,
            };
            if ($mode === null) {
                throw new ValueError(
                    'mb_substitute_character(): Argument #1 ($substitute_character) must be "none", "long", "entity" or a valid codepoint',
                );
            }
            self::$mode = $mode;

            return true;
        }
        if ($substitute_character < 0 || $substitute_character > 0x10FFFF
            || ($substitute_character >= 0xD800 && $substitute_character <= 0xDFFF)) {
            throw new ValueError('mb_substitute_character(): Argument #1 ($substitute_character) is not a valid codepoint');
        }
        self::$mode = self::MODE_CHAR;
        self::$substitute = $substitute_character;

        return true;
    }

    /** @return list<string> */
    public static function mb_list_encodings(): array
    {
        return array_keys(self::encodings());
    }

    /** @return list<string> */
    public static function mb_encoding_aliases(string $encoding): array
    {
        return self::encodings()[self::requireEncoding($encoding, 'mb_encoding_aliases', 1)]['aliases'];
    }

    public static function mb_preferred_mime_name(string $encoding): string|false
    {
        $name = self::requireEncoding($encoding, 'mb_preferred_mime_name', 1);
        $mime = self::encodings()[$name]['mime'];
        if ($mime === '') {
            trigger_error(sprintf('mb_preferred_mime_name(): No MIME preferred name corresponding to "%s"', $encoding), E_USER_WARNING);

            return false;
        }

        return $mime;
    }

    public static function mb_get_info(string $type = 'all'): array|string|int|false
    {
        $info = [
            'internal_encoding' => self::$internalEncoding,
            'http_output' => 'UTF-8',
            'http_output_conv_mimetypes' => '^(text/|application/xhtml\+xml)',
            'mail_charset' => 'UTF-8',
            'mail_header_encoding' => 'BASE64',
            'mail_body_encoding' => 'BASE64',
            'illegal_chars' => 0,
            'encoding_translation' => 'Off',
            'language' => self::$language,
            'detect_order' => self::mb_detect_order(),
            'substitute_character' => self::mb_substitute_character(),
            'strict_detection' => 'Off',
        ];
        if ($type === 'all') {
            return $info;
        }

        return $info[strtolower($type)] ?? false;
    }

    // ----------------------------------------------------------------- lengths

    public static function mb_strlen(string $string, ?string $encoding = null): int
    {
        return self::length($string, self::requireEncoding($encoding, 'mb_strlen', 2));
    }

    public static function mb_strwidth(string $string, ?string $encoding = null): int
    {
        return self::width($string, self::requireEncoding($encoding, 'mb_strwidth', 2));
    }

    public static function mb_check_encoding(array|string|null $value = null, ?string $encoding = null): bool
    {
        $name = self::requireEncoding($encoding, 'mb_check_encoding', 2);
        if ($value === null) {
            trigger_error('mb_check_encoding(): Calling mb_check_encoding() without argument is deprecated', E_USER_DEPRECATED);

            return true;
        }
        if (is_string($value)) {
            return self::valid($value, $name);
        }

        return self::validArray($value, $name);
    }

    // -------------------------------------------------------------- substrings

    public static function mb_substr(string $string, int $start, ?int $length = null, ?string $encoding = null): string
    {
        $name = self::requireEncoding($encoding, 'mb_substr', 4);
        if ($start === PHP_INT_MIN) {
            throw new ValueError('mb_substr(): Argument #2 ($start) must be between '.(PHP_INT_MIN + 1).' and '.PHP_INT_MAX);
        }
        if ($length === PHP_INT_MIN) {
            throw new ValueError('mb_substr(): Argument #3 ($length) must be between '.(PHP_INT_MIN + 1).' and '.PHP_INT_MAX);
        }
        $total = $start < 0 || ($length !== null && $length < 0) ? self::length($string, $name) : 0;
        $from = $start >= 0 ? $start : (-$start < $total ? $total + $start : 0);
        if ($length === null) {
            $count = PHP_INT_MAX;
        } elseif ($length >= 0) {
            $count = $length;
        } elseif ($from < $total && -$length < $total - $from) {
            $count = $total - $from + $length;
        } else {
            $count = 0;
        }

        return self::slice($string, $from, $count, $name);
    }

    public static function mb_strcut(string $string, int $start, ?int $length = null, ?string $encoding = null): string
    {
        $name = self::requireEncoding($encoding, 'mb_strcut', 4);
        $size = strlen($string);
        $length ??= $size;
        if ($start < 0) {
            $start = max(0, $size + $start);
        }
        if ($length < 0) {
            $length = max(0, $size - $start + $length);
        }
        if ($start > $size || $length === 0) {
            return '';
        }
        if ($name === 'UTF-8') {
            while ($start > 0 && $start < $size && (ord($string[$start]) & 0xC0) === 0x80) {
                $start--;
            }
            $end = $start + $length;
            if ($end >= $size) {
                return substr($string, $start);
            }
            while ($end > $start && (ord($string[$end]) & 0xC0) === 0x80) {
                $end--;
            }

            return substr($string, $start, $end - $start);
        }
        if (str_starts_with($name, 'UTF-16')) {
            return self::cutUtf16($string, $start, $length, $name);
        }
        $width = self::fixedWidth($name);
        $start -= $start % $width;
        $length = min($length, $size - $start);

        return substr($string, $start, $length - $length % $width);
    }

    /** @return list<string> */
    public static function mb_str_split(string $string, int $length = 1, ?string $encoding = null): array
    {
        if ($length <= 0) {
            throw new ValueError('mb_str_split(): Argument #2 ($length) must be greater than 0');
        }
        if ($length > 1073741823) {
            throw new ValueError('mb_str_split(): Argument #2 ($length) is too large');
        }
        $name = self::requireEncoding($encoding, 'mb_str_split', 3);
        if ($string === '') {
            return [];
        }
        $width = self::fixedWidth($name);
        if ($width > 0) {
            return str_split($string, $width * $length);
        }
        if ($name === 'UTF-8') {
            if (!self::hasHighBytes($string)) {
                return str_split($string, $length);
            }
            if (self::isUtf8($string)) {
                if ($length === 1) {
                    return preg_split('//u', $string, -1, PREG_SPLIT_NO_EMPTY) ?: [];
                }
                if ($length <= 65535) {
                    preg_match_all('/(?:[^\x80-\xBF][\x80-\xBF]*){1,'.$length.'}/', $string, $chunks);

                    return $chunks[0];
                }
            }
            // mbstring walks the lead-byte length table, valid or not.
            $chunks = [];
            $size = strlen($string);
            for ($position = 0; $position < $size;) {
                $begin = $position;
                for ($count = 0; $count < $length && $position < $size; $count++) {
                    $position += self::utf8Length(ord($string[$position]));
                }
                $chunks[] = substr($string, $begin, min($position, $size) - $begin);
                $position = min($position, $size);
            }

            return $chunks;
        }
        $chunks = [];
        foreach (array_chunk(self::decode($string, $name), $length) as $units) {
            $chunks[] = self::encode($units, $name);
        }

        return $chunks;
    }

    public static function mb_strimwidth(
        string $string,
        int $start,
        int $width,
        string $trim_marker = '',
        ?string $encoding = null,
    ): string {
        $name = self::requireEncoding($encoding, 'mb_strimwidth', 5);
        if ($start !== 0) {
            $total = self::length($string, $name);
            if ($start < 0) {
                $start += $total;
            }
            if ($start < 0 || $start > $total) {
                throw new ValueError('mb_strimwidth(): Argument #2 ($start) is out of range');
            }
        }
        if ($width < 0) {
            trigger_error('mb_strimwidth(): passing a negative integer to argument #3 ($width) is deprecated', E_USER_DEPRECATED);
            $width += self::width($string, $name);
            if ($start > 0) {
                $width -= self::width(self::slice($string, 0, $start, $name), $name);
            }
            if ($width < 0) {
                throw new ValueError('mb_strimwidth(): Argument #3 ($width) is out of range');
            }
        }
        if ($start === 0 && $name === 'UTF-8' && self::isUtf8($string) && self::width($string, $name) <= $width) {
            return $string;
        }
        $units = self::decode($string, $name);
        $count = count($units);
        $wide = preg_grep(self::wideClass(), $units) ?: [];
        $remaining = $width;
        $invalid = false;
        for ($index = $start; $index < $count; $index++) {
            $unitWidth = isset($wide[$index]) ? 2 : 1;
            $invalid = $invalid || $units[$index] === self::BAD;
            if ($remaining < $unitWidth) {
                $markerWidth = self::width($trim_marker, $name);
                if ($width <= $markerWidth) {
                    return $trim_marker;
                }
                $width -= $markerWidth;
                $kept = [];
                for ($cursor = $start; $cursor < $count; $cursor++) {
                    $unitWidth = isset($wide[$cursor]) ? 2 : 1;
                    if ($width < $unitWidth) {
                        break;
                    }
                    $width -= $unitWidth;
                    $kept[] = $units[$cursor];
                }

                return self::encode($kept, $name).$trim_marker;
            }
            $remaining -= $unitWidth;
        }
        if (!$invalid) {
            return $start === 0 ? $string : self::slice($string, $start, PHP_INT_MAX, $name);
        }

        return self::encode(array_slice($units, $start), $name);
    }

    public static function mb_str_pad(
        string $string,
        int $length,
        string $pad_string = ' ',
        int $pad_type = STR_PAD_RIGHT,
        ?string $encoding = null,
    ): string {
        $name = self::requireEncoding($encoding, 'mb_str_pad', 5);
        $current = self::length($string, $name);
        if ($length < 0 || $length <= $current) {
            return $string;
        }
        if ($pad_string === '') {
            throw new ValueError('mb_str_pad(): Argument #3 ($pad_string) must not be empty');
        }
        if ($pad_type < STR_PAD_LEFT || $pad_type > STR_PAD_BOTH) {
            throw new ValueError('mb_str_pad(): Argument #4 ($pad_type) must be STR_PAD_LEFT, STR_PAD_RIGHT, or STR_PAD_BOTH');
        }
        $padLength = self::length($pad_string, $name);
        if ($padLength === 0) {
            throw new ValueError('mb_str_pad(): Argument #3 ($pad_string) must not be empty');
        }
        $missing = $length - $current;
        [$left, $right] = match ($pad_type) {
            STR_PAD_LEFT => [$missing, 0],
            STR_PAD_RIGHT => [0, $missing],
            default => [intdiv($missing, 2), $missing - intdiv($missing, 2)],
        };

        return str_repeat($pad_string, intdiv($left, $padLength))
            .self::slice($pad_string, 0, $left % $padLength, $name)
            .$string
            .str_repeat($pad_string, intdiv($right, $padLength))
            .self::slice($pad_string, 0, $right % $padLength, $name);
    }

    public static function mb_trim(string $string, ?string $characters = null, ?string $encoding = null): string
    {
        return self::trim($string, $characters, $encoding, 3, 'mb_trim');
    }

    public static function mb_ltrim(string $string, ?string $characters = null, ?string $encoding = null): string
    {
        return self::trim($string, $characters, $encoding, 1, 'mb_ltrim');
    }

    public static function mb_rtrim(string $string, ?string $characters = null, ?string $encoding = null): string
    {
        return self::trim($string, $characters, $encoding, 2, 'mb_rtrim');
    }

    // --------------------------------------------------------------- searching

    public static function mb_strpos(string $haystack, string $needle, int $offset = 0, ?string $encoding = null): int|false
    {
        $name = self::requireEncoding($encoding, 'mb_strpos', 4);

        return self::position(self::find($haystack, $needle, $name, $offset, false), 'mb_strpos');
    }

    public static function mb_strrpos(string $haystack, string $needle, int $offset = 0, ?string $encoding = null): int|false
    {
        $name = self::requireEncoding($encoding, 'mb_strrpos', 4);

        return self::position(self::find($haystack, $needle, $name, $offset, true), 'mb_strrpos');
    }

    public static function mb_stripos(string $haystack, string $needle, int $offset = 0, ?string $encoding = null): int|false
    {
        $name = self::requireEncoding($encoding, 'mb_stripos', 4);

        return self::position(self::findFolded($haystack, $needle, $name, $offset, false), 'mb_stripos');
    }

    public static function mb_strripos(string $haystack, string $needle, int $offset = 0, ?string $encoding = null): int|false
    {
        $name = self::requireEncoding($encoding, 'mb_strripos', 4);

        return self::position(self::findFolded($haystack, $needle, $name, $offset, true), 'mb_strripos');
    }

    public static function mb_strstr(string $haystack, string $needle, bool $before_needle = false, ?string $encoding = null): string|false
    {
        $name = self::requireEncoding($encoding, 'mb_strstr', 4);

        return self::part($haystack, self::find($haystack, $needle, $name, 0, false), $before_needle, $name);
    }

    public static function mb_strrchr(string $haystack, string $needle, bool $before_needle = false, ?string $encoding = null): string|false
    {
        $name = self::requireEncoding($encoding, 'mb_strrchr', 4);

        return self::part($haystack, self::find($haystack, $needle, $name, 0, true), $before_needle, $name);
    }

    public static function mb_stristr(string $haystack, string $needle, bool $before_needle = false, ?string $encoding = null): string|false
    {
        $name = self::requireEncoding($encoding, 'mb_stristr', 4);

        return self::part($haystack, self::findFolded($haystack, $needle, $name, 0, false), $before_needle, $name);
    }

    public static function mb_strrichr(string $haystack, string $needle, bool $before_needle = false, ?string $encoding = null): string|false
    {
        $name = self::requireEncoding($encoding, 'mb_strrichr', 4);

        return self::part($haystack, self::findFolded($haystack, $needle, $name, 0, true), $before_needle, $name);
    }

    public static function mb_substr_count(string $haystack, string $needle, ?string $encoding = null): int
    {
        if ($needle === '') {
            throw new ValueError('mb_substr_count(): Argument #2 ($needle) must not be empty');
        }
        $name = self::requireEncoding($encoding, 'mb_substr_count', 3);
        $haystack = self::toMarkedUtf8($haystack, $name);
        $needle = self::toMarkedUtf8($needle, $name);
        if ($needle === '') {
            throw new ValueError('mb_substr_count(): Argument #2 ($needle) must not be empty');
        }

        return substr_count($haystack, $needle);
    }

    // ------------------------------------------------------------------- case

    public static function mb_convert_case(string $string, int $mode, ?string $encoding = null): string
    {
        $name = self::requireEncoding($encoding, 'mb_convert_case', 3);
        if ($mode < 0 || $mode > 7) {
            throw new ValueError('mb_convert_case(): Argument #2 ($mode) must be one of the MB_CASE_* constants');
        }

        return self::convertCase($string, $mode, $name);
    }

    public static function mb_strtoupper(string $string, ?string $encoding = null): string
    {
        return self::convertCase($string, 0, self::requireEncoding($encoding, 'mb_strtoupper', 2));
    }

    public static function mb_strtolower(string $string, ?string $encoding = null): string
    {
        return self::convertCase($string, 1, self::requireEncoding($encoding, 'mb_strtolower', 2));
    }

    public static function mb_ucfirst(string $string, ?string $encoding = null): string
    {
        return self::changeFirst($string, 2, self::requireEncoding($encoding, 'mb_ucfirst', 2));
    }

    public static function mb_lcfirst(string $string, ?string $encoding = null): string
    {
        return self::changeFirst($string, 1, self::requireEncoding($encoding, 'mb_lcfirst', 2));
    }

    // ------------------------------------------------------------- conversion

    /**
     * @param array<array-key, mixed>|string $string
     * @param array<array-key, string>|string|null $from_encoding
     * @return array<array-key, mixed>|string|false
     */
    public static function mb_convert_encoding(
        array|string $string,
        string $to_encoding,
        array|string|null $from_encoding = null,
    ): array|string|false {
        $target = self::requireEncoding($to_encoding, 'mb_convert_encoding', 2);
        $sources = $from_encoding === null
            ? [self::$internalEncoding]
            : self::encodingList($from_encoding, 'mb_convert_encoding', 3, 'from_encoding');
        if (count($sources) > 1) {
            $sources = array_values(array_filter($sources, static fn (string $name): bool => $name !== '8bit'));
        }
        if ($sources === []) {
            throw new ValueError('mb_convert_encoding(): Argument #3 ($from_encoding) must specify at least one encoding');
        }
        if (is_string($string)) {
            return self::convertString($string, $target, $sources);
        }

        return self::convertArray($string, $target, $sources);
    }

    public static function mb_convert_variables(
        string $to_encoding,
        array|string $from_encoding,
        mixed &$var,
        mixed &...$vars,
    ): string|false {
        $target = self::requireEncoding($to_encoding, 'mb_convert_variables', 1);
        $sources = self::encodingList($from_encoding, 'mb_convert_variables', 2, 'from_encoding');
        if ($sources === []) {
            throw new ValueError('mb_convert_variables(): Argument #2 ($from_encoding) must specify at least one encoding');
        }
        if (count($sources) === 1) {
            $source = $sources[0];
        } else {
            $strings = [];
            self::collectStrings($var, $strings);
            foreach ($vars as $value) {
                self::collectStrings($value, $strings);
            }
            $source = self::guess($strings, $sources, false, $from_encoding !== self::mb_list_encodings());
            if ($source === null) {
                trigger_error('mb_convert_variables(): Unable to detect encoding', E_USER_WARNING);

                return false;
            }
        }
        self::convertVariable($var, $source, $target);
        foreach ($vars as &$value) {
            self::convertVariable($value, $source, $target);
        }
        unset($value);

        return $source;
    }

    public static function mb_scrub(string $string, ?string $encoding = null): string
    {
        $name = self::requireEncoding($encoding, 'mb_scrub', 2);

        return self::convert($string, $name, $name);
    }

    /** @param array<array-key, string>|string|null $encodings */
    public static function mb_detect_encoding(string $string, array|string|null $encodings = null, bool $strict = false): string|false
    {
        $orderSignificant = $encodings !== self::mb_list_encodings();
        $list = $encodings === null
            ? self::mb_detect_order()
            : self::encodingList($encodings, 'mb_detect_encoding', 2, 'encodings');
        if ($list === []) {
            throw new ValueError('mb_detect_encoding(): Argument #2 ($encodings) must specify at least one encoding');
        }
        $list = array_values(array_filter($list, static fn (string $name): bool => $name !== '8bit'));
        if ($list === []) {
            return false;
        }

        return self::guess([$string], $list, $strict, $orderSignificant) ?? false;
    }

    public static function mb_ord(string $string, ?string $encoding = null): int|false
    {
        if ($string === '') {
            throw new ValueError('mb_ord(): Argument #1 ($string) must not be empty');
        }
        $name = self::requireEncoding($encoding, 'mb_ord', 2);
        if ($name === 'UTF-8') {
            preg_match(self::UTF8_UNIT, $string, $match);

            return ($match[1] ?? '') === '' ? false : self::codePoint($match[1]);
        }
        $first = self::decode($string, $name)[0] ?? self::BAD;
        if ($first === self::BAD) {
            return false;
        }

        return is_string($first) ? self::codePoint($first) : $first;
    }

    public static function mb_chr(int $codepoint, ?string $encoding = null): string|false
    {
        $name = self::requireEncoding($encoding, 'mb_chr', 2);
        if ($codepoint < 0 || $codepoint > 0x10FFFF) {
            return false;
        }
        if ($name === 'UTF-8') {
            return $codepoint >= 0xD800 && $codepoint <= 0xDFFF ? false : self::character($codepoint);
        }

        return self::encodeCodePoint($codepoint, $name) ?? false;
    }

    /** @param array<array-key, mixed> $map */
    public static function mb_encode_numericentity(string $string, array $map, ?string $encoding = null, bool $hex = false): string
    {
        $name = self::requireEncoding($encoding, 'mb_encode_numericentity', 3);
        $ranges = self::entityMap($map, 'mb_encode_numericentity');
        $units = [];
        foreach (self::decode($string, $name) as $unit) {
            $codePoint = is_string($unit) ? self::codePoint($unit) : ($unit === self::BAD ? 0xFFFFFFFF : $unit);
            $converted = null;
            foreach ($ranges as [$low, $high, $offset, $mask]) {
                if ($codePoint >= $low && $codePoint <= $high) {
                    $converted = (($codePoint + $offset) & 0xFFFFFFFF) & $mask;
                    break;
                }
            }
            if ($converted === null) {
                $units[] = $unit;
                continue;
            }
            $digits = $hex ? strtoupper(dechex($converted)) : (string) $converted;
            foreach (str_split('&#'.($hex ? 'x' : '').$digits.';') as $character) {
                $units[] = $character;
            }
        }

        return self::encode($units, $name);
    }

    /** @param array<array-key, mixed> $map */
    public static function mb_decode_numericentity(string $string, array $map, ?string $encoding = null): string
    {
        $name = self::requireEncoding($encoding, 'mb_decode_numericentity', 3);
        $ranges = self::entityMap($map, 'mb_decode_numericentity');
        if (!str_contains($string, '&') && $name === 'UTF-8' && self::isUtf8($string)) {
            return $string;
        }
        $units = self::decode($string, $name);
        $count = count($units);
        $out = [];
        for ($index = 0; $index < $count;) {
            if ($units[$index] !== '&') {
                $out[] = $units[$index++];
                continue;
            }
            $start = $index;
            $cursor = $index + 1;
            if (($units[$cursor] ?? null) !== '#') {
                $out[] = '&';
                $index++;
                continue;
            }
            $cursor++;
            $hex = ($units[$cursor] ?? null) === 'x';
            if ($hex) {
                $cursor++;
            }
            $digitsStart = $cursor;
            while ($cursor < $count && is_string($units[$cursor]) && strlen($units[$cursor]) === 1
                && ($hex ? ctype_xdigit($units[$cursor]) : ctype_digit($units[$cursor]))) {
                $cursor++;
            }
            $size = $cursor - $start;
            $decoded = null;
            if ($hex ? ($size >= 4 && $size <= 11) : ($size >= 3 && $size <= 12)) {
                $digits = implode('', array_slice($units, $digitsStart, $cursor - $digitsStart));
                $value = 0;
                $overflow = false;
                if ($hex) {
                    $value = (int) hexdec($digits);
                } else {
                    foreach (str_split($digits) as $digit) {
                        if ($value > 0x19999999) {
                            $overflow = true;
                            break;
                        }
                        $value = ($value * 10 + (int) $digit) & 0xFFFFFFFF;
                    }
                }
                if (!$overflow) {
                    foreach ($ranges as [$low, $high, $offset]) {
                        $codePoint = ($value - $offset) & 0xFFFFFFFF;
                        if ($codePoint >= $low && $codePoint <= $high) {
                            $decoded = $codePoint;
                            break;
                        }
                    }
                }
            }
            if ($decoded === null) {
                array_push($out, ...array_slice($units, $start, $cursor - $start));
            } else {
                $out[] = self::unit($decoded);
                if (($units[$cursor] ?? null) === ';') {
                    $cursor++;
                }
            }
            $index = $cursor;
        }

        return self::encode($out, $name);
    }

    // =========================================================== internals

    private static function requireEncoding(?string $name, string $function, int $argument): string
    {
        if ($name === null) {
            return self::$internalEncoding;
        }
        $found = self::canonical($name);
        if ($found === null) {
            $parameter = match (true) {
                $function === 'mb_convert_encoding' && $argument === 2 => 'to_encoding',
                $function === 'mb_convert_variables' && $argument === 1 => 'to_encoding',
                default => 'encoding',
            };
            throw new ValueError(sprintf('%s(): Argument #%d ($%s) must be a valid encoding, "%s" given', $function, $argument, $parameter, $name));
        }

        return $found;
    }

    /** @return array<string, array{mime: string, aliases: list<string>, high: list<int|null>|null, encode: array<int, int|null>}> */
    private static function encodings(): array
    {
        return self::$encodings ??= require __DIR__.'/Resources/encodings.php';
    }

    private static function canonical(string $name): ?string
    {
        if (self::$names === []) {
            $names = [];
            foreach (self::encodings() as $canonical => $info) {
                $names[strtolower($canonical)] ??= $canonical;
            }
            foreach (self::encodings() as $canonical => $info) {
                if ($info['mime'] !== '') {
                    $names[strtolower($info['mime'])] ??= $canonical;
                }
            }
            foreach (self::encodings() as $canonical => $info) {
                foreach ($info['aliases'] as $alias) {
                    $names[strtolower($alias)] ??= $canonical;
                }
            }
            self::$names = $names;
        }

        return self::$names[strtolower($name)] ?? null;
    }

    private static function language(string $name): ?string
    {
        $needle = strtolower($name);
        foreach (self::LANGUAGES as $language => [$aliases]) {
            if (in_array($needle, $aliases, true)) {
                return $language;
            }
        }

        return null;
    }

    /**
     * @param array<array-key, mixed>|string $value
     * @return list<string>
     */
    private static function encodingList(array|string $value, string $function, int $argument, string $parameter): array
    {
        if (is_string($value)) {
            if ($value === '') {
                return [];
            }
            if (strlen($value) > 2 && $value[0] === '"' && $value[-1] === '"') {
                $value = substr($value, 1, -1);
            }
            $items = explode(',', $value);
            $items = array_map(static fn (string $item): string => trim($item, " \t"), $items);
        } else {
            $items = array_map(static fn (mixed $item): string => (string) $item, array_values($value));
        }
        $list = [];
        $auto = false;
        foreach ($items as $item) {
            if (strtolower($item) === 'auto') {
                if (!$auto) {
                    $auto = true;
                    array_push($list, 'ASCII', 'UTF-8', ...self::$autoEncodings);
                }
                continue;
            }
            $found = self::canonical($item);
            if ($found === null) {
                throw new ValueError(sprintf('%s(): Argument #%d ($%s) contains invalid encoding "%s"', $function, $argument, $parameter, $item));
            }
            $list[] = $found;
        }

        return $list;
    }

    /** Bytes per character for fixed-width encodings, 0 for UTF-8 and UTF-16. */
    private static function fixedWidth(string $name): int
    {
        return match (true) {
            $name === 'UTF-8', str_starts_with($name, 'UTF-16') => 0,
            str_starts_with($name, 'UCS-2') => 2,
            str_starts_with($name, 'UCS-4'), str_starts_with($name, 'UTF-32') => 4,
            default => 1,
        };
    }

    private static function hasHighBytes(string $string): bool
    {
        return preg_match('/[\x80-\xFF]/', $string) === 1;
    }

    private static function isUtf8(string $string): bool
    {
        return preg_match('//u', $string) === 1;
    }

    private static function utf8Length(int $byte): int
    {
        return match (true) {
            $byte < 0xC2 => 1,
            $byte < 0xE0 => 2,
            $byte < 0xF0 => 3,
            $byte < 0xF5 => 4,
            default => 1,
        };
    }

    /** Character count of valid UTF-8 (bytes that are not continuation bytes). */
    private static function utf8Count(string $string): int
    {
        return strlen($string) - (int) preg_match_all('/[\x80-\xBF]/', $string);
    }

    private static function length(string $string, string $name): int
    {
        $width = self::fixedWidth($name);
        if ($width > 0) {
            return intdiv(strlen($string), $width);
        }
        if ($name === 'UTF-8' && self::isUtf8($string)) {
            return self::utf8Count($string);
        }

        return count(self::decode($string, $name));
    }

    private static function width(string $string, string $name): int
    {
        if ($name === 'UTF-8' && self::isUtf8($string)) {
            $count = self::utf8Count($string);
            if (preg_match('/[\x{1100}-\x{10FFFF}]/u', $string) !== 1) {
                return $count;
            }

            return $count + (int) preg_match_all(self::wideClass(), $string);
        }
        $units = self::decode($string, $name);

        return count($units) + count(preg_grep(self::wideClass(), $units) ?: []);
    }

    private static function wideClass(): string
    {
        return self::$wide ??= '/'.(require __DIR__.'/Resources/east_asian_width.php')['wide'].'/u';
    }

    private static function slice(string $string, int $from, int $count, string $name): string
    {
        $size = strlen($string);
        if ($count <= 0 || $from >= $size) {
            return '';
        }
        $width = self::fixedWidth($name);
        if ($width > 0) {
            $from *= $width;
            if ($from >= $size) {
                return '';
            }

            return substr($string, $from, $count > intdiv($size, $width) ? $size : $count * $width);
        }
        if ($name === 'UTF-8') {
            if (!self::hasHighBytes($string)) {
                return substr($string, $from, $count);
            }
            if (self::isUtf8($string)) {
                $begin = self::skipUtf8($string, 0, $from);
                if ($begin >= $size) {
                    return '';
                }
                $end = $count >= $size ? $size : self::skipUtf8($string, $begin, $count);

                return substr($string, $begin, $end - $begin);
            }
        }

        return self::encode(array_slice(self::decode($string, $name), $from, $count), $name);
    }

    /** Byte offset reached after skipping $characters characters of valid UTF-8 from $offset. */
    private static function skipUtf8(string $string, int $offset, int $characters): int
    {
        $size = strlen($string);
        while ($characters > 0 && $offset < $size) {
            $step = min($characters, 65535);
            preg_match('/\G(?:[^\x80-\xBF][\x80-\xBF]*){0,'.$step.'}/', $string, $match, 0, $offset);
            $offset += strlen($match[0]);
            $characters -= $step;
        }

        return min($offset, $size);
    }

    private static function cutUtf16(string $string, int $from, int $length, string $name): string
    {
        $size = strlen($string);
        $little = $name === 'UTF-16LE';
        if ($name === 'UTF-16') {
            if ($length < 2 || $size < 2) {
                return '';
            }
            $mark = (ord($string[0]) << 8) | ord($string[1]);
            if ($mark === 0xFFFE) {
                $little = true;
                $from = max($from, 2);
            } elseif ($mark === 0xFEFF) {
                $from = max($from, 2);
            }
        }
        $length = min($length, $size - $from);
        $from &= ~1;
        $length &= ~1;
        if ($length < 2 || $size - $from < 2) {
            return '';
        }
        $end = min($from + $length, $size);
        $last = $little
            ? (ord($string[$end - 1]) << 8) | ord($string[$end - 2])
            : (ord($string[$end - 2]) << 8) | ord($string[$end - 1]);
        if ($last >= 0xD800 && $last <= 0xDBFF) {
            $end -= 2;
        }

        return substr($string, $from, $end - $from);
    }

    /**
     * Decodes $string into units: a UTF-8 string per valid character, BAD per
     * invalid sequence, or the raw integer of a code point UTF-8 cannot hold.
     *
     * @return list<string|int>
     */
    private static function decode(string $string, string $name): array
    {
        if ($string === '') {
            return [];
        }
        if ($name === 'UTF-8') {
            if (!self::hasHighBytes($string)) {
                return str_split($string);
            }
            if (self::isUtf8($string)) {
                return preg_split('//u', $string, -1, PREG_SPLIT_NO_EMPTY) ?: [];
            }
            preg_match_all(self::UTF8_UNIT, $string, $matches);
            $units = [];
            foreach ($matches[0] as $index => $unit) {
                $units[] = $matches[1][$index] === '' ? self::BAD : $unit;
            }

            return $units;
        }
        if (self::fixedWidth($name) !== 1 || str_starts_with($name, 'UTF-16')) {
            return self::decodeWide($string, $name);
        }
        $units = [];
        if ($name === 'ASCII') {
            foreach (str_split($string) as $byte) {
                $units[] = $byte < "\x80" ? $byte : self::BAD;
            }

            return $units;
        }
        $high = self::encodings()[$name]['high'];
        foreach (str_split($string) as $byte) {
            $value = ord($byte);
            if ($value < 0x80) {
                $units[] = $byte;
            } elseif ($high === null) {
                $units[] = self::character($value);
            } else {
                $units[] = $high[$value - 0x80] === null ? self::BAD : self::character($high[$value - 0x80]);
            }
        }

        return $units;
    }

    /** @return list<string|int> */
    private static function decodeWide(string $string, string $name): array
    {
        $size = str_starts_with($name, 'UTF-16') || str_starts_with($name, 'UCS-2') ? 2 : 4;
        $little = str_ends_with($name, 'LE');
        if (!$little && !str_ends_with($name, 'BE') && strlen($string) >= $size) {
            $mark = substr($string, 0, $size);
            if ($mark === ($size === 2 ? "\xFF\xFE" : "\xFF\xFE\x00\x00")) {
                $little = true;
                $string = substr($string, $size);
            } elseif ($mark === ($size === 2 ? "\xFE\xFF" : "\x00\x00\xFE\xFF")) {
                $string = substr($string, $size);
            }
        }
        $length = strlen($string);
        $whole = $length - $length % $size;
        $format = $size === 2 ? ($little ? 'v' : 'n') : ($little ? 'V' : 'N');
        $words = $whole > 0 ? array_values(unpack($format.'*', substr($string, 0, $whole)) ?: []) : [];
        $utf16 = str_starts_with($name, 'UTF-16');
        $utf32 = str_starts_with($name, 'UTF-32');
        $units = [];
        $count = count($words);
        for ($index = 0; $index < $count; $index++) {
            $word = $words[$index];
            if ($utf16) {
                if ($word >= 0xD800 && $word <= 0xDBFF) {
                    if ($index + 1 >= $count) {
                        $units[] = self::BAD;
                        continue;
                    }
                    $next = $words[$index + 1];
                    if ($next >= 0xD800 && $next <= 0xDBFF) {
                        $units[] = self::BAD;
                    } elseif ($next >= 0xDC00 && $next <= 0xDFFF) {
                        $units[] = self::character(((($word & 0x3FF) << 10) | ($next & 0x3FF)) + 0x10000);
                        $index++;
                    } else {
                        $units[] = self::BAD;
                        $units[] = self::character($next);
                        $index++;
                    }
                } elseif ($word >= 0xDC00 && $word <= 0xDFFF) {
                    $units[] = self::BAD;
                } else {
                    $units[] = self::character($word);
                }
            } elseif ($utf32) {
                $units[] = $word < 0x110000 && ($word < 0xD800 || $word > 0xDFFF) ? self::character($word) : self::BAD;
            } else {
                $units[] = $word === 0xFFFFFFFF ? self::BAD : self::unit($word);
            }
        }
        if ($whole !== $length) {
            $units[] = self::BAD;
        }

        return $units;
    }

    private static function unit(int $codePoint): string|int
    {
        return $codePoint < 0xD800 || ($codePoint > 0xDFFF && $codePoint < 0x110000)
            ? self::character($codePoint)
            : $codePoint;
    }

    /** @param list<string|int> $units */
    private static function encode(array $units, string $name): string
    {
        if ($name === 'UTF-8') {
            $out = '';
            foreach ($units as $unit) {
                if (is_string($unit)) {
                    $out .= $unit;
                } elseif ($unit !== self::BAD && $unit < 0x110000) {
                    $out .= self::character($unit);
                } else {
                    $out .= self::failure($unit, $name);
                }
            }

            return $out;
        }
        $out = '';
        foreach ($units as $unit) {
            if ($unit === self::BAD) {
                $out .= self::failure(self::BAD, $name);
                continue;
            }
            if (is_int($unit)) {
                $out .= self::encodeCodePoint($unit, $name) ?? self::failure($unit, $name);
                continue;
            }
            // A case mapping can turn one character into several.
            foreach (strlen($unit) === 1 ? [$unit] : (preg_split('//u', $unit, -1, PREG_SPLIT_NO_EMPTY) ?: []) as $character) {
                $codePoint = self::codePoint($character);
                $out .= self::encodeCodePoint($codePoint, $name) ?? self::failure($codePoint, $name);
            }
        }

        return $out;
    }

    private static function encodeCodePoint(int $codePoint, string $name): ?string
    {
        if ($name === 'UTF-8') {
            return $codePoint < 0x110000 ? self::character($codePoint) : null;
        }
        if ($name === 'ASCII') {
            return $codePoint < 0x80 ? chr($codePoint) : null;
        }
        if ($name === '8bit' || $name === 'ISO-8859-1') {
            return $codePoint < 0x100 ? chr($codePoint) : null;
        }
        $little = str_ends_with($name, 'LE');
        if (str_starts_with($name, 'UTF-16')) {
            if ($codePoint < 0x10000) {
                return pack($little ? 'v' : 'n', $codePoint);
            }
            if ($codePoint < 0x110000) {
                $codePoint -= 0x10000;

                return pack($little ? 'vv' : 'nn', 0xD800 | ($codePoint >> 10), 0xDC00 | ($codePoint & 0x3FF));
            }

            return null;
        }
        if (str_starts_with($name, 'UCS-2')) {
            return $codePoint < 0x10000 ? pack($little ? 'v' : 'n', $codePoint) : null;
        }
        if (str_starts_with($name, 'UTF-32')) {
            return $codePoint < 0x110000 ? pack($little ? 'V' : 'N', $codePoint) : null;
        }
        if (str_starts_with($name, 'UCS-4')) {
            return pack($little ? 'V' : 'N', $codePoint);
        }
        if (!isset(self::$reverse[$name])) {
            $reverse = [];
            for ($byte = 0; $byte < 0x80; $byte++) {
                $reverse[$byte] = chr($byte);
            }
            foreach (self::encodings()[$name]['high'] ?? [] as $index => $mapped) {
                if ($mapped !== null) {
                    $reverse[$mapped] ??= chr($index + 0x80);
                }
            }
            foreach (self::encodings()[$name]['encode'] as $mapped => $byte) {
                $reverse[$mapped] = $byte === null ? null : chr($byte);
            }
            self::$reverse[$name] = $reverse;
        }

        return self::$reverse[$name][$codePoint] ?? null;
    }

    /** What mbstring emits for an invalid input sequence (BAD) or an unrepresentable code point. */
    private static function failure(int $codePoint, string $name): string
    {
        if (self::$mode === self::MODE_NONE) {
            return '';
        }
        if ($codePoint === self::BAD || self::$mode === self::MODE_CHAR) {
            $replacement = self::encodeCodePoint(self::$substitute, $name);
            if ($replacement !== null) {
                return $replacement;
            }
            if (self::$mode === self::MODE_CHAR && self::$substitute !== 0x3F) {
                return self::encodeCodePoint(0x3F, $name) ?? '';
            }

            return '';
        }
        $text = (self::$mode === self::MODE_LONG ? 'U+' : '&#x').strtoupper(dechex($codePoint))
            .(self::$mode === self::MODE_ENTITY ? ';' : '');
        $out = '';
        foreach (str_split($text) as $character) {
            $out .= self::encodeCodePoint(ord($character), $name) ?? '';
        }

        return $out;
    }

    private static function codePoint(string $character): int
    {
        $first = ord($character[0]);

        return match (strlen($character)) {
            1 => $first,
            2 => (($first & 0x1F) << 6) | (ord($character[1]) & 0x3F),
            3 => (($first & 0x0F) << 12) | ((ord($character[1]) & 0x3F) << 6) | (ord($character[2]) & 0x3F),
            default => (($first & 0x07) << 18) | ((ord($character[1]) & 0x3F) << 12)
                | ((ord($character[2]) & 0x3F) << 6) | (ord($character[3]) & 0x3F),
        };
    }

    private static function character(int $codePoint): string
    {
        if ($codePoint < 0x80) {
            return chr($codePoint);
        }
        if ($codePoint < 0x800) {
            return chr(0xC0 | ($codePoint >> 6)).chr(0x80 | ($codePoint & 0x3F));
        }
        if ($codePoint < 0x10000) {
            return chr(0xE0 | ($codePoint >> 12)).chr(0x80 | (($codePoint >> 6) & 0x3F)).chr(0x80 | ($codePoint & 0x3F));
        }

        return chr(0xF0 | ($codePoint >> 18)).chr(0x80 | (($codePoint >> 12) & 0x3F))
            .chr(0x80 | (($codePoint >> 6) & 0x3F)).chr(0x80 | ($codePoint & 0x3F));
    }

    private static function valid(string $string, string $name): bool
    {
        if ($name === 'UTF-8') {
            return self::isUtf8($string);
        }
        if ($name === '8bit' || $name === 'ISO-8859-1') {
            return true;
        }

        return !in_array(self::BAD, self::decode($string, $name), true);
    }

    /** @param array<array-key, mixed> $values */
    private static function validArray(array $values, string $name): bool
    {
        foreach ($values as $key => $value) {
            if (is_string($key) && !self::valid($key, $name)) {
                return false;
            }
            if (is_string($value)) {
                if (!self::valid($value, $name)) {
                    return false;
                }
            } elseif (is_array($value)) {
                if (!self::validArray($value, $name)) {
                    return false;
                }
            } elseif (!is_int($value) && !is_float($value) && !is_bool($value) && $value !== null) {
                return false;
            }
        }

        return true;
    }

    /** Converts to UTF-8 with every error replaced by a 0xFF byte (mbstring's search mode). */
    private static function toMarkedUtf8(string $string, string $name): string
    {
        if ($name === 'UTF-8' && self::isUtf8($string)) {
            return $string;
        }
        $out = '';
        foreach (self::decode($string, $name) as $unit) {
            $out .= is_string($unit) ? $unit : ($unit !== self::BAD && $unit < 0x110000 ? self::character($unit) : "\xFF");
        }

        return $out;
    }

    private static function find(string $haystack, string $needle, string $name, int $offset, bool $reverse): int
    {
        if ($name !== 'UTF-8') {
            $haystack = self::toMarkedUtf8($haystack, $name);
            $needle = self::toMarkedUtf8($needle, $name);
        }
        $size = strlen($haystack);
        $start = self::pointer($haystack, 0, $size, $offset);
        if ($start === null) {
            return -2;
        }
        if ($size < strlen($needle)) {
            return -1;
        }
        if (!$reverse) {
            $found = strpos($haystack, $needle, $start);
        } elseif ($offset >= 0) {
            $found = strrpos($haystack, $needle, $start);
        } else {
            $limit = self::pointer($haystack, $start, $size, self::utf8Count($needle)) ?? $size;
            $found = $limit < strlen($needle) ? false : strrpos(substr($haystack, 0, $limit), $needle);
        }

        return $found === false ? -1 : self::utf8Count(substr($haystack, 0, $found));
    }

    private static function findFolded(string $haystack, string $needle, string $name, int $offset, bool $reverse): int
    {
        $haystack = self::foldForSearch($haystack, $name);
        $needle = self::foldForSearch($needle, $name);

        return self::find($haystack, $needle, 'UTF-8', $offset, $reverse);
    }

    private static function foldForSearch(string $string, string $name): string
    {
        if ($name === 'UTF-8' && !self::hasHighBytes($string)) {
            return strtolower($string);
        }
        if ($name === 'UTF-8' && self::isUtf8($string)) {
            return strtr($string, self::caseMap('fold', false));
        }
        $map = self::caseMap('fold', false);
        $out = '';
        foreach (self::decode($string, $name) as $unit) {
            $out .= is_string($unit) ? ($map[$unit] ?? $unit) : ($unit !== self::BAD && $unit < 0x110000 ? self::character($unit) : "\xFF");
        }

        return $out;
    }

    /** mbstring's offset_to_pointer_utf8(). */
    private static function pointer(string $string, int $base, int $end, int $offset): ?int
    {
        if ($offset < 0) {
            $position = $end;
            while ($offset < 0) {
                if ($position <= $base) {
                    return null;
                }
                if ((ord($string[--$position]) & 0xC0) !== 0x80) {
                    $offset++;
                }
            }

            return $position;
        }
        $position = $base;
        while ($offset-- > 0) {
            if ($position >= $end) {
                return null;
            }
            $position += self::utf8Length(ord($string[$position]));
        }

        return min($position, $end);
    }

    private static function position(int $result, string $function): int|false
    {
        if ($result === -2) {
            throw new ValueError($function.'(): Argument #3 ($offset) must be contained in argument #1 ($haystack)');
        }

        return $result < 0 ? false : $result;
    }

    private static function part(string $haystack, int $position, bool $before, string $name): string|false
    {
        if ($position < 0) {
            return false;
        }

        return $before
            ? self::slice($haystack, 0, $position, $name)
            : self::slice($haystack, $position, PHP_INT_MAX, $name);
    }

    private static function trim(string $string, ?string $characters, ?string $encoding, int $mode, string $function): string
    {
        $name = self::requireEncoding($encoding, $function, 3);
        if ($name === 'UTF-8' && self::isUtf8($string) && ($characters === null || self::isUtf8($characters))) {
            if ($characters === '') {
                return $string;
            }
            $class = $characters === null
                ? '[\x{20}\x{C}\x{A}\x{D}\x{9}\x{B}\x{0}\x{A0}\x{1680}\x{2000}-\x{200A}\x{2028}\x{2029}\x{202F}\x{205F}\x{3000}\x{85}\x{180E}]'
                : '['.preg_quote($characters, '/').']';
            $pattern = match ($mode) {
                1 => '/\A'.$class.'+/u',
                2 => '/'.$class.'+\z/u',
                default => '/\A'.$class.'+|'.$class.'+\z/u',
            };

            return (string) preg_replace($pattern, '', $string);
        }
        $set = [];
        if ($characters === null) {
            foreach (self::TRIM as $codePoint) {
                $set[self::character($codePoint)] = true;
            }
        } else {
            foreach (self::decode($characters, $name) as $unit) {
                $set[is_string($unit) ? $unit : '#'.$unit] = true;
            }
            if ($set === []) {
                return $string;
            }
        }
        $units = self::decode($string, $name);
        $left = 0;
        $right = 0;
        foreach ($units as $unit) {
            if (isset($set[is_string($unit) ? $unit : '#'.$unit])) {
                if (($mode & 1) !== 0) {
                    $left++;
                }
                if (($mode & 2) !== 0) {
                    $right++;
                }
            } else {
                $mode &= ~1;
                if (($mode & 2) !== 0) {
                    $right = 0;
                }
            }
        }
        if ($left === 0 && $right === 0) {
            return $string;
        }

        return self::slice($string, $left, count($units) - $right - $left, $name);
    }

    /**
     * @param list<string> $sources
     */
    private static function convertString(string $string, string $target, array $sources): string|false
    {
        $source = $sources[0];
        if (count($sources) > 1) {
            $source = self::guess([$string], $sources, false, true);
            if ($source === null) {
                trigger_error('mb_convert_encoding(): Unable to detect character encoding', E_USER_WARNING);

                return false;
            }
        }

        return self::convert($string, $target, $source);
    }

    /**
     * @param array<array-key, mixed> $values
     * @param list<string> $sources
     * @return array<array-key, mixed>
     */
    private static function convertArray(array $values, string $target, array $sources): array
    {
        $out = [];
        foreach ($values as $key => $value) {
            if (is_string($key)) {
                $key = self::convertString($key, $target, $sources);
                if ($key === false) {
                    continue;
                }
            }
            if (is_string($value)) {
                $value = self::convertString($value, $target, $sources);
                if ($value === false) {
                    continue;
                }
            } elseif (is_array($value)) {
                $value = self::convertArray($value, $target, $sources);
            } elseif (is_object($value)) {
                trigger_error('mb_convert_encoding(): Object is not supported', E_USER_WARNING);
                continue;
            }
            $out[$key] = $value;
        }

        return $out;
    }

    private static function convert(string $string, string $target, string $source): string
    {
        if ($source === $target && $source === 'UTF-8' && self::isUtf8($string)) {
            return $string;
        }
        if (!self::hasHighBytes($string) && self::asciiCompatible($source) && self::asciiCompatible($target)) {
            return $string;
        }

        return self::encode(self::decode($string, $source), $target);
    }

    private static function asciiCompatible(string $name): bool
    {
        return $name === 'UTF-8' || (self::fixedWidth($name) === 1 && self::encodings()[$name]['encode'] === []);
    }

    /** @param list<string> $strings */
    private static function collectStrings(mixed $value, array &$strings): void
    {
        if (is_string($value)) {
            $strings[] = $value;
        } elseif (is_array($value) || is_object($value)) {
            foreach ((array) $value as $item) {
                self::collectStrings($item, $strings);
            }
        }
    }

    private static function convertVariable(mixed &$value, string $source, string $target): void
    {
        if (is_string($value)) {
            $value = self::convert($value, $target, $source);
        } elseif (is_array($value)) {
            foreach ($value as &$item) {
                self::convertVariable($item, $source, $target);
            }
            unset($item);
        } elseif (is_object($value)) {
            foreach (get_object_vars($value) as $property => $item) {
                self::convertVariable($item, $source, $target);
                $value->{$property} = $item;
            }
        }
    }

    /**
     * mbstring's candidate scoring: invalid input disqualifies (strict) or
     * costs 1000, rare code points 30, ASCII punctuation 6, others 1.
     *
     * @param list<string> $strings
     * @param list<string> $candidates
     */
    private static function guess(array $strings, array $candidates, bool $strict, bool $orderSignificant): ?string
    {
        $total = count($candidates);
        if ($total === 1) {
            if ($strict) {
                foreach ($strings as $string) {
                    if (!self::valid($string, $candidates[0])) {
                        return null;
                    }
                }
            }

            return $candidates[0];
        }
        if (count($strings) === 1 && $strings[0] === '') {
            return $candidates[0];
        }
        $best = null;
        $bestScore = PHP_INT_MAX;
        foreach ($candidates as $index => $candidate) {
            $demerits = 0;
            foreach ($strings as $string) {
                if (($candidate === 'UTF-8' && str_starts_with($string, "\xEF\xBB\xBF"))
                    || ($candidate === 'UTF-16BE' && str_starts_with($string, "\xFE\xFF"))
                    || ($candidate === 'UTF-16LE' && str_starts_with($string, "\xFF\xFE"))) {
                    $string = substr($string, $candidate === 'UTF-8' ? 3 : 2);
                }
                foreach (self::decode($string, $candidate) as $unit) {
                    if ($unit === self::BAD) {
                        if ($strict) {
                            continue 3;
                        }
                        $demerits += 1000;
                        continue;
                    }
                    $demerits += self::demerits(is_string($unit) ? self::codePoint($unit) : $unit);
                }
            }
            $score = (int) ($demerits * ($orderSignificant ? 1.0 + (0.3 * $index) / $total : 1.0));
            if ($score < $bestScore) {
                $best = $candidate;
                $bestScore = $score;
            }
        }

        return $best;
    }

    private static function demerits(int $codePoint): int
    {
        if ($codePoint > 0xFFFF) {
            return 40;
        }
        if ($codePoint >= 0x21 && $codePoint <= 0x2F) {
            return 6;
        }
        self::$rare ??= hex2bin((require __DIR__.'/Resources/rare_code_points.php')['bits']) ?: '';

        return ((ord(self::$rare[$codePoint >> 3]) >> ($codePoint & 7)) & 1) === 1 ? 30 : 1;
    }

    /**
     * @param array<array-key, mixed> $map
     * @return list<array{int, int, int, int}>
     */
    private static function entityMap(array $map, string $function): array
    {
        if (count($map) % 4 !== 0) {
            throw new ValueError($function.'(): Argument #2 ($map) must have a multiple of 4 elements');
        }
        $values = [];
        foreach ($map as $value) {
            if (!is_int($value) && !is_float($value) && !is_bool($value) && $value !== null
                && !(is_string($value) && is_numeric($value))) {
                throw new ValueError($function.'(): Argument #2 ($map) must only be composed of values of type int');
            }
            $values[] = ((int) $value) & 0xFFFFFFFF;
        }

        return array_chunk($values, 4);
    }

    // ------------------------------------------------------------------ case

    /** @return array<string, string> */
    private static function caseMap(string $kind, bool $full, bool $turkish = false): array
    {
        $key = $kind.($full ? '_full' : '').($turkish ? '_tr' : '');
        if (isset(self::$caseMaps[$key])) {
            return self::$caseMaps[$key];
        }
        self::$caseTables ??= require __DIR__.'/Resources/case_maps.php';
        $map = self::$caseTables[$kind];
        if ($full) {
            $map = self::$caseTables[$kind.'_full'] + $map;
        }
        if ($turkish) {
            // ext-mbstring maps the dotted and dotless i the Turkish way for ISO-8859-9.
            $map = match ($kind) {
                'upper', 'title' => ['i' => "\u{130}"],
                default => ['I' => "\u{131}", "\u{130}" => 'i'],
            } + $map;
        }

        return self::$caseMaps[$key] = $map;
    }

    private static function caseClass(string $character): int
    {
        if (isset(self::$caseClasses[$character])) {
            return self::$caseClasses[$character];
        }
        self::$caseProperties ??= require __DIR__.'/Resources/case_properties.php';
        $class = preg_match('/'.self::$caseProperties['cased'].'/u', $character) === 1
            ? 1
            : (preg_match('/'.self::$caseProperties['ignorable'].'/u', $character) === 1 ? 2 : 0);
        if (count(self::$caseClasses) < 4096) {
            self::$caseClasses[$character] = $class;
        }

        return $class;
    }

    private static function convertCase(string $string, int $mode, string $name): string
    {
        $full = $mode < 4;
        $kind = ['upper', 'lower', 'title', 'fold'][$mode % 4];
        if ($name === 'UTF-8' && $kind !== 'title') {
            if (!self::hasHighBytes($string)) {
                return $kind === 'upper' ? strtoupper($string) : strtolower($string);
            }
            if (self::isUtf8($string) && !($full && $kind === 'lower' && str_contains($string, "\xCE\xA3"))) {
                return strtr($string, self::caseMap($kind, $full));
            }
        }
        $turkish = $name === 'ISO-8859-9';
        $units = self::decode($string, $name);
        $out = [];
        if ($kind === 'title') {
            $lower = self::caseMap('lower', $full, $turkish);
            $title = self::caseMap('title', $full, $turkish);
            $lowerMode = false;
            foreach ($units as $index => $unit) {
                if ($unit === self::BAD) {
                    $out[] = $unit;
                    continue;
                }
                if (!is_string($unit)) {
                    $out[] = $unit;
                    $lowerMode = false;
                    continue;
                }
                if (!$lowerMode) {
                    $out[] = $title[$unit] ?? $unit;
                } elseif ($full && $unit === "\xCE\xA3" && self::finalSigma($units, $index)) {
                    $out[] = "\xCF\x82";
                } else {
                    $out[] = $lower[$unit] ?? $unit;
                }
                $class = self::caseClass($unit);
                if ($class !== 2) {
                    $lowerMode = $class === 1;
                }
            }

            return self::encode($out, $name);
        }
        $map = self::caseMap($kind, $full, $turkish);
        foreach ($units as $index => $unit) {
            if (!is_string($unit)) {
                $out[] = $unit;
            } elseif ($full && $kind === 'lower' && $unit === "\xCE\xA3" && self::finalSigma($units, $index)) {
                $out[] = "\xCF\x82";
            } else {
                $out[] = $map[$unit] ?? $unit;
            }
        }

        return self::encode($out, $name);
    }

    /** @param list<string|int> $units */
    private static function finalSigma(array $units, int $index): bool
    {
        for ($cursor = $index - 1; $cursor >= 0; $cursor--) {
            $class = is_string($units[$cursor]) ? self::caseClass($units[$cursor]) : 0;
            if ($class !== 2) {
                break;
            }
        }
        if ($cursor < 0 || $class !== 1) {
            return false;
        }
        $count = count($units);
        for ($cursor = $index + 1; $cursor < $count; $cursor++) {
            $class = is_string($units[$cursor]) ? self::caseClass($units[$cursor]) : 0;
            if ($class !== 2) {
                return $class !== 1;
            }
        }

        return true;
    }

    private static function changeFirst(string $string, int $mode, string $name): string
    {
        $first = self::slice($string, 0, 1, $name);
        $head = self::convertCase($first, $mode, $name);
        if ($head === $first) {
            return $string;
        }

        return $head.self::slice($string, 1, PHP_INT_MAX, $name);
    }
}
