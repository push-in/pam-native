<?php

declare(strict_types=1);

/*
 * ext-mbstring for PAM's mobile runtimes. Android and iOS embed PHP without
 * ext-mbstring; Composer loads this file (autoload "files") before any
 * application or package code, so mb_* calls behave as on a desktop PHP.
 * Each function is only defined when it does not exist yet, so a real
 * ext-mbstring (or an application polyfill loaded first) always wins.
 * Like the internal functions, a null string argument is read as "".
 */

use Pam\Native\Polyfill\Mbstring;

foreach (['MB_CASE_UPPER' => 0, 'MB_CASE_LOWER' => 1, 'MB_CASE_TITLE' => 2, 'MB_CASE_FOLD' => 3, 'MB_CASE_UPPER_SIMPLE' => 4, 'MB_CASE_LOWER_SIMPLE' => 5, 'MB_CASE_TITLE_SIMPLE' => 6, 'MB_CASE_FOLD_SIMPLE' => 7] as $constant => $value) {
    if (!defined($constant)) {
        define($constant, $value);
    }
}
unset($constant, $value);

if (!function_exists('mb_check_encoding')) {
    function mb_check_encoding(array|string|null $value = null, ?string $encoding = null): bool
    {
        return Mbstring::mb_check_encoding($value, $encoding);
    }
}

if (!function_exists('mb_chr')) {
    function mb_chr(int $codepoint, ?string $encoding = null): string|false
    {
        return Mbstring::mb_chr($codepoint, $encoding);
    }
}

if (!function_exists('mb_convert_case')) {
    function mb_convert_case(?string $string, int $mode, ?string $encoding = null): string
    {
        return Mbstring::mb_convert_case($string ?? '', $mode, $encoding);
    }
}

if (!function_exists('mb_convert_encoding')) {
    function mb_convert_encoding(
        array|string $string,
        string $to_encoding,
        array|string|null $from_encoding = null,
    ): array|string|false
    {
        return Mbstring::mb_convert_encoding($string, $to_encoding, $from_encoding);
    }
}

if (!function_exists('mb_convert_variables')) {
    function mb_convert_variables(
        string $to_encoding,
        array|string $from_encoding,
        mixed &$var,
        mixed &...$vars,
    ): string|false
    {
        return Mbstring::mb_convert_variables($to_encoding, $from_encoding, $var, ...$vars);
    }
}

if (!function_exists('mb_decode_numericentity')) {
    function mb_decode_numericentity(?string $string, array $map, ?string $encoding = null): string
    {
        return Mbstring::mb_decode_numericentity($string ?? '', $map, $encoding);
    }
}

if (!function_exists('mb_detect_encoding')) {
    function mb_detect_encoding(
        ?string $string,
        array|string|null $encodings = null,
        bool $strict = false,
    ): string|false
    {
        return Mbstring::mb_detect_encoding($string ?? '', $encodings, $strict);
    }
}

if (!function_exists('mb_detect_order')) {
    function mb_detect_order(array|string|null $encoding = null): array|bool
    {
        return Mbstring::mb_detect_order($encoding);
    }
}

if (!function_exists('mb_encode_numericentity')) {
    function mb_encode_numericentity(
        ?string $string,
        array $map,
        ?string $encoding = null,
        bool $hex = false,
    ): string
    {
        return Mbstring::mb_encode_numericentity($string ?? '', $map, $encoding, $hex);
    }
}

if (!function_exists('mb_encoding_aliases')) {
    function mb_encoding_aliases(string $encoding): array
    {
        return Mbstring::mb_encoding_aliases($encoding);
    }
}

if (!function_exists('mb_get_info')) {
    function mb_get_info(string $type = 'all'): array|string|int|false
    {
        return Mbstring::mb_get_info($type);
    }
}

if (!function_exists('mb_internal_encoding')) {
    function mb_internal_encoding(?string $encoding = null): string|bool
    {
        return Mbstring::mb_internal_encoding($encoding);
    }
}

if (!function_exists('mb_language')) {
    function mb_language(?string $language = null): string|bool
    {
        return Mbstring::mb_language($language);
    }
}

if (!function_exists('mb_lcfirst')) {
    function mb_lcfirst(?string $string, ?string $encoding = null): string
    {
        return Mbstring::mb_lcfirst($string ?? '', $encoding);
    }
}

if (!function_exists('mb_list_encodings')) {
    function mb_list_encodings(): array
    {
        return Mbstring::mb_list_encodings();
    }
}

if (!function_exists('mb_ltrim')) {
    function mb_ltrim(?string $string, ?string $characters = null, ?string $encoding = null): string
    {
        return Mbstring::mb_ltrim($string ?? '', $characters, $encoding);
    }
}

if (!function_exists('mb_ord')) {
    function mb_ord(?string $string, ?string $encoding = null): int|false
    {
        return Mbstring::mb_ord($string ?? '', $encoding);
    }
}

if (!function_exists('mb_preferred_mime_name')) {
    function mb_preferred_mime_name(string $encoding): string|false
    {
        return Mbstring::mb_preferred_mime_name($encoding);
    }
}

if (!function_exists('mb_rtrim')) {
    function mb_rtrim(?string $string, ?string $characters = null, ?string $encoding = null): string
    {
        return Mbstring::mb_rtrim($string ?? '', $characters, $encoding);
    }
}

if (!function_exists('mb_scrub')) {
    function mb_scrub(?string $string, ?string $encoding = null): string
    {
        return Mbstring::mb_scrub($string ?? '', $encoding);
    }
}

if (!function_exists('mb_str_pad')) {
    function mb_str_pad(
        ?string $string,
        int $length,
        string $pad_string = ' ',
        int $pad_type = STR_PAD_RIGHT,
        ?string $encoding = null,
    ): string
    {
        return Mbstring::mb_str_pad($string ?? '', $length, $pad_string, $pad_type, $encoding);
    }
}

if (!function_exists('mb_str_split')) {
    function mb_str_split(?string $string, int $length = 1, ?string $encoding = null): array
    {
        return Mbstring::mb_str_split($string ?? '', $length, $encoding);
    }
}

if (!function_exists('mb_strcut')) {
    function mb_strcut(
        ?string $string,
        int $start,
        ?int $length = null,
        ?string $encoding = null,
    ): string
    {
        return Mbstring::mb_strcut($string ?? '', $start, $length, $encoding);
    }
}

if (!function_exists('mb_strimwidth')) {
    function mb_strimwidth(
        ?string $string,
        int $start,
        int $width,
        string $trim_marker = '',
        ?string $encoding = null,
    ): string
    {
        return Mbstring::mb_strimwidth($string ?? '', $start, $width, $trim_marker, $encoding);
    }
}

if (!function_exists('mb_stripos')) {
    function mb_stripos(
        ?string $haystack,
        ?string $needle,
        int $offset = 0,
        ?string $encoding = null,
    ): int|false
    {
        return Mbstring::mb_stripos($haystack ?? '', $needle ?? '', $offset, $encoding);
    }
}

if (!function_exists('mb_stristr')) {
    function mb_stristr(
        ?string $haystack,
        ?string $needle,
        bool $before_needle = false,
        ?string $encoding = null,
    ): string|false
    {
        return Mbstring::mb_stristr($haystack ?? '', $needle ?? '', $before_needle, $encoding);
    }
}

if (!function_exists('mb_strlen')) {
    function mb_strlen(?string $string, ?string $encoding = null): int
    {
        return Mbstring::mb_strlen($string ?? '', $encoding);
    }
}

if (!function_exists('mb_strpos')) {
    function mb_strpos(
        ?string $haystack,
        ?string $needle,
        int $offset = 0,
        ?string $encoding = null,
    ): int|false
    {
        return Mbstring::mb_strpos($haystack ?? '', $needle ?? '', $offset, $encoding);
    }
}

if (!function_exists('mb_strrchr')) {
    function mb_strrchr(
        ?string $haystack,
        ?string $needle,
        bool $before_needle = false,
        ?string $encoding = null,
    ): string|false
    {
        return Mbstring::mb_strrchr($haystack ?? '', $needle ?? '', $before_needle, $encoding);
    }
}

if (!function_exists('mb_strrichr')) {
    function mb_strrichr(
        ?string $haystack,
        ?string $needle,
        bool $before_needle = false,
        ?string $encoding = null,
    ): string|false
    {
        return Mbstring::mb_strrichr($haystack ?? '', $needle ?? '', $before_needle, $encoding);
    }
}

if (!function_exists('mb_strripos')) {
    function mb_strripos(
        ?string $haystack,
        ?string $needle,
        int $offset = 0,
        ?string $encoding = null,
    ): int|false
    {
        return Mbstring::mb_strripos($haystack ?? '', $needle ?? '', $offset, $encoding);
    }
}

if (!function_exists('mb_strrpos')) {
    function mb_strrpos(
        ?string $haystack,
        ?string $needle,
        int $offset = 0,
        ?string $encoding = null,
    ): int|false
    {
        return Mbstring::mb_strrpos($haystack ?? '', $needle ?? '', $offset, $encoding);
    }
}

if (!function_exists('mb_strstr')) {
    function mb_strstr(
        ?string $haystack,
        ?string $needle,
        bool $before_needle = false,
        ?string $encoding = null,
    ): string|false
    {
        return Mbstring::mb_strstr($haystack ?? '', $needle ?? '', $before_needle, $encoding);
    }
}

if (!function_exists('mb_strtolower')) {
    function mb_strtolower(?string $string, ?string $encoding = null): string
    {
        return Mbstring::mb_strtolower($string ?? '', $encoding);
    }
}

if (!function_exists('mb_strtoupper')) {
    function mb_strtoupper(?string $string, ?string $encoding = null): string
    {
        return Mbstring::mb_strtoupper($string ?? '', $encoding);
    }
}

if (!function_exists('mb_strwidth')) {
    function mb_strwidth(?string $string, ?string $encoding = null): int
    {
        return Mbstring::mb_strwidth($string ?? '', $encoding);
    }
}

if (!function_exists('mb_substitute_character')) {
    function mb_substitute_character(string|int|null $substitute_character = null): string|int|bool
    {
        return Mbstring::mb_substitute_character($substitute_character);
    }
}

if (!function_exists('mb_substr')) {
    function mb_substr(
        ?string $string,
        int $start,
        ?int $length = null,
        ?string $encoding = null,
    ): string
    {
        return Mbstring::mb_substr($string ?? '', $start, $length, $encoding);
    }
}

if (!function_exists('mb_substr_count')) {
    function mb_substr_count(?string $haystack, ?string $needle, ?string $encoding = null): int
    {
        return Mbstring::mb_substr_count($haystack ?? '', $needle ?? '', $encoding);
    }
}

if (!function_exists('mb_trim')) {
    function mb_trim(?string $string, ?string $characters = null, ?string $encoding = null): string
    {
        return Mbstring::mb_trim($string ?? '', $characters, $encoding);
    }
}

if (!function_exists('mb_ucfirst')) {
    function mb_ucfirst(?string $string, ?string $encoding = null): string
    {
        return Mbstring::mb_ucfirst($string ?? '', $encoding);
    }
}
