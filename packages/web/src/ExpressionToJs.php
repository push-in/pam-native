<?php

declare(strict_types=1);

namespace Pam\Native\Web;

use Pam\Native\Internal\TemplateExpression;
use RuntimeException;

/**
 * Compiles a template expression (the grammar of TemplateExpressionCompiler:
 * `$a.b`, `$a['b']`, `??`, ternaries, calls, array literals) to one JS
 * expression over `c` (the component), `s` (loop scope) and `PAM.g` (globals).
 */
final class ExpressionToJs
{
    /** @var list<array{type: int|string, text: string}> */
    private array $tokens;
    private int $position = 0;
    private int $coalescing = 0;

    /**
     * @param array<string, true> $locals loop variables in scope
     * @param array<string, true> $properties component properties
     * @param array<string, true> $methods component methods
     */
    private function __construct(
        string $expression,
        private readonly array $locals,
        private readonly array $properties,
        private readonly array $methods,
        private readonly string $where,
    ) {
        $this->tokens = TemplateExpression::__pamTokens(TemplateExpression::normalizeObjectLiteral($expression));
    }

    /**
     * @param array<string, true> $locals
     * @param array<string, true> $properties
     * @param array<string, true> $methods
     */
    public static function compile(string $expression, array $locals, array $properties, array $methods, string $where): string
    {
        $compiler = new self($expression, $locals, $properties, $methods, $where);
        $result = $compiler->ternary();
        if ($compiler->peek() !== null) {
            throw $compiler->error("unexpected {$compiler->peek()['text']}");
        }

        return $result;
    }

    /**
     * An event handler: a method name (`playerKey`), or an expression run on
     * the event (`open($item['id'])`, `$event` is the event).
     *
     * @param array<string, true> $locals
     * @param array<string, true> $properties
     * @param array<string, true> $methods
     */
    public static function handler(string $expression, array $locals, array $properties, array $methods, string $where): string
    {
        $trimmed = trim($expression);
        if (preg_match('/^[A-Za-z_][A-Za-z0-9_]*$/D', $trimmed) === 1) {
            if (!isset($methods[$trimmed])) {
                throw new RuntimeException("{$where}: the component has no method {$trimmed}().");
            }

            return "function (e) { c.{$trimmed}(e); }";
        }
        $locals['event'] = true;

        return 'function (e) { var s2 = Object.create(s); s2.event = e; (function (s) { '
            .self::compile($trimmed, $locals, $properties, $methods, $where)
            .'; })(s2); }';
    }

    private function ternary(): string
    {
        $condition = $this->coalescing();
        if (!$this->take('?')) {
            return $condition;
        }
        if ($this->take(':')) {
            $falsy = $this->ternary();

            return "(function (v) { return PAM.b(v) ? v : {$falsy}; })({$condition})";
        }
        $truthy = $this->ternary();
        $this->expect(':');
        $falsy = $this->ternary();

        return "(PAM.b({$condition}) ? {$truthy} : {$falsy})";
    }

    private function coalescing(): string
    {
        $this->coalescing++;
        try {
            $left = $this->logicalOr();
        } finally {
            $this->coalescing--;
        }
        if ($this->take(T_COALESCE)) {
            $right = $this->coalescing();

            return "PAM.co({$left}, function () { return {$right}; })";
        }

        return $left;
    }

    private function logicalOr(): string
    {
        $value = $this->logicalAnd();
        while ($this->take(T_BOOLEAN_OR) || $this->take('||')) {
            $value = "(PAM.b({$value}) || PAM.b({$this->logicalAnd()}))";
        }

        return $value;
    }

    private function logicalAnd(): string
    {
        $value = $this->equality();
        while ($this->take(T_BOOLEAN_AND) || $this->take('&&')) {
            $value = "(PAM.b({$value}) && PAM.b({$this->equality()}))";
        }

        return $value;
    }

    private function equality(): string
    {
        $value = $this->comparison();
        while (($operator = $this->takeOne([T_IS_IDENTICAL, T_IS_NOT_IDENTICAL, T_IS_EQUAL, T_IS_NOT_EQUAL])) !== null) {
            $right = $this->comparison();
            $value = match ($operator) {
                T_IS_IDENTICAL => "PAM.same({$value}, {$right})",
                T_IS_NOT_IDENTICAL => "!PAM.same({$value}, {$right})",
                T_IS_EQUAL => "PAM.eq({$value}, {$right})",
                default => "!PAM.eq({$value}, {$right})",
            };
        }

        return $value;
    }

    private function comparison(): string
    {
        $value = $this->concatenation();
        while (($operator = $this->takeOne([T_IS_GREATER_OR_EQUAL, T_IS_SMALLER_OR_EQUAL, '>', '<'])) !== null) {
            $right = $this->concatenation();
            $symbol = match ($operator) {
                T_IS_GREATER_OR_EQUAL => '>=',
                T_IS_SMALLER_OR_EQUAL => '<=',
                default => (string) $operator,
            };
            $value = "(PAM.cmp({$value}, {$right}) {$symbol} 0)";
        }

        return $value;
    }

    private function concatenation(): string
    {
        $value = $this->additive();
        while ($this->take('.')) {
            $value = "(PAM.s({$value}) + PAM.s({$this->additive()}))";
        }

        return $value;
    }

    private function additive(): string
    {
        $value = $this->multiplicative();
        while (($operator = $this->takeOne(['+', '-'])) !== null) {
            $value = "(PAM.num({$value}) {$operator} PAM.num({$this->multiplicative()}))";
        }

        return $value;
    }

    private function multiplicative(): string
    {
        $value = $this->unary();
        while (($operator = $this->takeOne(['*', '/', '%'])) !== null) {
            $value = "(PAM.num({$value}) {$operator} PAM.num({$this->unary()}))";
        }

        return $value;
    }

    private function unary(): string
    {
        if ($this->take('!')) {
            return "!PAM.b({$this->unary()})";
        }
        if ($this->take('-')) {
            return "(-PAM.num({$this->unary()}))";
        }

        return $this->primary();
    }

    private function primary(): string
    {
        $token = $this->peek();
        if ($token === null) {
            throw $this->error('the expression ended unexpectedly');
        }
        if ($this->take('(')) {
            $value = $this->ternary();
            $this->expect(')');

            return $this->postfix("({$value})");
        }
        if ($this->take('[')) {
            return $this->postfix($this->arrayLiteral());
        }
        $this->position++;
        switch ($token['type']) {
            case T_VARIABLE:
                return $this->postfix($this->variable(substr($token['text'], 1)));
            case T_LNUMBER:
                return (string) (int) str_replace('_', '', $token['text']);
            case T_DNUMBER:
                return Js::number((float) str_replace('_', '', $token['text']));
            case T_CONSTANT_ENCAPSED_STRING:
                return Js::string(TemplateExpression::__pamStringLiteral($token['text']));
            case T_STRING:
                $lower = strtolower($token['text']);
                if (in_array($lower, ['true', 'false', 'null'], true)) {
                    return $lower;
                }
                if ($this->take('(')) {
                    return $this->postfix($this->call($token['text'], $this->arguments()));
                }
        }

        throw $this->error("unsupported token {$token['text']}");
    }

    private function arrayLiteral(): string
    {
        $pairs = [];
        $keyed = false;
        if (!$this->take(']')) {
            while (true) {
                $first = $this->ternary();
                if ($this->take(T_DOUBLE_ARROW)) {
                    $pairs[] = [$first, $this->ternary()];
                    $keyed = true;
                } else {
                    $pairs[] = [null, $first];
                }
                if ($this->take(']')) {
                    break;
                }
                $this->expect(',');
                if ($this->take(']')) {
                    break;
                }
            }
        }
        if (!$keyed) {
            return '['.implode(', ', array_column($pairs, 1)).']';
        }

        return 'PAM.arr(['.implode(', ', array_map(
            static fn (array $pair): string => '['.($pair[0] ?? 'null').', '.$pair[1].']',
            $pairs,
        )).'])';
    }

    /** @return list<string> */
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

    private function variable(string $name): string
    {
        if (isset($this->locals[$name])) {
            return 's.'.$name;
        }
        if ($name === 'this') {
            return 'c';
        }
        if (isset($this->properties[$name])) {
            return 'c.'.$name;
        }

        // Anything else is an App::globals() entry, resolved at runtime.
        return 'PAM.g.'.$name;
    }

    private function postfix(string $value): string
    {
        $keys = [];
        while (true) {
            if ($this->take(T_OBJECT_OPERATOR) || $this->take(T_NULLSAFE_OBJECT_OPERATOR) || $this->takePropertyDot()) {
                $segment = $this->peek();
                if ($segment === null || $segment['type'] !== T_STRING) {
                    throw $this->error('invalid property path');
                }
                $this->position++;
                if ($this->take('(')) {
                    $value = $this->access($value, $keys);
                    $keys = [];
                    $value = "{$value}.{$segment['text']}(".implode(', ', $this->arguments()).')';
                    continue;
                }
                $keys[] = Js::string($segment['text']);
                continue;
            }
            if ($this->take('[')) {
                $keys[] = $this->ternary();
                $this->expect(']');
                continue;
            }

            return $this->access($value, $keys);
        }
    }

    /** @param list<string> $keys */
    private function access(string $value, array $keys): string
    {
        if ($keys === []) {
            return $value;
        }
        if ($this->coalescing > 0) {
            return "PAM.dig({$value}, [".implode(', ', $keys).'])';
        }

        return $value.implode('', array_map(static fn (string $key): string => "[{$key}]", $keys));
    }

    /** @param list<string> $arguments */
    private function call(string $name, array $arguments): string
    {
        if (isset($this->methods[$name])) {
            return "c.{$name}(".implode(', ', $arguments).')';
        }

        return Stdlib::call($name, $arguments, $this->where);
    }

    private function takePropertyDot(): bool
    {
        if (($this->tokens[$this->position]['type'] ?? null) !== '.'
            || ($this->tokens[$this->position + 1]['type'] ?? null) !== T_STRING
            // `$a . trim($b)` concatenates a call.
            || ($this->tokens[$this->position + 2]['type'] ?? null) === '(') {
            return false;
        }
        $this->position++;

        return true;
    }

    /** @return array{type: int|string, text: string}|null */
    private function peek(): ?array
    {
        return $this->tokens[$this->position] ?? null;
    }

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
            throw $this->error('expected '.(is_int($type) ? token_name($type) : $type));
        }
    }

    private function error(string $message): RuntimeException
    {
        return new RuntimeException("{$this->where}: {$message} in template expression.");
    }
}
