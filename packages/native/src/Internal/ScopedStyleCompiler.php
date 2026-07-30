<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

use RuntimeException;

/**
 * Compiles the intentionally small, native-safe CSS subset accepted by
 * `<style>` blocks in .pam.php components.
 */
final class ScopedStyleCompiler
{
    /** @var array<string, string> */
    private const PROPERTIES = [
        'align-items' => 'alignItems',
        'align-self' => 'alignSelf',
        'aspect-ratio' => 'aspectRatio',
        'background' => 'backgroundColor',
        'background-color' => 'backgroundColor',
        'border-bottom-left-radius' => 'borderBottomLeftRadius',
        'border-bottom-right-radius' => 'borderBottomRightRadius',
        'border-bottom-width' => 'borderBottomWidth',
        'border-color' => 'borderColor',
        'border-radius' => 'borderRadius',
        'border-top-left-radius' => 'borderTopLeftRadius',
        'border-top-right-radius' => 'borderTopRightRadius',
        'border-top-width' => 'borderTopWidth',
        'border-left-width' => 'borderLeftWidth',
        'border-right-width' => 'borderRightWidth',
        'border-width' => 'borderWidth',
        'bottom' => 'bottom',
        'color' => 'textColor',
        'display' => 'visible',
        'elevation' => 'elevation',
        'flex-grow' => 'flexGrow',
        'flex-shrink' => 'flexShrink',
        'flex-direction' => 'flexDirection',
        'font-family' => 'fontFamily',
        'font-size' => 'fontSize',
        'font-style' => 'fontStyle',
        'font-weight' => 'fontWeight',
        'gap' => 'gap',
        'height' => 'height',
        'justify-content' => 'justifyContent',
        'left' => 'left',
        'letter-spacing' => 'letterSpacing',
        'line-height' => 'lineHeight',
        'margin-bottom' => 'marginBottom',
        'margin-left' => 'marginLeft',
        'margin-right' => 'marginRight',
        'margin-top' => 'marginTop',
        'max-height' => 'maxHeight',
        'max-width' => 'maxWidth',
        'min-height' => 'minHeight',
        'min-width' => 'minWidth',
        'opacity' => 'opacity',
        'overflow' => 'overflow',
        'padding-bottom' => 'paddingBottom',
        'padding-left' => 'paddingLeft',
        'padding-right' => 'paddingRight',
        'padding-top' => 'paddingTop',
        'position' => 'position',
        'right' => 'right',
        'text-align' => 'textAlign',
        'text-transform' => 'textTransform',
        'top' => 'top',
        'width' => 'width',
        'z-index' => 'zIndex',
    ];

    private function __construct()
    {
    }

    /**
     * @return array{
     *     classes: array<string, array<string, string|bool>>,
     *     tags: array<string, array<string, string|bool>>
     * }
     */
    public static function compile(string $source, string $name): array
    {
        $clean = preg_replace('/\/\*[\s\S]*?\*\//', '', $source);
        if (!is_string($clean)) {
            throw new RuntimeException("Cannot parse styles in {$name}.");
        }
        $variables = [];
        $classes = [];
        $tags = [];
        $rules = [];
        $matchedBytes = 0;
        preg_match_all(
            '/([^{}]+)\{([^{}]*)\}/',
            $clean,
            $blocks,
            PREG_SET_ORDER | PREG_OFFSET_CAPTURE,
        );
        foreach ($blocks as $block) {
            $selectorSource = trim($block[1][0]);
            $body = $block[2][0];
            $offset = $block[0][1];
            if (trim(substr($clean, $matchedBytes, $offset - $matchedBytes)) !== '') {
                throw new RuntimeException("Unsupported nested CSS in {$name}.");
            }
            $matchedBytes = $offset + strlen($block[0][0]);
            $rules[] = [$selectorSource, $body];
        }
        if (trim(substr($clean, $matchedBytes)) !== '') {
            throw new RuntimeException("Invalid CSS after the last rule in {$name}.");
        }
        foreach ($rules as [$selectorSource, $body]) {
            if ($selectorSource === ':root') {
                foreach (self::rawDeclarations($body, $name) as $property => $value) {
                    if (!str_starts_with($property, '--')) {
                        throw new RuntimeException(
                            ":root in {$name} may only declare --custom-properties.",
                        );
                    }
                    $variables[$property] = self::unquote(trim($value));
                }
            }
        }
        foreach ($rules as [$selectorSource, $body]) {
            if ($selectorSource === ':root') {
                continue;
            }
            $declarations = self::declarations($body, $variables, $name);
            foreach (array_map('trim', explode(',', $selectorSource)) as $selector) {
                if (preg_match('/^\.([A-Za-z_][A-Za-z0-9_-]*)$/D', $selector, $match) === 1) {
                    $classes[$match[1]] = [
                        ...($classes[$match[1]] ?? []),
                        ...$declarations,
                    ];
                    continue;
                }
                if (preg_match('/^[A-Za-z][A-Za-z0-9_.-]*$/D', $selector) === 1) {
                    $tags[$selector] = [
                        ...($tags[$selector] ?? []),
                        ...$declarations,
                    ];
                    continue;
                }
                throw new RuntimeException(
                    "Unsupported scoped selector {$selector} in {$name}; use a tag or .class.",
                );
            }
        }

        return ['classes' => $classes, 'tags' => $tags];
    }

    /**
     * @param array<string, string> $variables
     * @return array<string, string|bool>
     */
    private static function declarations(
        string $source,
        array $variables,
        string $name,
    ): array {
        $output = [];
        foreach (self::rawDeclarations($source, $name) as $property => $rawValue) {
            if (str_starts_with($property, '--')) {
                throw new RuntimeException(
                    "Custom properties in {$name} must be declared in :root.",
                );
            }
            $value = preg_replace_callback(
                '/var\(\s*(--[A-Za-z0-9_-]+)\s*\)/',
                static function (array $match) use ($variables, $name): string {
                    if (!array_key_exists($match[1], $variables)) {
                        throw new RuntimeException(
                            "Unknown CSS variable {$match[1]} in {$name}.",
                        );
                    }
                    return $variables[$match[1]];
                },
                trim($rawValue),
            );
            if (!is_string($value)) {
                throw new RuntimeException("Cannot resolve CSS value in {$name}.");
            }
            if (in_array($property, ['padding', 'margin'], true)) {
                self::expandBox($output, $property, $value, $name);
                continue;
            }
            if ($property === 'border') {
                self::expandBorder($output, $value, $name);
                continue;
            }
            if ($property === 'flex') {
                $output['flexGrow'] = self::scalar($value, $name);
                $output['flexShrink'] = '1';
                continue;
            }
            $attribute = self::PROPERTIES[$property] ?? null;
            if ($attribute === null) {
                throw new RuntimeException(
                    "Unsupported native CSS property {$property} in {$name}.",
                );
            }
            if (
                str_ends_with(trim($value), '%')
                && in_array($property, ['width', 'height', 'max-width', 'max-height'], true)
            ) {
                $attribute = match ($property) {
                    'width' => 'widthPercent',
                    'height' => 'heightPercent',
                    'max-width' => 'maxWidthPercent',
                    'max-height' => 'maxHeightPercent',
                };
                $output[$attribute] = self::percentage($value, $name);
                continue;
            }
            $output[$attribute] = self::propertyValue($property, $value, $name);
        }

        return $output;
    }

    /** @return array<string, string> */
    private static function rawDeclarations(string $source, string $name): array
    {
        $output = [];
        foreach (explode(';', $source) as $declaration) {
            if (trim($declaration) === '') {
                continue;
            }
            $parts = explode(':', $declaration, 2);
            if (count($parts) !== 2 || trim($parts[0]) === '') {
                throw new RuntimeException("Invalid CSS declaration in {$name}.");
            }
            $property = strtolower(trim($parts[0]));
            if (array_key_exists($property, $output)) {
                throw new RuntimeException(
                    "Duplicate CSS property {$property} in one rule in {$name}.",
                );
            }
            $output[$property] = trim($parts[1]);
        }

        return $output;
    }

    /** @param array<string, string|bool> $output */
    private static function expandBox(
        array &$output,
        string $property,
        string $value,
        string $name,
    ): void {
        $parts = preg_split('/\s+/', trim($value)) ?: [];
        if ($parts === [] || count($parts) > 4) {
            throw new RuntimeException("Invalid {$property} shorthand in {$name}.");
        }
        [$top, $right, $bottom, $left] = match (count($parts)) {
            1 => [$parts[0], $parts[0], $parts[0], $parts[0]],
            2 => [$parts[0], $parts[1], $parts[0], $parts[1]],
            3 => [$parts[0], $parts[1], $parts[2], $parts[1]],
            4 => $parts,
        };
        foreach ([
            'Top' => $top,
            'Right' => $right,
            'Bottom' => $bottom,
            'Left' => $left,
        ] as $edge => $edgeValue) {
            $output[$property.$edge] = self::scalar($edgeValue, $name);
        }
    }

    /** @param array<string, string|bool> $output */
    private static function expandBorder(
        array &$output,
        string $value,
        string $name,
    ): void {
        $parts = preg_split('/\s+/', trim($value)) ?: [];
        if (count($parts) !== 3 || strtolower($parts[1]) !== 'solid') {
            throw new RuntimeException(
                "Native border shorthand in {$name} must be '<width> solid <color>'.",
            );
        }
        $output['borderWidth'] = self::scalar($parts[0], $name);
        $output['borderColor'] = $parts[2];
    }

    private static function propertyValue(
        string $property,
        string $value,
        string $name,
    ): string|bool {
        if ($property === 'display') {
            return match (strtolower($value)) {
                'none' => false,
                'flex' => true,
                default => throw new RuntimeException(
                    "Native display in {$name} supports only flex or none.",
                ),
            };
        }
        if ($property === 'font-family') {
            return self::unquote($value);
        }
        if ($property === 'font-style') {
            return match (strtolower($value)) {
                'normal' => 'normal',
                'italic' => 'italic',
                default => throw new RuntimeException("Invalid font-style in {$name}."),
            };
        }
        if (in_array($property, [
            'background',
            'background-color',
            'border-color',
            'color',
            'align-items',
            'align-self',
            'justify-content',
            'overflow',
            'position',
            'flex-direction',
            'text-align',
            'text-transform',
        ], true)) {
            return self::unquote($value);
        }

        return self::scalar($value, $name);
    }

    private static function scalar(string $value, string $name): string
    {
        $trimmed = trim($value);
        if (preg_match('/^-?(?:\d+|\d*\.\d+)(?:px|dp|pt)?$/D', $trimmed) !== 1) {
            throw new RuntimeException("Expected a native numeric CSS value in {$name}, got {$value}.");
        }

        return preg_replace('/(?:px|dp|pt)$/', '', $trimmed) ?? $trimmed;
    }

    private static function percentage(string $value, string $name): string
    {
        $trimmed = trim($value);
        if (preg_match('/^-?(?:\d+|\d*\.\d+)%$/D', $trimmed) !== 1) {
            throw new RuntimeException("Expected a CSS percentage in {$name}, got {$value}.");
        }

        return substr($trimmed, 0, -1);
    }

    private static function unquote(string $value): string
    {
        $trimmed = trim($value);
        if (
            strlen($trimmed) >= 2
            && (($trimmed[0] === '"' && str_ends_with($trimmed, '"'))
                || ($trimmed[0] === "'" && str_ends_with($trimmed, "'")))
        ) {
            return substr($trimmed, 1, -1);
        }

        return $trimmed;
    }
}
