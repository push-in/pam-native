<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

use Pam\Native\BuildConfiguration;
use Pam\Native\BuildMode;
use Pam\Native\Component;
use ReflectionClass;
use ReflectionFunction;
use ReflectionMethod;
use Throwable;
use UnitEnum;

use function count;
use function in_array;
use function is_array;
use function is_object;
use function is_string;
use function strtolower;

/**
 * @internal Which template event handlers can skip the re-render their
 * component would otherwise get (React semantics: no state change, no
 * render), decided without any opt-in.
 *
 * A handler method is inferred pure by static analysis of its body and of the
 * same-class methods it calls, recursively, when it reads no global or static
 * state (no `static::`, `self::$`, `static $`, `global`, `$GLOBALS`), creates
 * no objects, calls no user functions, static methods or object methods other
 * than its own (except a small allowlist of pure framework and PHP APIs) and
 * so cannot reach services, stores, the event bus, preferences or files. At
 * runtime it also needs a component whose own state holds no objects, so the
 * state snapshot comparison is exact. `#[PureHandler]` still forces purity.
 *
 * In development builds every render an inferred handler skipped is rendered
 * anyway and checked: if the tree changed, a PamNativePurity warning names
 * the handler and it is treated as impure for the rest of the session.
 */
final class HandlerPurity
{
    /** @var array<string, bool> "Class::method" => inferred pure */
    private static array $methods = [];

    /** @var array<string, true> handlers a development check found impure */
    private static array $impure = [];

    /** @var array<string, array<string, string>> file => lowercase alias => class */
    private static array $imports = [];

    /** @var list<array{0: string, 1: string}> skipped renders to verify: [key, location] */
    private static array $pending = [];

    /** Framework APIs without side effects that handlers may call statically. */
    private const PURE_CLASSES = [
        'pam\\native\\support\\format' => true,
        'pam\\native\\support\\text' => true,
    ];

    /** PHP functions (beyond the prefixes below) without effects on the app's state. */
    private const PURE_FUNCTIONS = [
        'count', 'sizeof', 'in_array', 'implode', 'explode', 'join', 'sprintf', 'vsprintf', 'printf',
        'number_format', 'abs', 'min', 'max', 'round', 'floor', 'ceil', 'intdiv', 'fmod', 'sqrt', 'pow',
        'exp', 'log', 'log10', 'pi', 'ucfirst', 'lcfirst', 'ucwords', 'trim', 'ltrim', 'rtrim', 'nl2br',
        'htmlspecialchars', 'substr', 'substr_count', 'sort', 'rsort', 'usort', 'uasort', 'uksort', 'ksort',
        'krsort', 'asort', 'arsort', 'natsort', 'natcasesort', 'shuffle', 'range', 'intval', 'floatval',
        'boolval', 'strval', 'gettype', 'get_debug_type', 'key', 'current', 'reset', 'end', 'next', 'prev',
        'compact', 'error_log', 'time', 'microtime', 'hrtime', 'random_int', 'mt_rand', 'rand', 'lcg_value',
        'chr', 'ord', 'dechex', 'hexdec', 'bin2hex', 'base64_encode', 'base64_decode', 'md5', 'sha1', 'crc32',
        'hash', 'urlencode', 'rawurlencode', 'http_build_query', 'nl_langinfo', 'wordwrap', 'levenshtein',
        'similar_text', 'soundex', 'metaphone', 'ctype_digit', 'checkdate', 'date', 'gmdate', 'mktime',
        'strtotime', 'iterator_to_array', 'iterator_count', 'filter_var', 'var_export', 'serialize',
        'unserialize', 'spl_object_id', 'spl_object_hash', 'method_exists', 'property_exists',
        'function_exists', 'class_exists', 'enum_exists', 'get_class', 'array', 'list', 'isset', 'empty', 'unset',
    ];

    private const PURE_PREFIXES = ['str', 'mb_', 'array_', 'preg_', 'is_', 'ctype_', 'json_', 'iconv', 'grapheme_'];

    /** Internal functions matching a prefix above that still run arbitrary callbacks by reference. */
    private const IMPURE_FUNCTIONS = [
        'array_walk' => false, 'array_walk_recursive' => false,
    ];

    private function __construct()
    {
    }

    /** Whether a handler method may skip its component's render when the state is unchanged. */
    public static function inferred(ReflectionMethod $method): bool
    {
        $key = $method->class.'::'.$method->name;
        if (isset(self::$impure[$key])) {
            return false;
        }
        if (!isset(self::$methods[$key])) {
            $visiting = [];
            try {
                self::$methods[$key] = self::analyze($method, $visiting);
            } catch (Throwable) {
                self::$methods[$key] = false;
            }
        }

        return self::$methods[$key];
    }

    /** The component's own state holds only scalars, arrays of them and enums. */
    public static function plainState(Component $component): bool
    {
        return $component->__pamPlainState();
    }

    /**
     * A render an inferred handler skipped. Development builds render anyway
     * and verify(): true when the caller must still mark the component dirty.
     */
    public static function skipped(ReflectionMethod $method): bool
    {
        if (BuildConfiguration::mode() !== BuildMode::Development) {
            return false;
        }
        self::$pending[] = [$method->class.'::'.$method->name, (string) $method->getFileName().':'.$method->getStartLine()];

        return true;
    }

    /** After the render that followed skipped handlers: whether it changed the tree. */
    public static function verify(bool $changed): void
    {
        if (self::$pending === []) {
            return;
        }
        $pending = self::$pending;
        self::$pending = [];
        if (!$changed) {
            return;
        }
        foreach ($pending as [$key, $location]) {
            self::$impure[$key] = true;
            $line = "PamNativePurity: handler {$key} was inferred pure but its render changed the tree; "
                .'it is treated as impure for the rest of this session. Report this as a framework bug.';
            DevWarnings::warn($line, $location);
            error_log("Pam Native warning: {$line} ({$location}).");
        }
    }

    /** @internal Tests. */
    public static function reset(): void
    {
        self::$methods = [];
        self::$impure = [];
        self::$pending = [];
    }

    /** @param array<string, true> $visiting */
    private static function analyze(ReflectionMethod $method, array &$visiting): bool
    {
        $key = $method->class.'::'.$method->name;
        if (isset($visiting[$key])) {
            return true;
        }
        $visiting[$key] = true;
        $class = $method->getDeclaringClass();
        if ($method->isStatic() || $method->isAbstract() || self::isFramework($class->getName())) {
            return false;
        }
        $file = $method->getFileName();
        $start = $method->getStartLine();
        $end = $method->getEndLine();
        if (!is_string($file) || $start === false || $end === false) {
            return false;
        }
        $lines = @file($file);
        if (!is_array($lines)) {
            return false;
        }
        $source = implode('', array_slice($lines, $start - 1, $end - $start + 1));
        $tokens = array_values(array_filter(
            token_get_all('<?php '.$source),
            static fn (mixed $token): bool => !is_array($token)
                || !in_array($token[0], [T_WHITESPACE, T_COMMENT, T_DOC_COMMENT, T_OPEN_TAG], true),
        ));
        $imports = self::imports($file);
        $namespace = $class->getNamespaceName();
        $count = count($tokens);
        // Skip the signature: analysis starts at the body's opening brace.
        $index = 0;
        while ($index < $count && $tokens[$index] !== '{') {
            ++$index;
        }
        for (; $index < $count; $index++) {
            $token = $tokens[$index];
            $id = is_array($token) ? $token[0] : null;
            $next = $tokens[$index + 1] ?? null;
            $previous = $tokens[$index - 1] ?? null;
            if (
                $id === T_GLOBAL || $id === T_EVAL || $id === T_INCLUDE || $id === T_INCLUDE_ONCE
                || $id === T_REQUIRE || $id === T_REQUIRE_ONCE || $id === T_EXIT || $id === T_NEW
                || $token === '`' || $id === T_HALT_COMPILER
            ) {
                return false;
            }
            if ($id === T_VARIABLE && $token[1] === '$GLOBALS') {
                return false;
            }
            if ($id === T_STATIC) {
                // `static fn`/`static function` closures are fine; static::,
                // static $x are not.
                $after = is_array($next) ? $next[0] : null;
                if ($after !== T_FN && $after !== T_FUNCTION) {
                    return false;
                }
                continue;
            }
            if ($id === T_DOUBLE_COLON) {
                $target = is_array($previous) ? strtolower($previous[1]) : '';
                if (is_array($next) && $next[0] === T_VARIABLE) {
                    return false; // static property (self::$x, Foo::$x)
                }
                if (in_array($target, ['self', 'static', 'parent'], true)) {
                    $call = ($tokens[$index + 2] ?? null) === '(';
                    if ($call) {
                        return false;
                    }
                    continue; // class constant
                }
                if (($tokens[$index + 2] ?? null) !== '(') {
                    continue; // constant or enum case
                }
                $resolved = self::resolveClass(is_array($previous) ? $previous[1] : '', $imports, $namespace);
                $member = is_array($next) ? strtolower($next[1]) : '';
                if (isset(self::PURE_CLASSES[strtolower($resolved)])) {
                    continue;
                }
                if (
                    enum_exists($resolved)
                    && in_array($member, ['from', 'tryfrom', 'cases'], true)
                ) {
                    continue;
                }

                return false;
            }
            if (($id === T_OBJECT_OPERATOR || $id === T_NULLSAFE_OBJECT_OPERATOR) && ($tokens[$index + 2] ?? null) === '(') {
                // A method call: only $this->own() (analyzed recursively).
                if (!(is_array($previous) && $previous[0] === T_VARIABLE && $previous[1] === '$this')) {
                    return false;
                }
                $name = is_array($next) ? $next[1] : '';
                if (!$class->hasMethod($name)) {
                    return false;
                }
                if (!self::analyze($class->getMethod($name), $visiting)) {
                    return false;
                }
                continue;
            }
            if (
                ($id === T_STRING || $id === T_NAME_FULLY_QUALIFIED || $id === T_NAME_QUALIFIED)
                && $next === '('
            ) {
                $before = is_array($previous) ? $previous[0] : $previous;
                if (in_array($before, [T_OBJECT_OPERATOR, T_NULLSAFE_OBJECT_OPERATOR, T_DOUBLE_COLON, T_FUNCTION, T_NEW], true)) {
                    continue;
                }
                if (!self::pureFunction($token[1], $namespace)) {
                    return false;
                }
                continue;
            }
            if ($id === T_VARIABLE && $next === '(') {
                return false; // $callable(...)
            }
            if ($token === '[' && is_array($next) && $next[0] === T_VARIABLE && $next[1] === '$this'
                && ($tokens[$index + 2] ?? null) === ',') {
                return false; // [$this, 'method'] callable: not analyzed
            }
            if ($token === ')' && $next === '(') {
                return false; // (expr)(...)
            }
        }

        return true;
    }

    private static function isFramework(string $class): bool
    {
        return str_starts_with($class, 'Pam\\Native\\');
    }

    private static function pureFunction(string $name, string $namespace): bool
    {
        $global = ltrim($name, '\\');
        if (!str_contains($global, '\\') && $namespace !== '' && function_exists($namespace.'\\'.$global)) {
            return false; // a namespaced user function shadows the global one
        }
        $lower = strtolower($global);
        if (str_contains($lower, '\\') || !function_exists($lower)) {
            return in_array($lower, ['array', 'list', 'isset', 'empty', 'unset'], true);
        }
        if (!(new ReflectionFunction($lower))->isInternal()) {
            return false;
        }
        if (isset(self::IMPURE_FUNCTIONS[$lower])) {
            return self::IMPURE_FUNCTIONS[$lower];
        }
        if (in_array($lower, self::PURE_FUNCTIONS, true)) {
            return true;
        }
        foreach (self::PURE_PREFIXES as $prefix) {
            if (str_starts_with($lower, $prefix)) {
                return true;
            }
        }

        return false;
    }

    /** @param array<string, string> $imports */
    private static function resolveClass(string $name, array $imports, string $namespace): string
    {
        if (str_starts_with($name, '\\')) {
            return ltrim($name, '\\');
        }
        $parts = explode('\\', $name, 2);
        $alias = strtolower($parts[0]);
        if (isset($imports[$alias])) {
            return $imports[$alias].(isset($parts[1]) ? '\\'.$parts[1] : '');
        }

        return $namespace === '' ? $name : $namespace.'\\'.$name;
    }

    /** @return array<string, string> lowercase alias => class, from the file's top-level `use` statements */
    private static function imports(string $file): array
    {
        if (isset(self::$imports[$file])) {
            return self::$imports[$file];
        }
        $imports = [];
        $source = @file_get_contents($file);
        if (is_string($source)) {
            $tokens = token_get_all($source);
            $depth = 0;
            $count = count($tokens);
            for ($i = 0; $i < $count; $i++) {
                $token = $tokens[$i];
                if ($token === '{') {
                    ++$depth;
                } elseif ($token === '}') {
                    --$depth;
                } elseif ($depth === 0 && is_array($token) && $token[0] === T_USE) {
                    $name = '';
                    $alias = null;
                    for ($i++; $i < $count && $tokens[$i] !== ';'; $i++) {
                        $part = $tokens[$i];
                        if (!is_array($part)) {
                            if ($part === ',') {
                                if ($name !== '') {
                                    $imports[strtolower($alias ?? substr(strrchr('\\'.$name, '\\'), 1))] = ltrim($name, '\\');
                                }
                                $name = '';
                                $alias = null;
                            }
                            continue;
                        }
                        if ($part[0] === T_AS) {
                            $alias = '';
                        } elseif ($alias === '' && $part[0] === T_STRING) {
                            $alias = $part[1];
                        } elseif (in_array($part[0], [T_STRING, T_NAME_QUALIFIED, T_NAME_FULLY_QUALIFIED], true)) {
                            $name .= $part[1];
                        }
                    }
                    if ($name !== '') {
                        $imports[strtolower($alias ?? substr(strrchr('\\'.$name, '\\'), 1))] = ltrim($name, '\\');
                    }
                }
            }
        }

        return self::$imports[$file] = $imports;
    }

    /** @internal */
    public static function plainValue(mixed $value, int $depth = 0): bool
    {
        if (is_object($value)) {
            return $value instanceof UnitEnum;
        }
        if (!is_array($value)) {
            return true;
        }
        if ($depth > 32) {
            return false;
        }
        foreach ($value as $item) {
            if ((is_array($item) || is_object($item)) && !self::plainValue($item, $depth + 1)) {
                return false;
            }
        }

        return true;
    }
}
