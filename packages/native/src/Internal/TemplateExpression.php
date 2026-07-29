<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

use ReflectionMethod;
use ReflectionProperty;
use RuntimeException;
use Stringable;

final class TemplateExpression
{
    /** @var list<array{type: int|string, text: string}> */
    private array $tokens;
    private int $position = 0;

    /**
     * @param array<string, mixed> $data
     */
    private function __construct(
        string $expression,
        private readonly ?object $scope,
        private readonly array $data,
    ) {
        $this->tokens = self::tokenize($expression);
    }

    /** @param array<string, mixed> $data */
    public static function evaluate(
        string $expression,
        ?object $scope,
        array $data,
    ): mixed {
        $parser = new self($expression, $scope, $data);
        $value = $parser->ternary();

        if ($parser->peek() !== null) {
            throw new RuntimeException(
                "Unexpected token {$parser->peek()['text']} in template expression.",
            );
        }

        return $value;
    }

    /** @param array<string, mixed> $data */
    public static function interpolate(
        string $value,
        ?object $scope,
        array $data,
    ): string {
        return preg_replace_callback(
            '/\{\{\s*(.*?)\s*\}\}/s',
            static function (array $match) use ($scope, $data): string {
                $resolved = self::evaluate($match[1], $scope, $data);

                if (
                    !is_string($resolved)
                    && !is_int($resolved)
                    && !is_float($resolved)
                    && !is_bool($resolved)
                    && !$resolved instanceof Stringable
                ) {
                    throw new RuntimeException(
                        "Template expression {$match[1]} is not printable.",
                    );
                }

                return (string) $resolved;
            },
            $value,
        ) ?? $value;
    }

    private function ternary(): mixed
    {
        $condition = $this->logicalOr();

        if (!$this->take('?')) {
            return $condition;
        }
        if ($this->take(':')) {
            $truthy = $condition;
        } else {
            $truthy = $this->ternary();
            $this->expect(':');
        }
        $falsy = $this->ternary();

        return (bool) $condition ? $truthy : $falsy;
    }

    private function logicalOr(): mixed
    {
        $value = $this->logicalAnd();

        while ($this->take(T_BOOLEAN_OR) || $this->take('||')) {
            $right = $this->logicalAnd();
            $value = (bool) $value || (bool) $right;
        }

        return $value;
    }

    private function logicalAnd(): mixed
    {
        $value = $this->equality();

        while ($this->take(T_BOOLEAN_AND) || $this->take('&&')) {
            $right = $this->equality();
            $value = (bool) $value && (bool) $right;
        }

        return $value;
    }

    private function equality(): mixed
    {
        $value = $this->comparison();

        while (true) {
            $operator = $this->takeOne([
                T_IS_IDENTICAL,
                T_IS_NOT_IDENTICAL,
                T_IS_EQUAL,
                T_IS_NOT_EQUAL,
            ]);
            if ($operator === null) {
                return $value;
            }
            $right = $this->comparison();
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
    }

    private function comparison(): mixed
    {
        $value = $this->unary();

        while (true) {
            $operator = $this->takeOne([
                T_IS_GREATER_OR_EQUAL,
                T_IS_SMALLER_OR_EQUAL,
                '>',
                '<',
            ]);
            if ($operator === null) {
                return $value;
            }
            $right = $this->unary();
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
    }

    private function unary(): mixed
    {
        if ($this->take('!')) {
            return !$this->unary();
        }
        if ($this->take('-')) {
            $value = $this->unary();
            if (!is_int($value) && !is_float($value)) {
                throw new RuntimeException('Unary minus requires a numeric expression.');
            }

            return -$value;
        }

        return $this->primary();
    }

    private function primary(): mixed
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

            return $this->resolveVariable(substr($token['text'], 1));
        }
        if ($token['type'] === T_LNUMBER) {
            $this->position++;

            return (int) str_replace('_', '', $token['text']);
        }
        if ($token['type'] === T_DNUMBER) {
            $this->position++;

            return (float) str_replace('_', '', $token['text']);
        }
        if ($token['type'] === T_CONSTANT_ENCAPSED_STRING) {
            $this->position++;

            return self::stringLiteral($token['text']);
        }
        if ($token['type'] === T_STRING) {
            $this->position++;
            $name = $token['text'];
            $lower = strtolower($name);
            if ($lower === 'true') {
                return true;
            }
            if ($lower === 'false') {
                return false;
            }
            if ($lower === 'null') {
                return null;
            }
            if ($this->take('(')) {
                $arguments = $this->arguments();

                return $this->invoke($name, $arguments);
            }
        }

        throw new RuntimeException(
            "Unsupported token {$token['text']} in template expression.",
        );
    }

    /** @return array<array-key, mixed> */
    private function array(): array
    {
        $values = [];

        if ($this->take(']')) {
            return $values;
        }
        while (true) {
            $first = $this->ternary();
            if ($this->take(T_DOUBLE_ARROW)) {
                if (!is_string($first) && !is_int($first)) {
                    throw new RuntimeException(
                        'Template array keys must be strings or integers.',
                    );
                }
                $values[$first] = $this->ternary();
            } else {
                $values[] = $first;
            }
            if ($this->take(']')) {
                return $values;
            }
            $this->expect(',');
            if ($this->take(']')) {
                return $values;
            }
        }
    }

    /** @return list<mixed> */
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

    private function resolveVariable(string $name): mixed
    {
        if (array_key_exists($name, $this->data)) {
            $value = $this->data[$name];
        } elseif ($this->scope !== null && property_exists($this->scope, $name)) {
            $property = new ReflectionProperty($this->scope, $name);
            if (!$property->isInitialized($this->scope)) {
                throw new RuntimeException("Template property \${$name} is not initialized.");
            }
            $value = $property->getValue($this->scope);
        } else {
            throw new RuntimeException("Template expression \${$name} is undefined.");
        }

        while (true) {
            if ($this->take(T_OBJECT_OPERATOR) || $this->take('.')) {
                $segment = $this->peek();
                if ($segment === null || $segment['type'] !== T_STRING) {
                    throw new RuntimeException('Template property path is invalid.');
                }
                $this->position++;
                $value = match (true) {
                    is_array($value) && array_key_exists($segment['text'], $value) =>
                        $value[$segment['text']],
                    is_object($value) && property_exists($value, $segment['text']) =>
                        (new ReflectionProperty($value, $segment['text']))->getValue($value),
                    default => throw new RuntimeException(
                        "Cannot resolve template property {$segment['text']}.",
                    ),
                };
                continue;
            }
            if ($this->take('[')) {
                $index = $this->ternary();
                $this->expect(']');
                if (
                    (!is_string($index) && !is_int($index))
                    || !is_array($value)
                    || !array_key_exists($index, $value)
                ) {
                    throw new RuntimeException('Cannot resolve template array index.');
                }
                $value = $value[$index];
                continue;
            }
            break;
        }

        return $value;
    }

    /** @param list<mixed> $arguments */
    private function invoke(string $name, array $arguments): mixed
    {
        if ($this->scope === null || !method_exists($this->scope, $name)) {
            throw new RuntimeException("Template method {$name} does not exist.");
        }
        $method = new ReflectionMethod($this->scope, $name);
        if (!$method->isPublic()) {
            throw new RuntimeException("Template method {$name} must be public.");
        }

        return $method->invokeArgs($this->scope, $arguments);
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
                        T_DOUBLE_ARROW,
                    ], true)
                ) {
                    throw new RuntimeException(
                        "Unsupported token {$token[1]} in template expression.",
                    );
                }
                $tokens[] = ['type' => $token[0], 'text' => $token[1]];
                continue;
            }
            if (!str_contains('()[],:?!-.<>', $token)) {
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
}
