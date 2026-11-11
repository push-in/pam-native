<?php

declare(strict_types=1);

namespace Pam\Native\Web;

use RuntimeException;

/**
 * The PHP functions and PAM Native APIs a web build provides (resources/runtime.js).
 * Anything else fails the build with the file and line, never at runtime.
 */
final class Stdlib
{
    /** PHP functions implemented in `PAM.f`. */
    public const FUNCTIONS = [
        'count', 'max', 'min', 'abs', 'floor', 'ceil', 'round', 'intdiv', 'fmod', 'sqrt', 'pow', 'intval',
        'floatval', 'strval', 'boolval', 'is_null', 'is_string', 'is_int', 'is_float', 'is_numeric', 'is_bool',
        'is_array', 'array_is_list', 'strlen', 'mb_strlen', 'strtoupper', 'strtolower', 'mb_strtoupper',
        'mb_strtolower', 'ucfirst', 'lcfirst', 'ucwords', 'trim', 'ltrim', 'rtrim', 'str_contains',
        'str_starts_with', 'str_ends_with', 'strpos', 'str_repeat', 'str_pad', 'str_replace', 'substr',
        'mb_substr', 'number_format', 'sprintf', 'implode', 'join', 'explode', 'nl2br', 'htmlspecialchars',
        'array_keys', 'array_values', 'array_map', 'array_filter', 'array_reduce', 'array_merge', 'array_slice',
        'array_reverse', 'array_search', 'array_key_exists', 'array_key_first', 'array_key_last', 'array_column',
        'array_combine', 'array_flip', 'array_unique', 'array_sum', 'array_fill', 'array_chunk', 'in_array',
        'range', 'json_encode', 'json_decode', 'time', 'microtime', 'hrtime', 'random_int', 'mt_rand', 'uniqid',
        'spl_object_id', 'gettype', 'get_debug_type', 'date', 'preg_match', 'preg_match_all', 'preg_replace',
        'preg_replace_callback', 'preg_split', 'preg_quote', 'preg_grep',
        'addslashes', 'array_all', 'array_any', 'array_count_values', 'array_diff', 'array_diff_key',
        'array_fill_keys', 'array_find', 'array_intersect', 'array_intersect_key', 'array_pad', 'array_product',
        'array_rand', 'array_replace', 'base64_decode', 'base64_encode', 'base_convert', 'bin2hex', 'bindec', 'chr',
        'ctype_alnum', 'ctype_alpha', 'ctype_digit', 'ctype_lower', 'ctype_punct', 'ctype_space', 'ctype_upper',
        'ctype_xdigit', 'decbin', 'dechex', 'decoct', 'hexdec', 'html_entity_decode', 'htmlspecialchars_decode',
        'http_build_query', 'is_callable', 'is_countable', 'is_iterable', 'is_object', 'is_scalar',
        'iterator_to_array', 'lcg_value', 'mb_chr', 'mb_ord', 'mb_str_split', 'mb_strpos', 'method_exists',
        'octdec', 'ord', 'property_exists', 'rawurldecode', 'rawurlencode', 'str_ireplace', 'str_split',
        'strcasecmp', 'strcmp', 'strip_tags', 'stripos', 'stripslashes', 'stristr', 'strnatcasecmp', 'strnatcmp',
        'strncasecmp', 'strncmp', 'strrchr', 'strrev', 'strrpos', 'strstr', 'strtr', 'substr_count', 'urldecode',
        'urlencode', 'vsprintf', 'wordwrap',
    ];

    /** `Pam\Native` functions and classes, by short name, implemented on `PAM`. */
    public const NATIVE = [
        'useFormFactor' => 'PAM.useFormFactor',
        'useSafeAreaInsets' => 'PAM.useSafeAreaInsets',
        'useWindowDimensions' => 'PAM.useWindowDimensions',
        'useColorScheme' => 'PAM.useColorScheme',
        'signal' => 'PAM.signal',
        'App' => 'PAM.App',
        'Component' => 'PAM.Component',
        'KeyEvent' => 'PAM.KeyEvent',
    ];

    /** Built-in exception classes, thrown as `PAM.Error(type, message)`. */
    public const EXCEPTIONS = [
        'Exception', 'Error', 'RuntimeException', 'LogicException', 'InvalidArgumentException', 'DomainException',
        'LengthException', 'OutOfRangeException', 'OutOfBoundsException', 'RangeException',
        'UnexpectedValueException', 'UnhandledMatchError', 'TypeError', 'ValueError', 'JsonException', 'Throwable',
    ];

    /** Global constants. */
    public const CONSTANTS = [
        'PHP_EOL' => '"\n"',
        'PHP_INT_MAX' => 'Number.MAX_SAFE_INTEGER',
        'PHP_INT_MIN' => 'Number.MIN_SAFE_INTEGER',
        'PHP_FLOAT_EPSILON' => 'Number.EPSILON',
        'M_PI' => 'Math.PI',
        'INF' => 'Infinity',
        'NAN' => 'NaN',
        'ARRAY_FILTER_USE_KEY' => '2',
        'ARRAY_FILTER_USE_BOTH' => '1',
        'STR_PAD_LEFT' => '0',
        'STR_PAD_RIGHT' => '1',
        'STR_PAD_BOTH' => '2',
        'JSON_THROW_ON_ERROR' => '0',
        'JSON_UNESCAPED_SLASHES' => '0',
        'JSON_UNESCAPED_UNICODE' => '0',
        'JSON_PRETTY_PRINT' => '0',
        'PREG_PATTERN_ORDER' => '1',
        'PREG_SET_ORDER' => '2',
        'PREG_OFFSET_CAPTURE' => '256',
        'PREG_UNMATCHED_AS_NULL' => '512',
        'PREG_SPLIT_NO_EMPTY' => '1',
        'PREG_SPLIT_DELIM_CAPTURE' => '2',
        'PREG_SPLIT_OFFSET_CAPTURE' => '4',
    ];

    /** @param list<string> $arguments */
    public static function call(string $name, array $arguments, string $where): string
    {
        $short = strtolower(ltrim($name, '\\'));
        if (str_starts_with($short, 'pam\\native\\')) {
            $native = substr(ltrim($name, '\\'), strlen('Pam\\Native\\'));
            if (isset(self::NATIVE[$native])) {
                return self::NATIVE[$native].'('.implode(', ', $arguments).')';
            }
            throw new RuntimeException("{$where}: {$native}() is not available in web builds yet.");
        }
        if (isset(self::NATIVE[$name])) {
            return self::NATIVE[$name].'('.implode(', ', $arguments).')';
        }
        if (in_array($short, self::FUNCTIONS, true)) {
            return "PAM.f.{$short}(".implode(', ', $arguments).')';
        }

        throw new RuntimeException("{$where}: {$name}() is not available in web builds.");
    }
}
