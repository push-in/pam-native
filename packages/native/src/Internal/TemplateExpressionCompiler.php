<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

use RuntimeException;

/**
 * Compiles a template expression to the PHP source of one closure with the
 * exact semantics of TemplateExpression's closure tree (same grammar, eager
 * operand evaluation in the same order, the same "missing" coalescing rules
 * and the same diagnostics), so it runs as straight-line opcodes. The source
 * is written into the component cache at build time and evaluated once at
 * runtime otherwise.
 *
 * @internal
 */
final class TemplateExpressionCompiler
{
    private const RUNTIME = '\\'.TemplateExpression::class;

    /** @var list<array{type: int|string, text: string}> */
    private array $tokens;
    private int $position = 0;
    private int $coalescingDepth = 0;
    private int $temporaries = 0;
    private bool $usesMissing = false;

    /** @var array<string, true> literal string/int codes (valid array keys) */
    private array $keyLiterals = [];

    /** @var array<string, string|int> */
    private array $literalValues = [];

    /** @var list<string> */
    private array $statements = [];

    /** @param list<array{type: int|string, text: string}> $tokens */
    private function __construct(array $tokens)
    {
        $this->tokens = $tokens;
    }

    /**
     * Source of `static function (array $data, ?object $scope): mixed`.
     *
     * @throws RuntimeException when the expression is invalid
     */
    public static function closureSource(string $expression): string
    {
        $compiler = new self(TemplateExpression::__pamTokens($expression));
        $result = $compiler->ternary();
        if ($compiler->peek() !== null) {
            throw new RuntimeException(
                "Unexpected token {$compiler->peek()['text']} in template expression.",
            );
        }
        $body = $compiler->usesMissing
            ? '$m = '.self::RUNTIME.'::__pamMissing();'."\n"
            : '';
        $body .= implode("\n", $compiler->statements);

        return "static function (array \$data, ?object \$scope): mixed {\n{$body}\nreturn {$result};\n}";
    }

    /**
     * Source of `static function (array $data, ?object $scope): array`
     * returning the enabled, non-blank class names of a `:class` array
     * literal (`['a', 'b' => $on]`) in order, evaluating its operands exactly
     * like the array literal; null when the expression is not such a list
     * (dynamic keys or names, numeric or duplicate keys).
     */
    public static function classListSource(string $expression): ?string
    {
        try {
            $compiler = new self(TemplateExpression::__pamTokens($expression));
            if (!$compiler->take('[')) {
                return null;
            }
            // Same depth as an array literal inside the top-level expression.
            $compiler->coalescingDepth = 1;
            $seen = [];
            $compiler->emit('$p = [];');
            if (!$compiler->take(']')) {
                while (true) {
                    $first = $compiler->ternary();
                    $literal = $compiler->literalValues[$first] ?? null;
                    if (!is_string($literal)) {
                        return null;
                    }
                    if ($compiler->take(T_DOUBLE_ARROW)) {
                        if ((string) (int) $literal === $literal || isset($seen[$literal])) {
                            return null;
                        }
                        $seen[$literal] = true;
                        $second = $compiler->ternary();
                        if (trim($literal) !== '') {
                            $compiler->emit("if ((bool) {$second}) { \$p[] = {$first}; }");
                        }
                    } elseif (trim($literal) !== '') {
                        $compiler->emit("\$p[] = {$first};");
                    }
                    if ($compiler->take(']')) {
                        break;
                    }
                    $compiler->expect(',');
                    if ($compiler->take(']')) {
                        break;
                    }
                }
            }
            if ($compiler->peek() !== null) {
                return null;
            }
        } catch (RuntimeException) {
            return null;
        }
        $body = $compiler->usesMissing
            ? '$m = '.self::RUNTIME.'::__pamMissing();'."\n"
            : '';
        $body .= implode("\n", $compiler->statements);

        return "static function (array \$data, ?object \$scope): array {\n{$body}\nreturn \$p;\n}";
    }

    private function temporary(): string
    {
        return '$t'.(++$this->temporaries);
    }

    private function emit(string $statement): void
    {
        $this->statements[] = $statement;
    }

    private function missing(): string
    {
        $this->usesMissing = true;

        return '$m';
    }

    private function ternary(): string
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
        $result = $this->temporary();
        $this->emit($truthy === null
            ? "{$result} = (bool) {$condition} ? {$condition} : {$falsy};"
            : "{$result} = (bool) {$condition} ? {$truthy} : {$falsy};");

        return $result;
    }

    private function coalescing(): string
    {
        $this->coalescingDepth++;
        try {
            $left = $this->logicalOr();
        } finally {
            $this->coalescingDepth--;
        }

        if ($this->take(T_COALESCE)) {
            $right = $this->coalescing();
            $result = $this->temporary();
            $missing = $this->missing();
            $this->emit("{$result} = {$left} === {$missing} || {$left} === null ? {$right} : {$left};");

            return $result;
        }
        if ($this->coalescingDepth === 0) {
            $missing = $this->missing();
            $this->emit("if ({$left} === {$missing}) { throw new \\RuntimeException('Cannot resolve template value.'); }");
        }

        return $left;
    }

    private function logicalOr(): string
    {
        $value = $this->logicalAnd();
        while ($this->take(T_BOOLEAN_OR) || $this->take('||')) {
            $right = $this->logicalAnd();
            $result = $this->temporary();
            $this->emit("{$result} = (bool) {$value} || (bool) {$right};");
            $value = $result;
        }

        return $value;
    }

    private function logicalAnd(): string
    {
        $value = $this->equality();
        while ($this->take(T_BOOLEAN_AND) || $this->take('&&')) {
            $right = $this->equality();
            $result = $this->temporary();
            $this->emit("{$result} = (bool) {$value} && (bool) {$right};");
            $value = $result;
        }

        return $value;
    }

    private function equality(): string
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
            $symbol = match ($operator) {
                T_IS_IDENTICAL => '===',
                T_IS_NOT_IDENTICAL => '!==',
                T_IS_EQUAL => '==',
                T_IS_NOT_EQUAL => '!=',
            };
            $result = $this->temporary();
            $this->emit("{$result} = {$value} {$symbol} {$right};");
            $value = $result;
        }
    }

    private function comparison(): string
    {
        $value = $this->concatenation();
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
            $right = $this->concatenation();
            $symbol = match ($operator) {
                T_IS_GREATER_OR_EQUAL => '>=',
                T_IS_SMALLER_OR_EQUAL => '<=',
                '>' => '>',
                '<' => '<',
            };
            $result = $this->temporary();
            $this->emit("{$result} = {$value} {$symbol} {$right};");
            $value = $result;
        }
    }

    private function concatenation(): string
    {
        $value = $this->additive();
        while ($this->take('.')) {
            $right = $this->additive();
            $result = $this->temporary();
            $runtime = self::RUNTIME;
            $this->emit("{$result} = {$runtime}::__pamString({$value}).{$runtime}::__pamString({$right});");
            $value = $result;
        }

        return $value;
    }

    private function additive(): string
    {
        $value = $this->multiplicative();
        while (($operator = $this->takeOne(['+', '-'])) !== null) {
            $right = $this->multiplicative();
            $result = $this->temporary();
            $runtime = self::RUNTIME;
            $quoted = var_export((string) $operator, true);
            $this->emit("{$runtime}::__pamNumeric({$value}, {$quoted}); {$runtime}::__pamNumeric({$right}, {$quoted});");
            $this->emit("{$result} = {$value} {$operator} {$right};");
            $value = $result;
        }

        return $value;
    }

    private function multiplicative(): string
    {
        $value = $this->unary();
        while (($operator = $this->takeOne(['*', '/', '%'])) !== null) {
            $right = $this->unary();
            $result = $this->temporary();
            $runtime = self::RUNTIME;
            $quoted = var_export((string) $operator, true);
            $this->emit("{$runtime}::__pamNumeric({$value}, {$quoted}); {$runtime}::__pamNumeric({$right}, {$quoted});");
            if ($operator === '/' || $operator === '%') {
                $this->emit("if ({$right} == 0) { throw new \\RuntimeException('Division by zero in template expression.'); }");
            }
            if ($operator === '%') {
                $this->emit("if (!is_int({$value}) || !is_int({$right})) { throw new \\RuntimeException('Template modulo requires integer operands.'); }");
            }
            $this->emit("{$result} = {$value} {$operator} {$right};");
            $value = $result;
        }

        return $value;
    }

    private function unary(): string
    {
        if ($this->take('!')) {
            $operand = $this->unary();
            $result = $this->temporary();
            $this->emit("{$result} = !{$operand};");

            return $result;
        }
        if ($this->take('-')) {
            $operand = $this->unary();
            $result = $this->temporary();
            $this->emit("if (!is_int({$operand}) && !is_float({$operand})) { throw new \\RuntimeException('Unary minus requires a numeric expression.'); }");
            $this->emit("{$result} = -{$operand};");

            return $result;
        }

        return $this->primary();
    }

    private function primary(): string
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
            return $this->arrayLiteral();
        }
        if ($token['type'] === T_VARIABLE) {
            $this->position++;

            return $this->variable(substr($token['text'], 1));
        }
        if ($token['type'] === T_LNUMBER) {
            $this->position++;

            return $this->literal((int) str_replace('_', '', $token['text']));
        }
        if ($token['type'] === T_DNUMBER) {
            $this->position++;

            return $this->literal((float) str_replace('_', '', $token['text']));
        }
        if ($token['type'] === T_CONSTANT_ENCAPSED_STRING) {
            $this->position++;

            return $this->literal(TemplateExpression::__pamStringLiteral($token['text']));
        }
        if ($token['type'] === T_STRING) {
            $this->position++;
            $name = $token['text'];
            $lower = strtolower($name);
            if ($lower === 'true') {
                return 'true';
            }
            if ($lower === 'false') {
                return 'false';
            }
            if ($lower === 'null') {
                return 'null';
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

    private function literal(int|float|string $value): string
    {
        if (is_float($value) && !is_finite($value)) {
            $result = $this->temporary();
            $this->emit("{$result} = ".($value > 0 ? 'INF' : (is_nan($value) ? 'NAN' : '-INF')).';');

            return $result;
        }
        $code = var_export($value, true);
        if (is_float($value) && !str_contains($code, '.') && !str_contains($code, 'E') && !str_contains($code, 'e')) {
            $code .= '.0';
        }

        $code = '('.$code.')';
        if (is_string($value) || is_int($value)) {
            $this->keyLiterals[$code] = true;
            $this->literalValues[$code] = $value;
        }

        return $code;
    }

    private function arrayLiteral(): string
    {
        $result = $this->temporary();
        $this->emit("{$result} = [];");

        if (!$this->take(']')) {
            while (true) {
                $first = $this->ternary();
                if ($this->take(T_DOUBLE_ARROW)) {
                    if (!isset($this->keyLiterals[$first])) {
                        $this->emit("if (!is_string({$first}) && !is_int({$first})) { throw new \\RuntimeException('Template array keys must be strings or integers.'); }");
                    }
                    $second = $this->ternary();
                    $this->emit("{$result}[{$first}] = {$second};");
                } else {
                    $this->emit("{$result}[] = {$first};");
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

        return $result;
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
        $lenient = $this->coalescingDepth > 0 ? 'true' : 'false';
        $result = $this->temporary();
        $quoted = var_export($name, true);
        $runtime = self::RUNTIME;
        $this->emit(
            "{$result} = array_key_exists({$quoted}, \$data) ? \$data[{$quoted}] : {$runtime}::__pamVariable(\$scope, {$quoted}, {$lenient});",
        );
        $this->postfix($result);

        return $result;
    }

    private function postfix(string $value): void
    {
        $lenient = $this->coalescingDepth > 0 ? 'true' : 'false';
        $runtime = self::RUNTIME;

        while (true) {
            if ($this->take(T_OBJECT_OPERATOR) || $this->takePropertyDot()) {
                $segment = $this->peek();
                if ($segment === null || $segment['type'] !== T_STRING) {
                    throw new RuntimeException('Template property path is invalid.');
                }
                $this->position++;
                $step = var_export($segment['text'], true);
                $this->emit(
                    "{$value} = is_array({$value}) && array_key_exists({$step}, {$value}) ? {$value}[{$step}] : {$runtime}::__pamPropertyStep({$value}, {$step}, {$lenient});",
                );
                continue;
            }
            if ($this->take('[')) {
                $constant = $this->constantIndex();
                if ($constant !== null) {
                    $this->emit(
                        "{$value} = is_array({$value}) && array_key_exists({$constant}, {$value}) ? {$value}[{$constant}] : {$runtime}::__pamIndex({$value}, {$constant}, {$lenient});",
                    );
                    continue;
                }
                $index = $this->ternary();
                $this->expect(']');
                $this->emit(
                    "{$value} = is_array({$value}) && (is_int({$index}) || is_string({$index})) && array_key_exists({$index}, {$value}) ? {$value}[{$index}] : {$runtime}::__pamIndex({$value}, {$index}, {$lenient});",
                );
                continue;
            }

            return;
        }
    }

    /** A literal string or integer index followed by "]", consumed; otherwise null. */
    private function constantIndex(): ?string
    {
        $token = $this->tokens[$this->position] ?? null;
        $next = $this->tokens[$this->position + 1] ?? null;
        if ($token === null || ($next['type'] ?? null) !== ']') {
            return null;
        }
        if ($token['type'] === T_CONSTANT_ENCAPSED_STRING) {
            $value = TemplateExpression::__pamStringLiteral($token['text']);
        } elseif ($token['type'] === T_LNUMBER) {
            $value = (int) str_replace('_', '', $token['text']);
        } else {
            return null;
        }
        $this->position += 2;

        return var_export($value, true);
    }

    private function staticEnumCase(string $name): string
    {
        $case = $this->peek();
        if ($case === null || $case['type'] !== T_STRING) {
            throw new RuntimeException('Template enum case is invalid.');
        }
        $this->position++;
        $result = $this->temporary();
        $runtime = self::RUNTIME;
        $this->emit("{$result} = {$runtime}::__pamEnumCase(".var_export($name, true).', '.var_export($case['text'], true).', $scope);');
        $this->postfix($result);

        return $result;
    }

    /** @param list<string> $arguments */
    private function call(string $name, array $arguments): string
    {
        $result = $this->temporary();
        $runtime = self::RUNTIME;
        $this->emit(
            "{$result} = {$runtime}::__pamInvoke(".var_export($name, true).', '
            .var_export(strtolower($name), true).', ['.implode(', ', $arguments).'], $scope);',
        );

        return $result;
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
            $actual = $this->peek()['text'] ?? 'end of expression';
            throw new RuntimeException("Expected {$type}, found {$actual}.");
        }
    }
}
