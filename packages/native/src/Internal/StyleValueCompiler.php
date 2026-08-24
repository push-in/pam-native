<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

use JsonException;
use Pam\Native\Style\StyleExpressionKind;
use Pam\Native\Style\StyleValueUnit;
use RuntimeException;

/** Compile-time parser and allocation-free-at-call-site evaluator for CSS math. */
final class StyleValueCompiler
{
    private const PREFIX = '@pam-style:';

    /** @var list<array{type:string,value:string}> */
    private array $tokens = [];
    private int $position = 0;

    private function __construct(private readonly string $name)
    {
    }

    public static function isDynamic(string $value): bool
    {
        return preg_match(
            '/(?:calc|min|max|clamp|env)\(|-?(?:\d+|\d*\.\d+)(?:vw|vh|vmin|vmax|sp|%)(?:$|[^a-z])/i',
            trim($value),
        ) === 1;
    }

    public static function encode(string $value, string $name): string
    {
        $compiler = new self($name);
        $compiler->tokens = self::tokenize(trim($value), $name);
        $node = $compiler->expression();
        if ($compiler->peek() !== null) {
            throw new RuntimeException("Unexpected CSS math token in {$name}: {$value}.");
        }
        try {
            return self::PREFIX.base64_encode(json_encode(
                $node,
                JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES | JSON_PRESERVE_ZERO_FRACTION,
            ));
        } catch (JsonException $error) {
            throw new RuntimeException("Cannot encode CSS math in {$name}.", previous: $error);
        }
    }

    public static function encoded(string $value): bool
    {
        return str_starts_with($value, self::PREFIX);
    }

    /** @param array<string, float|int> $environment */
    public static function resolve(string $value, array $environment): float
    {
        if (!self::encoded($value)) {
            throw new RuntimeException('Expected compiled PAM style expression.');
        }
        $json = base64_decode(substr($value, strlen(self::PREFIX)), true);
        if (!is_string($json)) {
            throw new RuntimeException('Invalid PAM style expression envelope.');
        }
        try {
            $node = json_decode($json, true, 24, JSON_THROW_ON_ERROR);
        } catch (JsonException $error) {
            throw new RuntimeException('Invalid PAM style expression bytecode.', previous: $error);
        }
        if (!is_array($node)) {
            throw new RuntimeException('Invalid PAM style expression root.');
        }

        return self::evaluate($node, $environment, 0);
    }

    /** @return array<string, mixed> */
    private function expression(int $minimumPrecedence = 0): array
    {
        $left = $this->primary();
        while (($token = $this->peek()) !== null && $token['type'] === 'operator') {
            $precedence = in_array($token['value'], ['*', '/'], true) ? 20 : 10;
            if ($precedence < $minimumPrecedence) {
                break;
            }
            $this->position++;
            $right = $this->expression($precedence + 1);
            $left = [
                'kind' => match ($token['value']) {
                    '+' => StyleExpressionKind::Add->value,
                    '-' => StyleExpressionKind::Subtract->value,
                    '*' => StyleExpressionKind::Multiply->value,
                    '/' => StyleExpressionKind::Divide->value,
                },
                'children' => [$left, $right],
            ];
        }

        return $left;
    }

    /** @return array<string, mixed> */
    private function primary(): array
    {
        $token = $this->take();
        if ($token === null) {
            throw new RuntimeException("Incomplete CSS math expression in {$this->name}.");
        }
        if ($token['type'] === 'operator' && in_array($token['value'], ['+', '-'], true)) {
            $node = $this->primary();
            if ($token['value'] === '+') {
                return $node;
            }
            return [
                'kind' => StyleExpressionKind::Multiply->value,
                'children' => [self::literal(-1.0, StyleValueUnit::Number), $node],
            ];
        }
        if ($token['type'] === 'number') {
            if (preg_match('/^((?:\d+|\d*\.\d+))(px|dp|sp|pt|rem|%|vw|vh|vmin|vmax)?$/Di', $token['value'], $match) !== 1) {
                throw new RuntimeException("Invalid CSS number {$token['value']} in {$this->name}.");
            }
            return self::literal(
                (float) $match[1],
                self::unit(strtolower($match[2] ?? '')),
            );
        }
        if ($token['type'] === 'left') {
            $node = $this->expression();
            $this->expect('right');
            return $node;
        }
        if ($token['type'] !== 'identifier') {
            throw new RuntimeException("Invalid CSS math expression in {$this->name}.");
        }
        $function = strtolower($token['value']);
        $this->expect('left');
        if ($function === 'env') {
            $name = $this->take();
            if ($name === null || $name['type'] !== 'identifier') {
                throw new RuntimeException("env() requires a native environment name in {$this->name}.");
            }
            $this->expect('right');
            return [
                'kind' => StyleExpressionKind::Environment->value,
                'name' => $name['value'],
            ];
        }
        if ($function === 'calc') {
            $node = $this->expression();
            $this->expect('right');
            return $node;
        }
        if (!in_array($function, ['min', 'max', 'clamp'], true)) {
            throw new RuntimeException("Unsupported CSS math function {$function}() in {$this->name}.");
        }
        $children = [$this->expression()];
        while (($next = $this->peek()) !== null && $next['type'] === 'comma') {
            $this->position++;
            $children[] = $this->expression();
        }
        $this->expect('right');
        if (($function === 'clamp' && count($children) !== 3) || ($function !== 'clamp' && count($children) < 1)) {
            throw new RuntimeException("Invalid {$function}() arity in {$this->name}.");
        }
        return [
            'kind' => match ($function) {
                'min' => StyleExpressionKind::Minimum->value,
                'max' => StyleExpressionKind::Maximum->value,
                'clamp' => StyleExpressionKind::Clamp->value,
            },
            'children' => $children,
        ];
    }

    /** @return array{kind:int,value:float,unit:int} */
    private static function literal(float $value, StyleValueUnit $unit): array
    {
        return ['kind' => StyleExpressionKind::Literal->value, 'value' => $value, 'unit' => $unit->value];
    }

    /** @param array<string, mixed> $node @param array<string, float|int> $environment */
    private static function evaluate(array $node, array $environment, int $depth): float
    {
        if ($depth > 24) {
            throw new RuntimeException('PAM style expression exceeds the evaluation depth limit.');
        }
        $kind = StyleExpressionKind::tryFrom((int) ($node['kind'] ?? 0));
        if ($kind === null) {
            throw new RuntimeException('Unknown PAM style expression operation.');
        }
        if ($kind === StyleExpressionKind::Literal) {
            $unit = StyleValueUnit::tryFrom((int) ($node['unit'] ?? 0));
            $number = $node['value'] ?? null;
            if ($unit === null || (!is_int($number) && !is_float($number))) {
                throw new RuntimeException('Invalid PAM style literal.');
            }
            return self::resolveUnit((float) $number, $unit, $environment);
        }
        if ($kind === StyleExpressionKind::Environment) {
            $name = $node['name'] ?? null;
            $value = is_string($name) ? ($environment['env.'.$name] ?? null) : null;
            if (!is_int($value) && !is_float($value)) {
                throw new RuntimeException("Native style environment value {$name} is unavailable.");
            }
            return (float) $value;
        }
        $children = $node['children'] ?? null;
        if (!is_array($children)) {
            throw new RuntimeException('Invalid PAM style expression operands.');
        }
        $values = array_map(
            static fn (mixed $child): float => is_array($child)
                ? self::evaluate($child, $environment, $depth + 1)
                : throw new RuntimeException('Invalid PAM style expression child.'),
            $children,
        );
        return match ($kind) {
            StyleExpressionKind::Add => $values[0] + $values[1],
            StyleExpressionKind::Subtract => $values[0] - $values[1],
            StyleExpressionKind::Multiply => $values[0] * $values[1],
            StyleExpressionKind::Divide => $values[1] == 0.0
                ? throw new RuntimeException('CSS calc() division by zero.')
                : $values[0] / $values[1],
            StyleExpressionKind::Minimum => min($values),
            StyleExpressionKind::Maximum => max($values),
            StyleExpressionKind::Clamp => max($values[0], min($values[1], $values[2])),
            default => throw new RuntimeException('Invalid PAM style expression operation.'),
        };
    }

    /** @param array<string, float|int> $environment */
    private static function resolveUnit(float $value, StyleValueUnit $unit, array $environment): float
    {
        $width = (float) ($environment['width'] ?? 0.0);
        $height = (float) ($environment['height'] ?? 0.0);
        return match ($unit) {
            StyleValueUnit::Number, StyleValueUnit::Px, StyleValueUnit::Dp, StyleValueUnit::Pt => $value,
            StyleValueUnit::Sp => $value * (float) ($environment['fontScale'] ?? 1.0),
            StyleValueUnit::Rem => $value * (float) ($environment['rootFontSize'] ?? 16.0),
            StyleValueUnit::Percent => $value * (float) ($environment['reference'] ?? 0.0) / 100.0,
            StyleValueUnit::Vw => $value * $width / 100.0,
            StyleValueUnit::Vh => $value * $height / 100.0,
            StyleValueUnit::Vmin => $value * min($width, $height) / 100.0,
            StyleValueUnit::Vmax => $value * max($width, $height) / 100.0,
        };
    }

    /** @return list<array{type:string,value:string}> */
    private static function tokenize(string $value, string $name): array
    {
        $tokens = [];
        $length = strlen($value);
        for ($index = 0; $index < $length;) {
            if (ctype_space($value[$index])) {
                $index++;
                continue;
            }
            $rest = substr($value, $index);
            if (preg_match('/^(?:\d+|\d*\.\d+)(?:px|dp|sp|pt|rem|%|vw|vh|vmin|vmax)?/i', $rest, $match) === 1) {
                $tokens[] = ['type' => 'number', 'value' => $match[0]];
                $index += strlen($match[0]);
                continue;
            }
            if (preg_match('/^[A-Za-z][A-Za-z0-9-]*/', $rest, $match) === 1) {
                $tokens[] = ['type' => 'identifier', 'value' => $match[0]];
                $index += strlen($match[0]);
                continue;
            }
            $tokens[] = match ($value[$index]) {
                '+', '-', '*', '/' => ['type' => 'operator', 'value' => $value[$index]],
                '(' => ['type' => 'left', 'value' => '('],
                ')' => ['type' => 'right', 'value' => ')'],
                ',' => ['type' => 'comma', 'value' => ','],
                default => throw new RuntimeException("Invalid CSS math character {$value[$index]} in {$name}."),
            };
            $index++;
        }
        return $tokens;
    }

    private static function unit(string $unit): StyleValueUnit
    {
        return match ($unit) {
            '' => StyleValueUnit::Number,
            'px' => StyleValueUnit::Px,
            'dp' => StyleValueUnit::Dp,
            'sp' => StyleValueUnit::Sp,
            'pt' => StyleValueUnit::Pt,
            'rem' => StyleValueUnit::Rem,
            '%' => StyleValueUnit::Percent,
            'vw' => StyleValueUnit::Vw,
            'vh' => StyleValueUnit::Vh,
            'vmin' => StyleValueUnit::Vmin,
            'vmax' => StyleValueUnit::Vmax,
        };
    }

    /** @return array{type:string,value:string}|null */
    private function peek(): ?array
    {
        return $this->tokens[$this->position] ?? null;
    }

    /** @return array{type:string,value:string}|null */
    private function take(): ?array
    {
        return $this->tokens[$this->position++] ?? null;
    }

    private function expect(string $type): void
    {
        $token = $this->take();
        if ($token === null || $token['type'] !== $type) {
            throw new RuntimeException("Expected {$type} in CSS math expression in {$this->name}.");
        }
    }
}
