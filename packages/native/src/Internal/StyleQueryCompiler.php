<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

use Pam\Native\Style\StyleQueryFeature;
use Pam\Native\Style\StyleQueryOperator;
use Pam\Native\Style\StyleQueryValueKind;
use RuntimeException;

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
        $condition = preg_replace('/^[A-Za-z][A-Za-z0-9_-]*\s+(?=\()/', '', $condition)
            ?? $condition;
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
