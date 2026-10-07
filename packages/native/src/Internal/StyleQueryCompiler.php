<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

use Pam\Native\Style\StyleQueryFeature;
use Pam\Native\Style\StyleQueryOperator;
use Pam\Native\Style\StyleQueryValueKind;
use RuntimeException;

use function count;
use function in_array;
use function is_array;
use function is_float;
use function is_int;
use function is_string;
use function strlen;

/** Compiles native media/container conditions into a typed, string-free IR. */
final class StyleQueryCompiler
{
    private function __construct()
    {
    }

    /**
     * @return array{
     *   feature: int,
     *   operator: int,
     *   valueKind: int,
     *   number: float|null,
     *   keyword: string|null,
     *   unit: string|null
     * }
     */
    public static function compile(string $condition, string $name): array
    {
        $condition = trim($condition);
        // Named containers are part of the selector, not of the condition AST.
        $condition = preg_replace('/^(?!(?:not|only|screen|all|print)\b)[A-Za-z][A-Za-z0-9_-]*\s+(?=\()/i', '', $condition)
            ?? $condition;

        return self::condition($condition, $name);
    }

    /**
     * Media query lists, `and`/`or`/`not`, media types and range syntax
     * compile to `{op, children}` nodes around single-feature leaves.
     *
     * @return array<string, mixed>
     */
    private static function condition(string $condition, string $name): array
    {
        $condition = trim($condition);
        $list = self::splitTopLevel($condition, '/^\s*,\s*/');
        if (count($list) > 1) {
            return self::group('or', $list, $name);
        }
        $alternatives = self::splitTopLevel($condition, '/^\s+or\s+/i');
        if (count($alternatives) > 1) {
            return self::group('or', $alternatives, $name);
        }
        $conjunction = self::splitTopLevel($condition, '/^\s+and\s+/i');
        if (count($conjunction) > 1) {
            return self::group('and', $conjunction, $name);
        }
        if (preg_match('/^not\s+(.+)$/isD', $condition, $match) === 1) {
            return ['op' => 'not', 'children' => [self::condition($match[1], $name)]];
        }
        if (preg_match('/^only\s+(.+)$/isD', $condition, $match) === 1) {
            return self::condition($match[1], $name);
        }
        $lower = strtolower($condition);
        if (in_array($lower, ['all', 'screen'], true)) {
            return ['op' => 'and', 'children' => []];
        }
        if (in_array($lower, ['print', 'speech'], true)) {
            return ['op' => 'or', 'children' => []];
        }
        if (str_starts_with($condition, '(') && str_ends_with($condition, ')')
            && self::matchingClose($condition) === strlen($condition) - 1) {
            $inner = trim(substr($condition, 1, -1));
            if (str_starts_with($inner, '(') || preg_match('/^not\s/i', $inner) === 1) {
                return self::condition($inner, $name);
            }
            if (preg_match(
                '/^([0-9]+(?:\.[0-9]+)?(?:px|dp|em|rem)?)\s*(<=|<|>=|>)\s*(width|height)\s*(<=|<|>=|>)\s*([0-9]+(?:\.[0-9]+)?(?:px|dp|em|rem)?)$/Di',
                $inner,
                $match,
            ) === 1) {
                $flip = ['<' => '>', '<=' => '>=', '>' => '<', '>=' => '<='];
                return ['op' => 'and', 'children' => [
                    self::leaf('('.$match[3].' '.$flip[$match[2]].' '.$match[1].')', $name),
                    self::leaf('('.$match[3].' '.$match[4].' '.$match[5].')', $name),
                ]];
            }
            if (preg_match(
                '/^([0-9]+(?:\.[0-9]+)?(?:px|dp|em|rem)?)\s*(<=|<|>=|>|=)\s*(width|height)$/Di',
                $inner,
                $match,
            ) === 1) {
                $flip = ['<' => '>', '<=' => '>=', '>' => '<', '>=' => '<=', '=' => '='];
                return self::leaf('('.$match[3].' '.$flip[$match[2]].' '.$match[1].')', $name);
            }
        }

        return self::leaf($condition, $name);
    }

    /**
     * @param list<string> $parts
     * @return array<string, mixed>
     */
    private static function group(string $operator, array $parts, string $name): array
    {
        return [
            'op' => $operator,
            'children' => array_map(
                static fn (string $part): array => self::condition($part, $name),
                $parts,
            ),
        ];
    }

    /** @return list<string> */
    private static function splitTopLevel(string $value, string $separator): array
    {
        $parts = [];
        $depth = 0;
        $start = 0;
        $length = strlen($value);
        for ($index = 0; $index < $length; $index++) {
            $character = $value[$index];
            if ($character === '(') {
                $depth++;
                continue;
            }
            if ($character === ')') {
                $depth--;
                continue;
            }
            if ($depth !== 0) {
                continue;
            }
            if (preg_match($separator, substr($value, $index), $match) === 1) {
                $parts[] = trim(substr($value, $start, $index - $start));
                $index += strlen($match[0]) - 1;
                $start = $index + 1;
            }
        }
        $parts[] = trim(substr($value, $start));

        return array_values(array_filter($parts, static fn (string $part): bool => $part !== ''));
    }

    private static function matchingClose(string $value): int
    {
        $depth = 0;
        $length = strlen($value);
        for ($index = 0; $index < $length; $index++) {
            if ($value[$index] === '(') {
                $depth++;
            } elseif ($value[$index] === ')') {
                $depth--;
                if ($depth === 0) {
                    return $index;
                }
            }
        }

        return -1;
    }

    /** @return array{feature: int, operator: int, valueKind: int, number: float|null, keyword: string|null, unit: string|null} */
    private static function leaf(string $condition, string $name): array
    {
        $condition = preg_replace_callback(
            '/([0-9]+(?:\.[0-9]+)?)r?em\b/i',
            static fn (array $match): string => ((float) $match[1] * 16).'px',
            trim($condition),
        ) ?? trim($condition);
        if (preg_match(
            '/^\((min|max)-(width|height):\s*([0-9]+(?:\.[0-9]+)?)(dp|px)\)$/Di',
            $condition,
            $match,
        ) === 1) {
            return self::number(
                self::feature($match[2]),
                $match[1] === 'min'
                    ? StyleQueryOperator::GreaterThanOrEqual
                    : StyleQueryOperator::LessThanOrEqual,
                (float) $match[3],
                strtolower($match[4]),
            );
        }
        if (preg_match(
            '/^\((width|height|refresh-rate|memory-class|performance-tier)\s*(>=|<=|>|<|=)\s*([0-9]+(?:\.[0-9]+)?)(dp|px|hz|mb)?\)$/Di',
            $condition,
            $match,
        ) === 1) {
            return self::number(
                self::feature($match[1]),
                self::operator($match[2]),
                (float) $match[3],
                strtolower($match[4] !== '' ? $match[4] : 'number'),
            );
        }
        if (preg_match(
            '/^\((orientation|prefers-color-scheme|prefers-reduced-motion|pointer|device-type|dynamic-range|display-mode|fold-posture|input-mode):\s*([a-z][a-z0-9-]*)\)$/Di',
            $condition,
            $match,
        ) === 1) {
            return [
                'feature' => self::feature($match[1])->value,
                'operator' => StyleQueryOperator::Equal->value,
                'valueKind' => StyleQueryValueKind::Keyword->value,
                'number' => null,
                'keyword' => strtolower($match[2]),
                'unit' => null,
            ];
        }

        throw new RuntimeException("Unsupported native style query {$condition} in {$name}.");
    }

    /** @param array<string, float|int|string|bool|null> $environment */
    public static function matches(array $query, array $environment): bool
    {
        if (isset($query['op'])) {
            $children = is_array($query['children'] ?? null) ? $query['children'] : [];
            $results = array_map(
                static fn (mixed $child): bool => is_array($child) && self::matches($child, $environment),
                $children,
            );
            return match ($query['op']) {
                'and' => !in_array(false, $results, true),
                'or' => in_array(true, $results, true),
                'not' => $results !== [] && !$results[0],
                default => false,
            };
        }
        $feature = StyleQueryFeature::tryFrom((int) ($query['feature'] ?? 0));
        $operator = StyleQueryOperator::tryFrom((int) ($query['operator'] ?? 0));
        $kind = StyleQueryValueKind::tryFrom((int) ($query['valueKind'] ?? 0));
        if ($feature === null || $operator === null || $kind === null) {
            return false;
        }
        $actual = $environment[self::environmentKey($feature)] ?? null;
        if ($kind === StyleQueryValueKind::Keyword) {
            return is_string($actual)
                && $operator === StyleQueryOperator::Equal
                && strtolower($actual) === ($query['keyword'] ?? null);
        }
        if (!is_int($actual) && !is_float($actual)) {
            return false;
        }
        $expected = $query['number'] ?? null;
        if (!is_int($expected) && !is_float($expected)) {
            return false;
        }

        return match ($operator) {
            StyleQueryOperator::Equal => (float) $actual === (float) $expected,
            StyleQueryOperator::GreaterThanOrEqual => $actual >= $expected,
            StyleQueryOperator::LessThanOrEqual => $actual <= $expected,
            StyleQueryOperator::GreaterThan => $actual > $expected,
            StyleQueryOperator::LessThan => $actual < $expected,
        };
    }

    /** @return array{feature:int,operator:int,valueKind:int,number:float,keyword:null,unit:string} */
    private static function number(
        StyleQueryFeature $feature,
        StyleQueryOperator $operator,
        float $number,
        string $unit,
    ): array {
        return [
            'feature' => $feature->value,
            'operator' => $operator->value,
            'valueKind' => StyleQueryValueKind::Number->value,
            'number' => $number,
            'keyword' => null,
            'unit' => $unit,
        ];
    }

    private static function operator(string $operator): StyleQueryOperator
    {
        return match ($operator) {
            '=' => StyleQueryOperator::Equal,
            '>=' => StyleQueryOperator::GreaterThanOrEqual,
            '<=' => StyleQueryOperator::LessThanOrEqual,
            '>' => StyleQueryOperator::GreaterThan,
            '<' => StyleQueryOperator::LessThan,
        };
    }

    private static function feature(string $feature): StyleQueryFeature
    {
        return match (strtolower($feature)) {
            'width' => StyleQueryFeature::Width,
            'height' => StyleQueryFeature::Height,
            'orientation' => StyleQueryFeature::Orientation,
            'prefers-color-scheme' => StyleQueryFeature::ColorScheme,
            'prefers-reduced-motion' => StyleQueryFeature::ReducedMotion,
            'pointer' => StyleQueryFeature::Pointer,
            'device-type' => StyleQueryFeature::DeviceType,
            'refresh-rate' => StyleQueryFeature::RefreshRate,
            'dynamic-range' => StyleQueryFeature::DynamicRange,
            'display-mode' => StyleQueryFeature::DisplayMode,
            'fold-posture' => StyleQueryFeature::FoldPosture,
            'input-mode' => StyleQueryFeature::InputMode,
            'memory-class' => StyleQueryFeature::MemoryClass,
            'performance-tier' => StyleQueryFeature::PerformanceTier,
            default => throw new RuntimeException("Unknown native style query feature {$feature}."),
        };
    }

    private static function environmentKey(StyleQueryFeature $feature): string
    {
        return match ($feature) {
            StyleQueryFeature::Width => 'width',
            StyleQueryFeature::Height => 'height',
            StyleQueryFeature::Orientation => 'orientation',
            StyleQueryFeature::ColorScheme => 'colorScheme',
            StyleQueryFeature::ReducedMotion => 'reducedMotion',
            StyleQueryFeature::Pointer => 'pointer',
            StyleQueryFeature::DeviceType => 'deviceType',
            StyleQueryFeature::RefreshRate => 'refreshRate',
            StyleQueryFeature::DynamicRange => 'dynamicRange',
            StyleQueryFeature::DisplayMode => 'displayMode',
            StyleQueryFeature::FoldPosture => 'foldPosture',
            StyleQueryFeature::InputMode => 'inputMode',
            StyleQueryFeature::MemoryClass => 'memoryClass',
            StyleQueryFeature::PerformanceTier => 'performanceTier',
        };
    }
}
