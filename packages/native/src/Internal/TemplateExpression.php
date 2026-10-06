<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

use Closure;
use ReflectionMethod;
use ReflectionProperty;
use RuntimeException;
use Stringable;

use function array_key_exists;
use function count;
use function in_array;
use function is_array;
use function is_bool;
use function is_float;
use function is_int;
use function is_object;
use function is_string;
use function property_exists;

/**
 * Template expressions are compiled once per source string into a closure
 * tree and evaluated against the render scope/data afterwards. The grammar and
 * evaluation order (eager operands, coalescing "missing" semantics) match the
 * original token-walking interpreter exactly; only the parse is hoisted out of
 * the render loop.
 */
final class TemplateExpression
{
    private const CACHE_LIMIT = 8192;

    /** @var array<string, Closure(array<string, mixed>, ?object): mixed> */
    private static array $compiled = [];

    /** @var array<string, string|list<string|array{0: string}>> */
    private static array $interpolations = [];

    /** @var array<string, ReflectionProperty> */
    private static array $properties = [];

    /** @var array<string, array{public: bool, gestures: list<int>}> */
    private static array $methods = [];

    /** @var array<string, string|false> */
    private static array $enumClasses = [];

    private static ?object $missingSentinel = null;

    private static bool $generated = true;

    /** @var array<string, Closure|false> */
    private static array $classLists = [];

    /** @var list<array{type: int|string, text: string}> */
    private array $tokens;
    private int $position = 0;
    private int $coalescingDepth = 0;
    private readonly object $missing;

    private function __construct(string $expression)
    {
        $this->tokens = self::tokenize($expression);
        $this->missing = self::missing();
    }

    /** @param array<string, mixed> $data */
    public static function evaluate(
        string $expression,
        ?object $scope,
        array $data,
    ): mixed {
        $compiled = self::$compiled[$expression] ?? self::compile($expression);

        return $compiled($data, $scope);
    }

    /** @param array<string, mixed> $data */
    public static function interpolate(
        string $value,
        ?object $scope,
        array $data,
    ): string {
        $parts = self::$interpolations[$value] ?? null;
        if ($parts === null) {
            $parts = self::interpolationParts($value);
            if (count(self::$interpolations) >= self::CACHE_LIMIT) {
                self::$interpolations = [];
            }
            self::$interpolations[$value] = $parts;
        }
        if (is_string($parts)) {
            return $parts;
        }
        $result = '';
        foreach ($parts as $part) {
            if (is_string($part)) {
                $result .= $part;
                continue;
            }
            $resolved = self::evaluate($part[0], $scope, $data);
            if (
                !is_string($resolved)
                && !is_int($resolved)
                && !is_float($resolved)
                && !is_bool($resolved)
                && !$resolved instanceof Stringable
            ) {
                throw new RuntimeException(
                    "Template expression {$part[0]} is not printable.",
                );
            }
            $result .= (string) $resolved;
        }

        return $result;
    }

    /** @return string|list<string|array{0: string}> */
    private static function interpolationParts(string $value): string|array
    {
        if (
            !str_contains($value, '{{')
            || preg_match_all(
                '/\{\{\s*(.*?)\s*\}\}/s',
                $value,
                $matches,
                PREG_SET_ORDER | PREG_OFFSET_CAPTURE,
            ) < 1
        ) {
            return $value;
        }
        $parts = [];
        $offset = 0;
        foreach ($matches as $match) {
            [$whole, $start] = $match[0];
            if ($start > $offset) {
                $parts[] = substr($value, $offset, $start - $offset);
            }
            $parts[] = [$match[1][0]];
            $offset = $start + strlen($whole);
        }
        if ($offset < strlen($value)) {
            $parts[] = substr($value, $offset);
        }

        return $parts;
    }

    /**
     * @internal Registers closures compiled ahead of time (component cache)
     * for their expression sources.
     *
     * @param array<string, Closure(array<string, mixed>, ?object): mixed> $closures
     */
    public static function preload(array $closures, array $classLists = []): void
    {
        if (!self::$generated) {
            return;
        }
        if (count(self::$compiled) + count($closures) >= self::CACHE_LIMIT) {
            self::$compiled = [];
        }
        self::$compiled += $closures;
        if (count(self::$classLists) + count($classLists) >= self::CACHE_LIMIT) {
            self::$classLists = [];
        }
        self::$classLists += $classLists;
    }

    /**
     * @internal Enabled class names of a `:class` array literal, in order
     * (classValue() semantics), or null when the expression is not one.
     *
     * @param array<string, mixed> $data
     * @return list<string>|null
     */
    public static function classList(string $expression, ?object $scope, array $data): ?array
    {
        if (!self::$generated) {
            return null;
        }
        $compiled = self::$classLists[$expression] ?? null;
        if ($compiled === null) {
            $source = TemplateExpressionCompiler::classListSource($expression);
            $compiled = false;
            if ($source !== null) {
                try {
                    $compiled = eval('declare(strict_types=1); return '.$source.';');
                } catch (\ParseError) {
                    $compiled = false;
                }
            }
            if (count(self::$classLists) >= self::CACHE_LIMIT) {
                self::$classLists = [];
            }
            self::$classLists[$expression] = $compiled;
        }

        return $compiled === false ? null : $compiled($data, $scope);
    }

    /** @internal Disables generated closures (tests compare both forms). */
    public static function useGeneratedCode(bool $enabled): void
    {
        self::$generated = $enabled;
        self::$compiled = [];
        self::$classLists = [];
    }

    /** @return Closure(array<string, mixed>, ?object): mixed */
    private static function compile(string $expression): Closure
    {
        if (self::$generated) {
            $closure = null;
            try {
                $closure = eval('declare(strict_types=1); return '.TemplateExpressionCompiler::closureSource($expression).';');
            } catch (RuntimeException|\ParseError) {
                // Invalid expressions report through the reference parser.
            }
            if ($closure instanceof Closure) {
                if (count(self::$compiled) >= self::CACHE_LIMIT) {
                    self::$compiled = [];
                }

                return self::$compiled[$expression] = $closure;
            }
        }

        return self::compileTree($expression);
    }

    /** @return Closure(array<string, mixed>, ?object): mixed */
    private static function compileTree(string $expression): Closure
    {
        $parser = new self($expression);
        $compiled = $parser->ternary();

        if ($parser->peek() !== null) {
            throw new RuntimeException(
                "Unexpected token {$parser->peek()['text']} in template expression.",
            );
        }
        if (count(self::$compiled) >= self::CACHE_LIMIT) {
            self::$compiled = [];
        }

        return self::$compiled[$expression] = $compiled;
    }

    /**
     * @internal
     * @return list<array{type: int|string, text: string}>
     */
    public static function __pamTokens(string $expression): array
    {
        return self::tokenize($expression);
    }

    /** @internal */
    public static function __pamStringLiteral(string $literal): string
    {
        return self::stringLiteral($literal);
    }

    /** @internal */
    public static function __pamMissing(): object
    {
        return self::$missingSentinel ??= new \stdClass();
    }

    /** @internal */
    public static function __pamProperty(object $target, string $name): ReflectionProperty
    {
        return self::property($target, $name);
    }

    /** @internal */
    public static function __pamString(mixed $value): string
    {
        return self::stringOperand($value);
    }

    /** @internal */
    public static function __pamNumeric(mixed $value, string $operator): void
    {
        self::requireNumeric($value, $operator);
    }

    /**
     * @internal
     * @param list<mixed> $arguments
     */
    public static function __pamInvoke(string $name, string $builtIn, array $arguments, ?object $scope): mixed
    {
        return self::invoke($name, $builtIn, $arguments, $scope);
    }

    /** @internal A variable missing from the render data: component property or missing. */
    public static function __pamVariable(?object $scope, string $name, bool $lenient): mixed
    {
        if ($scope !== null && property_exists($scope, $name)) {
            $property = self::property($scope, $name);
            if (!$property->isInitialized($scope)) {
                throw new RuntimeException("Template property \${$name} is not initialized.");
            }

            return $property->getValue($scope);
        }
        if ($lenient) {
            return self::missing();
        }

        throw new RuntimeException("Template expression \${$name} is undefined.");
    }

    /** @internal Property step that is not a plain array key. */
    public static function __pamPropertyStep(mixed $value, string $step, bool $lenient): mixed
    {
        $missing = self::missing();

        return match (true) {
            $value === $missing => $missing,
            is_array($value) && array_key_exists($step, $value) => $value[$step],
            is_object($value) && property_exists($value, $step) =>
                self::property($value, $step)->getValue($value),
            $lenient => $missing,
            default => throw new RuntimeException(
                "Cannot resolve template property {$step}.",
            ),
        };
    }

    /** @internal Index step that is not a present array key. */
    public static function __pamIndex(mixed $value, mixed $index, bool $lenient): mixed
    {
        $missing = self::missing();
        if ($value === $missing) {
            return $missing;
        }
        if (
            (!is_string($index) && !is_int($index))
            || !is_array($value)
            || !array_key_exists($index, $value)
        ) {
            if ($lenient && (is_string($index) || is_int($index))) {
                return $missing;
            }
            throw new RuntimeException('Cannot resolve template array index.');
        }

        return $value[$index];
    }

    /** @internal */
    public static function __pamEnumCase(string $name, string $caseName, ?object $scope): mixed
    {
        $class = self::resolveScopedClassName($name, $scope);
        if ($class === null || !enum_exists($class)) {
            throw new RuntimeException("Template enum {$name} does not exist.");
        }
        $constant = $class.'::'.$caseName;
        if (!defined($constant)) {
            throw new RuntimeException("Template enum case {$constant} does not exist.");
        }

        return constant($constant);
    }

    private static function missing(): object
    {
        return self::$missingSentinel ??= new \stdClass();
    }

    private static function constant(mixed $value): Closure
    {
        return static fn (array $data, ?object $scope): mixed => $value;
    }

    private function ternary(): Closure
    {
        $condition = $this->coalescing();

        if (!$this->take('?')) {
            return $condition;
        }
        $truthy = null;
        if (!$this->take(':')) {
            $truthy = $this->ternary();
            $this->expect(':');
        }
        $falsy = $this->ternary();

        if ($truthy === null) {
            return static function (array $data, ?object $scope) use ($condition, $falsy): mixed {
                $value = $condition($data, $scope);
                $otherwise = $falsy($data, $scope);

                return (bool) $value ? $value : $otherwise;
            };
        }

        return static function (array $data, ?object $scope) use ($condition, $truthy, $falsy): mixed {
            $value = $condition($data, $scope);
            $whenTrue = $truthy($data, $scope);
            $whenFalse = $falsy($data, $scope);

            return (bool) $value ? $whenTrue : $whenFalse;
        };
    }

    private function coalescing(): Closure
    {
        $this->coalescingDepth++;
        try {
            $left = $this->logicalOr();
        } finally {
            $this->coalescingDepth--;
        }
        $missing = $this->missing;

        if ($this->take(T_COALESCE)) {
            $right = $this->coalescing();

            return static function (array $data, ?object $scope) use ($left, $right, $missing): mixed {
                $value = $left($data, $scope);
                $fallback = $right($data, $scope);

                return $value === $missing || $value === null ? $fallback : $value;
            };
        }
        if ($this->coalescingDepth === 0) {
            return static function (array $data, ?object $scope) use ($left, $missing): mixed {
                $value = $left($data, $scope);
                if ($value === $missing) {
                    throw new RuntimeException('Cannot resolve template value.');
                }

                return $value;
            };
        }

        return $left;
    }

    private function logicalOr(): Closure
    {
        $first = $this->logicalAnd();
        $rest = [];

        while ($this->take(T_BOOLEAN_OR) || $this->take('||')) {
            $rest[] = $this->logicalAnd();
        }
        if ($rest === []) {
            return $first;
        }

        return static function (array $data, ?object $scope) use ($first, $rest): bool {
            $value = $first($data, $scope);
            foreach ($rest as $operand) {
                $right = $operand($data, $scope);
                $value = (bool) $value || (bool) $right;
            }

            return $value;
        };
    }

    private function logicalAnd(): Closure
    {
        $first = $this->equality();
        $rest = [];

        while ($this->take(T_BOOLEAN_AND) || $this->take('&&')) {
            $rest[] = $this->equality();
        }
        if ($rest === []) {
            return $first;
        }

        return static function (array $data, ?object $scope) use ($first, $rest): bool {
            $value = $first($data, $scope);
            foreach ($rest as $operand) {
                $right = $operand($data, $scope);
                $value = (bool) $value && (bool) $right;
            }

            return $value;
        };
    }

    private function equality(): Closure
    {
        $first = $this->comparison();
        $rest = [];

        while (true) {
            $operator = $this->takeOne([
                T_IS_IDENTICAL,
                T_IS_NOT_IDENTICAL,
                T_IS_EQUAL,
                T_IS_NOT_EQUAL,
            ]);
            if ($operator === null) {
                break;
            }
            $rest[] = [$operator, $this->comparison()];
        }
        if ($rest === []) {
            return $first;
        }

        return static function (array $data, ?object $scope) use ($first, $rest): bool {
            $value = $first($data, $scope);
            foreach ($rest as [$operator, $operand]) {
                $right = $operand($data, $scope);
                $value = match ($operator) {
                    T_IS_IDENTICAL => $value === $right,
                    T_IS_NOT_IDENTICAL => $value !== $right,
                    T_IS_EQUAL => $value == $right,
                    T_IS_NOT_EQUAL => $value != $right,
                    default => throw new RuntimeException(
                        'Unsupported equality operator.',
                    ),
                };
            }

            return $value;
        };
    }

    private function comparison(): Closure
    {
        $first = $this->concatenation();
        $rest = [];

        while (true) {
            $operator = $this->takeOne([
                T_IS_GREATER_OR_EQUAL,
                T_IS_SMALLER_OR_EQUAL,
                '>',
                '<',
            ]);
            if ($operator === null) {
                break;
            }
            $rest[] = [$operator, $this->concatenation()];
        }
        if ($rest === []) {
            return $first;
        }

        return static function (array $data, ?object $scope) use ($first, $rest): bool {
            $value = $first($data, $scope);
            foreach ($rest as [$operator, $operand]) {
                $right = $operand($data, $scope);
                $value = match ($operator) {
                    T_IS_GREATER_OR_EQUAL => $value >= $right,
                    T_IS_SMALLER_OR_EQUAL => $value <= $right,
                    '>' => $value > $right,
                    '<' => $value < $right,
                    default => throw new RuntimeException(
                        'Unsupported comparison operator.',
                    ),
                };
            }

            return $value;
        };
    }

    private function concatenation(): Closure
    {
        $first = $this->additive();
        $rest = [];

        while ($this->take('.')) {
            $rest[] = $this->additive();
        }
        if ($rest === []) {
            return $first;
        }

        return static function (array $data, ?object $scope) use ($first, $rest): string {
            $value = $first($data, $scope);
            foreach ($rest as $operand) {
                $right = $operand($data, $scope);
                $value = self::stringOperand($value).self::stringOperand($right);
            }

            return $value;
        };
    }

    private function additive(): Closure
    {
        $first = $this->multiplicative();
        $rest = [];

        while (($operator = $this->takeOne(['+', '-'])) !== null) {
            $rest[] = [$operator, $this->multiplicative()];
        }
        if ($rest === []) {
            return $first;
        }

        return static function (array $data, ?object $scope) use ($first, $rest): int|float {
            $value = $first($data, $scope);
            foreach ($rest as [$operator, $operand]) {
                $right = $operand($data, $scope);
                self::requireNumeric($value, $operator);
                self::requireNumeric($right, $operator);
                $value = $operator === '+' ? $value + $right : $value - $right;
            }

            return $value;
        };
    }

    private function multiplicative(): Closure
    {
        $first = $this->unary();
        $rest = [];

        while (($operator = $this->takeOne(['*', '/', '%'])) !== null) {
            $rest[] = [$operator, $this->unary()];
        }
        if ($rest === []) {
            return $first;
        }

        return static function (array $data, ?object $scope) use ($first, $rest): int|float {
            $value = $first($data, $scope);
            foreach ($rest as [$operator, $operand]) {
                $right = $operand($data, $scope);
                self::requireNumeric($value, $operator);
                self::requireNumeric($right, $operator);
                if (($operator === '/' || $operator === '%') && $right == 0) {
                    throw new RuntimeException('Division by zero in template expression.');
                }
                if ($operator === '%' && (!is_int($value) || !is_int($right))) {
                    throw new RuntimeException(
                        'Template modulo requires integer operands.',
                    );
                }
                $value = match ($operator) {
                    '*' => $value * $right,
                    '/' => $value / $right,
                    '%' => $value % $right,
                };
            }

            return $value;
        };
    }

    private function unary(): Closure
    {
        if ($this->take('!')) {
            $operand = $this->unary();

            return static fn (array $data, ?object $scope): bool => !$operand($data, $scope);
        }
        if ($this->take('-')) {
            $operand = $this->unary();

            return static function (array $data, ?object $scope) use ($operand): int|float {
                $value = $operand($data, $scope);
                if (!is_int($value) && !is_float($value)) {
                    throw new RuntimeException('Unary minus requires a numeric expression.');
                }

                return -$value;
            };
        }

        return $this->primary();
    }

    private function primary(): Closure
    {
        $token = $this->peek();

        if ($token === null) {
            throw new RuntimeException('Template expression ended unexpectedly.');
        }
        if ($this->take('(')) {
            $value = $this->ternary();
            $this->expect(')');

            return $value;
        }
        if ($this->take('[')) {
            return $this->array();
        }
        if ($token['type'] === T_VARIABLE) {
            $this->position++;

            return $this->variable(substr($token['text'], 1));
        }
        if ($token['type'] === T_LNUMBER) {
            $this->position++;

            return self::constant((int) str_replace('_', '', $token['text']));
        }
        if ($token['type'] === T_DNUMBER) {
            $this->position++;

            return self::constant((float) str_replace('_', '', $token['text']));
        }
        if ($token['type'] === T_CONSTANT_ENCAPSED_STRING) {
            $this->position++;

            return self::constant(self::stringLiteral($token['text']));
        }
        if ($token['type'] === T_STRING) {
            $this->position++;
            $name = $token['text'];
            $lower = strtolower($name);
            if ($lower === 'true') {
                return self::constant(true);
            }
            if ($lower === 'false') {
                return self::constant(false);
            }
            if ($lower === 'null') {
                return self::constant(null);
            }
            if ($this->take('(')) {
                return $this->call($name, $this->arguments());
            }
            if ($this->take(T_DOUBLE_COLON)) {
                return $this->staticEnumCase($name);
            }
        }

        throw new RuntimeException(
            "Unsupported token {$token['text']} in template expression.",
        );
    }

    private function array(): Closure
    {
        /** @var list<array{0: Closure, 1: Closure|null}> $entries */
        $entries = [];

        if (!$this->take(']')) {
            while (true) {
                $first = $this->ternary();
                $entries[] = $this->take(T_DOUBLE_ARROW)
                    ? [$first, $this->ternary()]
                    : [$first, null];
                if ($this->take(']')) {
                    break;
                }
                $this->expect(',');
                if ($this->take(']')) {
                    break;
                }
            }
        }

        return static function (array $data, ?object $scope) use ($entries): array {
            $values = [];
            foreach ($entries as [$first, $second]) {
                $key = $first($data, $scope);
                if ($second === null) {
                    $values[] = $key;
                    continue;
                }
                if (!is_string($key) && !is_int($key)) {
                    throw new RuntimeException(
                        'Template array keys must be strings or integers.',
                    );
                }
                $values[$key] = $second($data, $scope);
            }

            return $values;
        };
    }

    /** @return list<Closure> */
    private function arguments(): array
    {
        $arguments = [];

        if ($this->take(')')) {
            return $arguments;
        }
        while (true) {
            $arguments[] = $this->ternary();
            if ($this->take(')')) {
                return $arguments;
            }
            $this->expect(',');
        }
    }

    private function variable(string $name): Closure
    {
        $lenient = $this->coalescingDepth > 0;
        $missing = $this->missing;
        $postfix = $this->postfix();

        return static function (array $data, ?object $scope) use ($name, $lenient, $missing, $postfix): mixed {
            if (array_key_exists($name, $data)) {
                $value = $data[$name];
            } elseif ($scope !== null && property_exists($scope, $name)) {
                $property = self::property($scope, $name);
                if (!$property->isInitialized($scope)) {
                    throw new RuntimeException("Template property \${$name} is not initialized.");
                }
                $value = $property->getValue($scope);
            } elseif ($lenient) {
                $value = $missing;
            } else {
                throw new RuntimeException("Template expression \${$name} is undefined.");
            }

            return $postfix === null ? $value : $postfix($value, $data, $scope);
        };
    }

    /** @return (Closure(mixed, array<string, mixed>, ?object): mixed)|null */
    private function postfix(): ?Closure
    {
        $lenient = $this->coalescingDepth > 0;
        $missing = $this->missing;
        /** @var list<array{0: bool, 1: string|Closure}> $steps */
        $steps = [];

        while (true) {
            if ($this->take(T_OBJECT_OPERATOR) || $this->takePropertyDot()) {
                $segment = $this->peek();
                if ($segment === null || $segment['type'] !== T_STRING) {
                    throw new RuntimeException('Template property path is invalid.');
                }
                $this->position++;
                $steps[] = [true, $segment['text']];
                continue;
            }
            if ($this->take('[')) {
                $index = $this->ternary();
                $this->expect(']');
                $steps[] = [false, $index];
                continue;
            }
            break;
        }
        if ($steps === []) {
            return null;
        }

        return static function (mixed $value, array $data, ?object $scope) use ($steps, $lenient, $missing): mixed {
            foreach ($steps as [$isProperty, $step]) {
                if ($isProperty) {
                    $value = match (true) {
                        $value === $missing => $missing,
                        is_array($value) && array_key_exists($step, $value) => $value[$step],
                        is_object($value) && property_exists($value, $step) =>
                            self::property($value, $step)->getValue($value),
                        $lenient => $missing,
                        default => throw new RuntimeException(
                            "Cannot resolve template property {$step}.",
                        ),
                    };
                    continue;
                }
                $index = $step($data, $scope);
                if ($value === $missing) {
                    continue;
                }
                if (
                    (!is_string($index) && !is_int($index))
                    || !is_array($value)
                    || !array_key_exists($index, $value)
                ) {
                    if ($lenient && (is_string($index) || is_int($index))) {
                        $value = $missing;

                        continue;
                    }
                    throw new RuntimeException('Cannot resolve template array index.');
                }
                $value = $value[$index];
            }

            return $value;
        };
    }

    private static function property(object $target, string $name): ReflectionProperty
    {
        $key = $target::class.'::'.$name;
        $property = self::$properties[$key] ?? null;
        if ($property !== null) {
            return $property;
        }
        $property = new ReflectionProperty($target, $name);
        if ($property->isDefault()) {
            self::$properties[$key] = $property;
        }

        return $property;
    }

    private function staticEnumCase(string $name): Closure
    {
        $case = $this->peek();
        if ($case === null || $case['type'] !== T_STRING) {
            throw new RuntimeException('Template enum case is invalid.');
        }
        $this->position++;
        $caseName = $case['text'];
        $postfix = $this->postfix();

        return static function (array $data, ?object $scope) use ($name, $caseName, $postfix): mixed {
            $class = self::resolveScopedClassName($name, $scope);
            if ($class === null || !enum_exists($class)) {
                throw new RuntimeException("Template enum {$name} does not exist.");
            }
            $constant = $class.'::'.$caseName;
            if (!defined($constant)) {
                throw new RuntimeException("Template enum case {$constant} does not exist.");
            }
            $value = constant($constant);

            return $postfix === null ? $value : $postfix($value, $data, $scope);
        };
    }

    private static function resolveScopedClassName(string $name, ?object $scope): ?string
    {
        $key = ($scope === null ? '' : $scope::class).'|'.$name;
        $cached = self::$enumClasses[$key] ?? null;
        if ($cached !== null) {
            return $cached === false ? null : $cached;
        }
        $resolved = self::locateScopedClassName($name, $scope);
        self::$enumClasses[$key] = $resolved ?? false;

        return $resolved;
    }

    private static function locateScopedClassName(string $name, ?object $scope): ?string
    {
        if (enum_exists($name)) {
            return $name;
        }
        if ($scope === null) {
            return null;
        }
        $reflection = new \ReflectionClass($scope);
        $local = $reflection->getNamespaceName().'\\'.$name;
        if (enum_exists($local)) {
            return $local;
        }
        $file = $reflection->getFileName();
        if (!is_string($file) || !is_readable($file)) {
            return null;
        }
        $source = (string) file_get_contents($file);
        if (($close = strpos($source, '?>')) !== false) {
            $source = substr($source, 0, $close);
        }
        if (preg_match_all('/^use\s+([^;]+);/mi', $source, $matches) !== false) {
            foreach ($matches[1] as $import) {
                if (!is_string($import) || str_contains($import, '{')) {
                    continue;
                }
                $parts = preg_split('/\s+as\s+/i', trim($import));
                if (!is_array($parts) || !isset($parts[0])) {
                    continue;
                }
                $candidate = ltrim(trim($parts[0]), '\\');
                $alias = isset($parts[1]) ? trim($parts[1]) : substr($candidate, strrpos($candidate, '\\') + 1);
                if ($alias === $name && enum_exists($candidate)) {
                    return $candidate;
                }
            }
        }

        return null;
    }

    private function takePropertyDot(): bool
    {
        if (($this->tokens[$this->position]['type'] ?? null) !== '.') {
            return false;
        }
        if (($this->tokens[$this->position + 1]['type'] ?? null) !== T_STRING) {
            return false;
        }
        if (($this->tokens[$this->position + 2]['type'] ?? null) === '(') {
            return false;
        }
        $this->position++;

        return true;
    }

    /** @param list<Closure> $arguments */
    private function call(string $name, array $arguments): Closure
    {
        $builtIn = strtolower($name);

        return static function (array $data, ?object $scope) use ($name, $builtIn, $arguments): mixed {
            $values = [];
            foreach ($arguments as $argument) {
                $values[] = $argument($data, $scope);
            }

            return self::invoke($name, $builtIn, $values, $scope);
        };
    }

    /** @param list<mixed> $arguments */
    private static function invoke(string $name, string $builtIn, array $arguments, ?object $scope): mixed
    {
        if ($builtIn === 'count') {
            if (count($arguments) !== 1 || (!is_array($arguments[0]) && !$arguments[0] instanceof \Countable)) {
                throw new RuntimeException('Template count() expects exactly one countable value.');
            }

            return count($arguments[0]);
        }
        if ($builtIn === 'in_array') {
            if (
                (count($arguments) !== 2 && count($arguments) !== 3)
                || !is_array($arguments[1])
                || (isset($arguments[2]) && !is_bool($arguments[2]))
            ) {
                throw new RuntimeException(
                    'Template in_array() expects a needle, an array, and an optional strict boolean.',
                );
            }

            return in_array(
                $arguments[0],
                $arguments[1],
                $arguments[2] ?? false,
            );
        }
        if (isset(self::STRING_HELPERS[$builtIn])) {
            return self::invokeStringHelper($builtIn, $arguments);
        }
        if ($scope === null || !method_exists($scope, $name)) {
            throw new RuntimeException("Template method {$name} does not exist.");
        }
        $key = $scope::class.'::'.$name;
        $method = self::$methods[$key] ?? null;
        if ($method === null) {
            $reflection = new ReflectionMethod($scope, $name);
            $gestures = [];
            foreach ($reflection->getParameters() as $index => $parameter) {
                $class = EventPayloads::parameterClass($reflection, $index);
                if ($class !== null) {
                    $gestures[$index] = $class;
                }
            }
            $method = self::$methods[$key] = [
                'public' => $reflection->isPublic(),
                'gestures' => $gestures,
            ];
        }
        if (!$method['public']) {
            throw new RuntimeException("Template method {$name} must be public.");
        }
        foreach ($method['gestures'] as $index => $class) {
            if (array_key_exists($index, $arguments) && is_string($arguments[$index])) {
                $arguments[$index] = EventPayloads::decode($class, $arguments[$index]);
            }
        }

        return $scope->{$name}(...$arguments);
    }

    private const STRING_HELPERS = [
        'trim' => true,
        'ltrim' => true,
        'rtrim' => true,
        'strlen' => true,
        'mb_strlen' => true,
        'substr' => true,
        'mb_substr' => true,
        'strtolower' => true,
        'strtoupper' => true,
        'mb_strtolower' => true,
        'mb_strtoupper' => true,
    ];

    /** @param list<mixed> $arguments */
    private static function invokeStringHelper(string $name, array $arguments): mixed
    {
        return match ($name) {
            'trim', 'ltrim', 'rtrim' => self::trimValue($name, $arguments),
            'strlen', 'strtolower', 'strtoupper' =>
                self::singleStringValue($name, $arguments),
            'mb_strlen', 'mb_strtolower', 'mb_strtoupper' =>
                self::multibyteStringValue($name, $arguments),
            'substr' => self::substringValue($arguments),
            'mb_substr' => self::multibyteSubstringValue($arguments),
            default => throw new RuntimeException(
                "Template string helper {$name} is not supported.",
            ),
        };
    }

    /** @param list<mixed> $arguments */
    private static function trimValue(string $name, array $arguments): string
    {
        if (
            (count($arguments) !== 1 && count($arguments) !== 2)
            || !is_string($arguments[0] ?? null)
            || (isset($arguments[1]) && !is_string($arguments[1]))
        ) {
            throw new RuntimeException(
                "Template {$name}() expects a string and an optional character mask.",
            );
        }

        return match ($name) {
            'trim' => count($arguments) === 2
                ? trim($arguments[0], $arguments[1])
                : trim($arguments[0]),
            'ltrim' => count($arguments) === 2
                ? ltrim($arguments[0], $arguments[1])
                : ltrim($arguments[0]),
            'rtrim' => count($arguments) === 2
                ? rtrim($arguments[0], $arguments[1])
                : rtrim($arguments[0]),
            default => throw new RuntimeException(
                "Template string helper {$name} is not supported.",
            ),
        };
    }

    /** @param list<mixed> $arguments */
    private static function singleStringValue(string $name, array $arguments): int|string
    {
        if (count($arguments) !== 1 || !is_string($arguments[0] ?? null)) {
            throw new RuntimeException(
                "Template {$name}() expects exactly one string.",
            );
        }

        return match ($name) {
            'strlen' => strlen($arguments[0]),
            'strtolower' => strtolower($arguments[0]),
            'strtoupper' => strtoupper($arguments[0]),
            default => throw new RuntimeException(
                "Template string helper {$name} is not supported.",
            ),
        };
    }

    /** @param list<mixed> $arguments */
    private static function multibyteStringValue(string $name, array $arguments): int|string
    {
        if (
            (count($arguments) !== 1 && count($arguments) !== 2)
            || !is_string($arguments[0] ?? null)
            || (isset($arguments[1]) && !is_string($arguments[1]))
        ) {
            throw new RuntimeException(
                "Template {$name}() expects a string and an optional encoding.",
            );
        }

        return match ($name) {
            'mb_strlen' => count($arguments) === 2
                ? mb_strlen($arguments[0], $arguments[1])
                : mb_strlen($arguments[0]),
            'mb_strtolower' => count($arguments) === 2
                ? mb_strtolower($arguments[0], $arguments[1])
                : mb_strtolower($arguments[0]),
            'mb_strtoupper' => count($arguments) === 2
                ? mb_strtoupper($arguments[0], $arguments[1])
                : mb_strtoupper($arguments[0]),
            default => throw new RuntimeException(
                "Template string helper {$name} is not supported.",
            ),
        };
    }

    /** @param list<mixed> $arguments */
    private static function substringValue(array $arguments): string
    {
        if (
            count($arguments) < 2
            || count($arguments) > 3
            || !is_string($arguments[0] ?? null)
            || !is_int($arguments[1] ?? null)
            || (
                count($arguments) === 3
                && !is_int($arguments[2])
                && $arguments[2] !== null
            )
        ) {
            throw new RuntimeException(
                'Template substr() expects a string, integer offset, and optional integer length.',
            );
        }

        return count($arguments) === 3
            ? substr($arguments[0], $arguments[1], $arguments[2])
            : substr($arguments[0], $arguments[1]);
    }

    /** @param list<mixed> $arguments */
    private static function multibyteSubstringValue(array $arguments): string
    {
        if (
            count($arguments) < 2
            || count($arguments) > 4
            || !is_string($arguments[0] ?? null)
            || !is_int($arguments[1] ?? null)
            || (
                count($arguments) >= 3
                && !is_int($arguments[2])
                && $arguments[2] !== null
            )
            || (isset($arguments[3]) && !is_string($arguments[3]))
        ) {
            throw new RuntimeException(
                'Template mb_substr() expects a string, integer offset, optional integer length, and optional encoding.',
            );
        }

        return match (count($arguments)) {
            2 => mb_substr($arguments[0], $arguments[1]),
            3 => mb_substr($arguments[0], $arguments[1], $arguments[2]),
            default => mb_substr(
                $arguments[0],
                $arguments[1],
                $arguments[2],
                $arguments[3],
            ),
        };
    }

    /** @return array{type: int|string, text: string}|null */
    private function peek(): ?array
    {
        return $this->tokens[$this->position] ?? null;
    }

    /** @phpstan-impure */
    private function take(int|string $type): bool
    {
        if (($this->tokens[$this->position]['type'] ?? null) !== $type) {
            return false;
        }
        $this->position++;

        return true;
    }

    /** @param list<int|string> $types */
    private function takeOne(array $types): int|string|null
    {
        $type = $this->tokens[$this->position]['type'] ?? null;
        if ($type === null || !in_array($type, $types, true)) {
            return null;
        }
        $this->position++;

        return $type;
    }

    private function expect(int|string $type): void
    {
        if (!$this->take($type)) {
            $actual = $this->peek()['text'] ?? 'end of expression';
            throw new RuntimeException("Expected {$type}, found {$actual}.");
        }
    }

    /** @return list<array{type: int|string, text: string}> */
    private static function tokenize(string $expression): array
    {
        $raw = token_get_all('<?php '.$expression);
        $tokens = [];

        foreach ($raw as $token) {
            if (is_array($token)) {
                if (in_array($token[0], [T_OPEN_TAG, T_WHITESPACE], true)) {
                    continue;
                }
                if (
                    !in_array($token[0], [
                        T_VARIABLE,
                        T_STRING,
                        T_LNUMBER,
                        T_DNUMBER,
                        T_CONSTANT_ENCAPSED_STRING,
                        T_BOOLEAN_AND,
                        T_BOOLEAN_OR,
                        T_IS_IDENTICAL,
                        T_IS_NOT_IDENTICAL,
                        T_IS_EQUAL,
                        T_IS_NOT_EQUAL,
                        T_IS_GREATER_OR_EQUAL,
                        T_IS_SMALLER_OR_EQUAL,
                        T_OBJECT_OPERATOR,
                        T_DOUBLE_COLON,
                        T_DOUBLE_ARROW,
                        T_COALESCE,
                    ], true)
                ) {
                    throw new RuntimeException(
                        "Unsupported token {$token[1]} in template expression.",
                    );
                }
                $tokens[] = ['type' => $token[0], 'text' => $token[1]];
                continue;
            }
            if (!str_contains('()[],:?!-.<>+*/%', $token)) {
                throw new RuntimeException(
                    "Unsupported token {$token} in template expression.",
                );
            }
            $tokens[] = ['type' => $token, 'text' => $token];
        }

        return $tokens;
    }

    private static function stringLiteral(string $literal): string
    {
        $quote = $literal[0] ?? '';
        $body = substr($literal, 1, -1);

        return $quote === "'"
            ? str_replace(["\\\\", "\\'"], ["\\", "'"], $body)
            : stripcslashes($body);
    }

    private static function requireNumeric(mixed $value, string $operator): void
    {
        if (!is_int($value) && !is_float($value)) {
            throw new RuntimeException(
                "Template operator {$operator} requires numeric operands.",
            );
        }
    }

    private static function stringOperand(mixed $value): string
    {
        if (
            $value === null
            || is_string($value)
            || is_int($value)
            || is_float($value)
            || is_bool($value)
            || $value instanceof Stringable
        ) {
            return (string) $value;
        }

        throw new RuntimeException(
            'Template concatenation requires scalar, null, or Stringable operands.',
        );
    }
}
