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
    private const MAX_IMPORTED_BYTES = 1_048_576;
    private const MAX_IMPORT_DEPTH = 16;

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
        'border-bottom-color' => 'borderColor',
        'border-color' => 'borderColor',
        'border-radius' => 'borderRadius',
        'border-style' => 'borderStyle',
        'border-top-left-radius' => 'borderTopLeftRadius',
        'border-top-right-radius' => 'borderTopRightRadius',
        'border-top-width' => 'borderTopWidth',
        'border-left-width' => 'borderLeftWidth',
        'border-left-color' => 'borderColor',
        'border-right-width' => 'borderRightWidth',
        'border-right-color' => 'borderColor',
        'border-top-color' => 'borderColor',
        'border-width' => 'borderWidth',
        'bottom' => 'bottom',
        'color' => 'textColor',
        'display' => 'visible',
        'elevation' => 'elevation',
        'flex-grow' => 'flexGrow',
        'flex-shrink' => 'flexShrink',
        'flex-direction' => 'flexDirection',
        'flex-wrap' => 'flexWrap',
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
        'text-decoration' => 'textDecoration',
        'text-transform' => 'textTransform',
        'top' => 'top',
        'translation-x' => 'translationX',
        'translation-y' => 'translationY',
        'width' => 'width',
        'z-index' => 'zIndex',
    ];

    private function __construct()
    {
    }

    /**
     * @return array{
     *     classes: array<string, array<string, string|int|bool>>,
     *     tags: array<string, array<string, string|int|bool>>,
     *     classCascade: array<string, array<string, array{order: int, value: string|int|bool}>>,
     *     fonts: array<string, list<array{source: string, weight: string, style: string}>>
     * }
     */
    public static function compile(string $source, string $name): array
    {
        $source = self::resolveImports($source, $name);
        $language2 = Language2StyleCompiler::extract($source, $name);
        $source = $language2['source'];
        $clean = preg_replace('/\/\*[\s\S]*?\*\//', '', $source);
        if (!is_string($clean)) {
            throw new RuntimeException("Cannot parse styles in {$name}.");
        }
        $variables = [];
        $classes = [];
        $tags = [];
        $fonts = [];
        $classCascade = [];
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
            if ($selectorSource !== '@font-face') {
                continue;
            }
            $face = self::fontFace($body, $variables, $name);
            $fonts[$face['family']][] = [
                'source' => $face['source'],
                'weight' => $face['weight'],
                'style' => $face['style'],
            ];
        }
        $sourceOrder = 0;
        foreach ($rules as [$selectorSource, $body]) {
            if ($selectorSource === ':root') {
                continue;
            }
            if ($selectorSource === '@font-face') {
                continue;
            }
            $declarations = self::declarations($body, $variables, $name);
            foreach (array_map('trim', explode(',', $selectorSource)) as $selector) {
                if (preg_match('/^\.([A-Za-z_][A-Za-z0-9_-]*)$/D', $selector, $match) === 1) {
                    $classes[$match[1]] = [
                        ...($classes[$match[1]] ?? []),
                        ...$declarations,
                    ];
                    foreach ($declarations as $attribute => $value) {
                        $classCascade[$match[1]][$attribute] = [
                            'order' => $sourceOrder,
                            'value' => $value,
                        ];
                    }
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
            $sourceOrder++;
        }

        return [
            'classes' => $classes,
            'tags' => $tags,
            'classCascade' => $classCascade,
            'fonts' => $fonts,
            'tokens' => $language2['tokens'],
            'states' => $language2['states'],
            'recipes' => $language2['recipes'],
            'queries' => $language2['queries'],
            'keyframes' => $language2['keyframes'],
        ];
    }

    /**
     * @param array<string, string> $variables
     * @return array<string, string|int|bool>
     */
    public static function compileDeclarations(
        string $source,
        array $variables,
        string $name,
    ): array {
        return self::declarations($source, $variables, $name);
    }

    public static function resolveImports(string $source, string $name): string
    {
        if (!str_contains($source, '@import')) {
            return $source;
        }
        if (!is_file($name)) {
            throw new RuntimeException(
                "CSS imports in {$name} require a component file path.",
            );
        }
        $component = realpath($name);
        if (!is_string($component)) {
            throw new RuntimeException("Cannot resolve component path {$name}.");
        }
        $root = self::projectRoot(dirname($component));
        $bytes = strlen($source);
        $stack = [];

        return self::expandImports(
            $source,
            dirname($component),
            $root,
            $stack,
            $bytes,
            0,
            $name,
        );
    }

    /**
     * @param array<string, bool> $stack
     */
    private static function expandImports(
        string $source,
        string $directory,
        string $root,
        array &$stack,
        int &$bytes,
        int $depth,
        string $name,
    ): string {
        if ($depth >= self::MAX_IMPORT_DEPTH) {
            throw new RuntimeException(
                "Scoped CSS imports exceed ".self::MAX_IMPORT_DEPTH." levels in {$name}.",
            );
        }
        $source = preg_replace('/\/\*[\s\S]*?\*\//', '', $source);
        if (!is_string($source)) {
            throw new RuntimeException("Cannot parse CSS comments in {$name}.");
        }
        $expanded = preg_replace_callback(
            '/@import\s+(?:url\(\s*)?(["\'])([^"\']+)\1\s*\)?\s*;/i',
            static function (array $match) use (
                $directory,
                $root,
                &$stack,
                &$bytes,
                $depth,
                $name,
            ): string {
                $import = trim((string) $match[2]);
                if (
                    $import === ''
                    || str_contains($import, "\0")
                    || str_contains($import, '://')
                    || str_starts_with($import, '/')
                    || strtolower(pathinfo($import, PATHINFO_EXTENSION)) !== 'css'
                ) {
                    throw new RuntimeException(
                        "CSS import {$import} in {$name} must be a relative .css file.",
                    );
                }
                $path = realpath($directory.DIRECTORY_SEPARATOR.$import);
                if (
                    !is_string($path)
                    || !is_file($path)
                    || !self::inside($path, $root)
                ) {
                    throw new RuntimeException(
                        "CSS import {$import} in {$name} is missing or outside the project.",
                    );
                }
                if (isset($stack[$path])) {
                    throw new RuntimeException(
                        "Circular CSS import {$import} in {$name}.",
                    );
                }
                $contents = file_get_contents($path);
                if ($contents === false) {
                    throw new RuntimeException("Cannot read CSS import {$path}.");
                }
                $bytes += strlen($contents);
                if ($bytes > self::MAX_IMPORTED_BYTES) {
                    throw new RuntimeException(
                        "Scoped CSS imports exceed one megabyte in {$name}.",
                    );
                }
                $stack[$path] = true;
                try {
                    return self::expandImports(
                        $contents,
                        dirname($path),
                        $root,
                        $stack,
                        $bytes,
                        $depth + 1,
                        $path,
                    );
                } finally {
                    unset($stack[$path]);
                }
            },
            $source,
        );
        if (!is_string($expanded)) {
            throw new RuntimeException("Cannot expand CSS imports in {$name}.");
        }
        if (str_contains($expanded, '@import')) {
            throw new RuntimeException(
                "Invalid CSS import syntax in {$name}; use @import \"relative.css\".",
            );
        }

        return $expanded;
    }

    private static function projectRoot(string $directory): string
    {
        $current = $directory;
        while (true) {
            if (is_file($current.DIRECTORY_SEPARATOR.'composer.json')) {
                return $current;
            }
            $parent = dirname($current);
            if ($parent === $current) {
                return $directory;
            }
            $current = $parent;
        }
    }

    private static function inside(string $path, string $root): bool
    {
        $root = rtrim($root, DIRECTORY_SEPARATOR).DIRECTORY_SEPARATOR;

        return str_starts_with($path, $root);
    }

    /**
     * @param array<string, string> $variables
     * @return array{family: string, source: string, weight: string, style: string}
     */
    private static function fontFace(
        string $source,
        array $variables,
        string $name,
    ): array {
        $declarations = self::rawDeclarations($source, $name);
        foreach (array_keys($declarations) as $property) {
            if (!in_array($property, ['font-family', 'src', 'font-weight', 'font-style'], true)) {
                throw new RuntimeException(
                    "Unsupported @font-face property {$property} in {$name}.",
                );
            }
        }
        $family = self::unquote(
            self::resolveVariables($declarations['font-family'] ?? '', $variables, $name),
        );
        if ($family === '') {
            throw new RuntimeException("@font-face in {$name} requires font-family.");
        }
        $rawSource = self::resolveVariables($declarations['src'] ?? '', $variables, $name);
        if (
            preg_match(
                '/^url\(\s*(?:"([^"]+)"|\'([^\']+)\'|([^\'")\s]+))\s*\)$/D',
                trim($rawSource),
                $match,
            ) !== 1
        ) {
            throw new RuntimeException(
                "@font-face src in {$name} must be one url(asset://…ttf|otf).",
            );
        }
        $fontSource = (string) ($match[1] !== ''
            ? $match[1]
            : ($match[2] !== '' ? $match[2] : $match[3]));
        if (
            preg_match(
                '/^asset:\/\/[A-Za-z0-9_.\/-]+\.(?:ttf|otf)$/Di',
                $fontSource,
            ) !== 1
            || str_contains($fontSource, '..')
        ) {
            throw new RuntimeException(
                "@font-face src in {$name} must reference a safe packaged TTF or OTF asset.",
            );
        }
        $weight = self::scalar(
            match (strtolower(self::resolveVariables(
                $declarations['font-weight'] ?? '400',
                $variables,
                $name,
            ))) {
                'normal' => '400',
                'bold' => '700',
                default => self::resolveVariables(
                    $declarations['font-weight'] ?? '400',
                    $variables,
                    $name,
                ),
            },
            $name,
        );
        $numericWeight = (int) $weight;
        if (
            (string) $numericWeight !== $weight
            || $numericWeight < 100
            || $numericWeight > 900
            || $numericWeight % 100 !== 0
        ) {
            throw new RuntimeException(
                "@font-face font-weight in {$name} must be 100, 200, …, or 900.",
            );
        }
        $style = strtolower(self::unquote(
            self::resolveVariables($declarations['font-style'] ?? 'normal', $variables, $name),
        ));
        if (!in_array($style, ['normal', 'italic'], true)) {
            throw new RuntimeException(
                "@font-face font-style in {$name} supports only normal or italic.",
            );
        }

        return [
            'family' => $family,
            'source' => $fontSource,
            'weight' => $weight,
            'style' => $style,
        ];
    }

    /**
     * @param array<string, string> $variables
     * @return array<string, string|int|bool>
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
            $value = self::resolveVariables($rawValue, $variables, $name);
            if (in_array($property, ['padding', 'margin'], true)) {
                self::expandBox($output, $property, $value, $name);
                continue;
            }
            if (in_array($property, [
                'padding-inline',
                'padding-block',
                'margin-inline',
                'margin-block',
                'inset-inline',
                'inset-block',
            ], true)) {
                self::expandLogicalBox($output, $property, $value, $name);
                continue;
            }
            if ($property === 'inset') {
                self::expandInset($output, $value, $name);
                continue;
            }
            if ($property === 'border') {
                self::expandBorder($output, $value, $name);
                continue;
            }
            if ($property === 'border-radius') {
                self::expandBorderRadius($output, $value, $name);
                continue;
            }
            if (preg_match('/^border-(top|right|bottom|left)$/D', $property, $match) === 1) {
                self::expandBorder($output, $value, $name, ucfirst($match[1]));
                continue;
            }
            if ($property === 'flex') {
                $output['flexGrow'] = self::scalar($value, $name);
                $output['flexShrink'] = '1';
                continue;
            }
            if ($property === 'transform') {
                self::expandTransform($output, $value, $name);
                continue;
            }
            if ($property === 'box-shadow') {
                self::expandBoxShadow($output, $value, $name);
                continue;
            }
            if ($property === 'object-fit') {
                $output['resizeMode'] = match (strtolower(trim($value))) {
                    'contain', 'cover', 'fill' => strtolower(trim($value)),
                    'none' => 'center',
                    'scale-down' => 'contain',
                    default => throw new RuntimeException(
                        "Unsupported object-fit value {$value} in {$name}.",
                    ),
                };
                continue;
            }
            if ($property === 'visibility') {
                $output['visible'] = match (strtolower(trim($value))) {
                    'visible' => true,
                    'hidden', 'collapse' => false,
                    default => throw new RuntimeException(
                        "Unsupported visibility value {$value} in {$name}.",
                    ),
                };
                continue;
            }
            if ($property === 'box-sizing') {
                if (strtolower(trim($value)) !== 'border-box') {
                    throw new RuntimeException(
                        "Pam Native uses border-box layout; content-box is unsupported in {$name}.",
                    );
                }
                continue;
            }
            if ($property === 'border-style') {
                $output['borderStyle'] = match (strtolower(trim($value))) {
                    'solid' => 1,
                    'dashed' => 2,
                    'dotted' => 3,
                    default => throw new RuntimeException(
                        "Unsupported border-style value {$value} in {$name}.",
                    ),
                };
                continue;
            }
            if (in_array($property, ['row-gap', 'column-gap'], true)) {
                $output[$property === 'column-gap' ? 'gridColumnGap' : 'gridRowGap'] = self::scalar($value, $name);
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
                && in_array($property, [
                    'width',
                    'height',
                    'max-width',
                    'max-height',
                    'left',
                    'top',
                    'right',
                    'bottom',
                ], true)
            ) {
                $attribute = match ($property) {
                    'width' => 'widthPercent',
                    'height' => 'heightPercent',
                    'max-width' => 'maxWidthPercent',
                    'max-height' => 'maxHeightPercent',
                    'left' => 'leftPercent',
                    'top' => 'topPercent',
                    'right' => 'rightPercent',
                    'bottom' => 'bottomPercent',
                };
                $output[$attribute] = self::percentage($value, $name);
                continue;
            }
            $output[$attribute] = self::propertyValue($property, $value, $name);
        }

        return $output;
    }

    /**
     * @param array<string, string> $variables
     * @param list<string> $stack
     */
    private static function resolveVariables(
        string $value,
        array $variables,
        string $name,
        array $stack = [],
    ): string {
        if (count($stack) > 32) {
            throw new RuntimeException("CSS variable expansion is too deep in {$name}.");
        }
        $resolved = trim($value);
        while (($start = strpos($resolved, 'var(')) !== false) {
            $end = self::matchingParenthesis($resolved, $start + 3, $name);
            $body = substr($resolved, $start + 4, $end - $start - 4);
            [$variable, $fallback] = self::variableParts($body);
            if (preg_match('/^--[A-Za-z0-9_-]+$/D', $variable) !== 1) {
                throw new RuntimeException("Invalid CSS variable {$variable} in {$name}.");
            }
            if (in_array($variable, $stack, true)) {
                throw new RuntimeException("Circular CSS variable {$variable} in {$name}.");
            }
            if (array_key_exists($variable, $variables)) {
                $replacement = self::resolveVariables(
                    $variables[$variable],
                    $variables,
                    $name,
                    [...$stack, $variable],
                );
            } elseif ($fallback !== null) {
                $replacement = self::resolveVariables($fallback, $variables, $name, $stack);
            } else {
                throw new RuntimeException("Unknown CSS variable {$variable} in {$name}.");
            }
            $resolved = substr($resolved, 0, $start)
                .$replacement
                .substr($resolved, $end + 1);
        }

        return $resolved;
    }

    private static function matchingParenthesis(
        string $value,
        int $open,
        string $name,
    ): int {
        $depth = 0;
        $length = strlen($value);
        for ($index = $open; $index < $length; $index++) {
            if ($value[$index] === '(') {
                $depth++;
            } elseif ($value[$index] === ')') {
                $depth--;
                if ($depth === 0) {
                    return $index;
                }
            }
        }

        throw new RuntimeException("Unclosed CSS var() in {$name}.");
    }

    /** @return array{string, ?string} */
    private static function variableParts(string $body): array
    {
        $depth = 0;
        $length = strlen($body);
        for ($index = 0; $index < $length; $index++) {
            if ($body[$index] === '(') {
                $depth++;
            } elseif ($body[$index] === ')') {
                $depth--;
            } elseif ($body[$index] === ',' && $depth === 0) {
                return [
                    trim(substr($body, 0, $index)),
                    trim(substr($body, $index + 1)),
                ];
            }
        }

        return [trim($body), null];
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

    /** @param array<string, string|int|bool> $output */
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
    private static function expandInset(
        array &$output,
        string $value,
        string $name,
    ): void {
        $parts = preg_split('/\s+/', trim($value)) ?: [];
        if ($parts === [] || count($parts) > 4) {
            throw new RuntimeException("Invalid inset shorthand in {$name}.");
        }
        [$top, $right, $bottom, $left] = match (count($parts)) {
            1 => [$parts[0], $parts[0], $parts[0], $parts[0]],
            2 => [$parts[0], $parts[1], $parts[0], $parts[1]],
            3 => [$parts[0], $parts[1], $parts[2], $parts[1]],
            4 => $parts,
        };
        foreach ([
            'top' => $top,
            'right' => $right,
            'bottom' => $bottom,
            'left' => $left,
        ] as $edge => $edgeValue) {
            $output[$edge] = self::scalar($edgeValue, $name);
        }
    }

    /** @param array<string, string|int|bool> $output */
    private static function expandLogicalBox(
        array &$output,
        string $property,
        string $value,
        string $name,
    ): void {
        $parts = preg_split('/\s+/', trim($value)) ?: [];
        if ($parts === [] || count($parts) > 2) {
            throw new RuntimeException("Invalid {$property} shorthand in {$name}.");
        }
        $start = self::scalar($parts[0], $name);
        $end = self::scalar($parts[1] ?? $parts[0], $name);
        [$first, $second] = match ($property) {
            'padding-inline' => ['paddingLeft', 'paddingRight'],
            'padding-block' => ['paddingTop', 'paddingBottom'],
            'margin-inline' => ['marginLeft', 'marginRight'],
            'margin-block' => ['marginTop', 'marginBottom'],
            'inset-inline' => ['left', 'right'],
            'inset-block' => ['top', 'bottom'],
        };
        $output[$first] = $start;
        $output[$second] = $end;
    }

    /** @param array<string, string|int|bool> $output */
    private static function expandBorder(
        array &$output,
        string $value,
        string $name,
        string $edge = '',
    ): void {
        $parts = preg_split('/\s+/', trim($value)) ?: [];
        if (
            in_array(strtolower(trim($value)), ['none', '0', '0px', '0dp', '0pt'], true)
        ) {
            $output[$edge === '' ? 'borderWidth' : 'border'.$edge.'Width'] = '0';
            if ($edge === '') {
                $output['borderColor'] = 0;
            }
            return;
        }
        if (count($parts) !== 3 || strtolower($parts[1]) !== 'solid') {
            throw new RuntimeException(
                "Native border shorthand in {$name} must be '<width> solid <color>'.",
            );
        }
        $output[$edge === '' ? 'borderWidth' : 'border'.$edge.'Width'] =
            self::scalar($parts[0], $name);
        $output['borderColor'] = CssColor::parse($parts[2], "Border color in {$name}");
    }

    /** @param array<string, string|int|bool> $output */
    private static function expandBorderRadius(
        array &$output,
        string $value,
        string $name,
    ): void {
        if (str_contains($value, '/')) {
            throw new RuntimeException(
                "Elliptical border-radius is not supported in {$name}.",
            );
        }
        $parts = preg_split('/\s+/', trim($value)) ?: [];
        if ($parts === [] || count($parts) > 4) {
            throw new RuntimeException("Invalid border-radius shorthand in {$name}.");
        }
        if (count($parts) === 1) {
            $output['borderRadius'] = self::scalar($parts[0], $name);
            return;
        }
        [$topLeft, $topRight, $bottomRight, $bottomLeft] = match (count($parts)) {
            2 => [$parts[0], $parts[1], $parts[0], $parts[1]],
            3 => [$parts[0], $parts[1], $parts[2], $parts[1]],
            4 => $parts,
        };
        foreach ([
            'borderTopLeftRadius' => $topLeft,
            'borderTopRightRadius' => $topRight,
            'borderBottomRightRadius' => $bottomRight,
            'borderBottomLeftRadius' => $bottomLeft,
        ] as $attribute => $cornerValue) {
            $output[$attribute] = self::scalar($cornerValue, $name);
        }
    }

    /** @param array<string, string|int|bool> $output */
    private static function expandBoxShadow(
        array &$output,
        string $value,
        string $name,
    ): void {
        $trimmed = trim($value);
        if (strtolower($trimmed) === 'none') {
            $output['shadowOffsetX'] = '0';
            $output['shadowOffsetY'] = '0';
            $output['shadowBlurRadius'] = '0';
            $output['shadowSpreadRadius'] = '0';
            $output['shadowColor'] = 0;

            return;
        }
        if (self::containsTopLevelComma($trimmed)) {
            throw new RuntimeException(
                "Native box-shadow in {$name} supports one shadow.",
            );
        }
        $parts = self::cssValueParts($trimmed, $name);
        if (in_array('inset', array_map('strtolower', $parts), true)) {
            throw new RuntimeException(
                "Inset box-shadow is not supported in {$name}.",
            );
        }
        $color = CssColor::parse('rgba(0, 0, 0, 0.33)', "Box shadow in {$name}");
        $colorParts = array_values(array_filter(
            $parts,
            static fn (string $part): bool => !self::isScalarToken($part),
        ));
        if (count($colorParts) > 1) {
            throw new RuntimeException("Invalid box-shadow color in {$name}.");
        }
        if ($colorParts !== []) {
            $color = CssColor::parse($colorParts[0], "Box shadow color in {$name}");
            $parts = array_values(array_filter(
                $parts,
                static fn (string $part): bool => self::isScalarToken($part),
            ));
        }
        if (count($parts) < 2 || count($parts) > 4) {
            throw new RuntimeException(
                "Native box-shadow in {$name} expects x-offset, y-offset, optional blur/spread, and an optional color.",
            );
        }
        $numbers = array_map(
            static fn (string $part): string => self::scalar($part, $name),
            $parts,
        );
        $output['shadowOffsetX'] = $numbers[0];
        $output['shadowOffsetY'] = $numbers[1];
        $output['shadowBlurRadius'] = $numbers[2] ?? '0';
        $output['shadowSpreadRadius'] = $numbers[3] ?? '0';
        $output['shadowColor'] = $color;
    }

    /** @return list<string> */
    private static function cssValueParts(string $value, string $name): array
    {
        $parts = [];
        $start = null;
        $depth = 0;
        $length = strlen($value);
        for ($index = 0; $index < $length; $index++) {
            $character = $value[$index];
            if ($character === '(') {
                $depth++;
            } elseif ($character === ')') {
                $depth--;
                if ($depth < 0) {
                    throw new RuntimeException("Invalid CSS value in {$name}.");
                }
            }
            if (self::isAsciiWhitespace($character) && $depth === 0) {
                if ($start !== null) {
                    $parts[] = substr($value, $start, $index - $start);
                    $start = null;
                }
            } elseif ($start === null) {
                $start = $index;
            }
        }
        if ($depth !== 0) {
            throw new RuntimeException("Invalid CSS value in {$name}.");
        }
        if ($start !== null) {
            $parts[] = substr($value, $start);
        }

        return $parts;
    }

    private static function isAsciiWhitespace(string $character): bool
    {
        return $character === ' '
            || $character === "\t"
            || $character === "\n"
            || $character === "\r"
            || $character === "\f"
            || $character === "\v";
    }

    private static function containsTopLevelComma(string $value): bool
    {
        $depth = 0;
        $length = strlen($value);
        for ($index = 0; $index < $length; $index++) {
            if ($value[$index] === '(') {
                $depth++;
            } elseif ($value[$index] === ')') {
                $depth--;
            } elseif ($value[$index] === ',' && $depth === 0) {
                return true;
            }
        }

        return false;
    }

    private static function isScalarToken(string $value): bool
    {
        return preg_match(
            '/^-?(?:\d+(?:\.\d+)?|\.\d+)(?:px|dp|pt|rem)?$/iD',
            trim($value),
        ) === 1;
    }

    private static function propertyValue(
        string $property,
        string $value,
        string $name,
    ): string|int|bool {
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
            return self::fontFamily($value, $name);
        }
        if ($property === 'font-style') {
            return match (strtolower($value)) {
                'normal' => 'normal',
                'italic' => 'italic',
                default => throw new RuntimeException("Invalid font-style in {$name}."),
            };
        }
        if ($property === 'font-weight') {
            $weight = match (strtolower(trim($value))) {
                'normal' => '400',
                'bold' => '700',
                default => self::scalar($value, $name),
            };
            if ((float) $weight < 1 || (float) $weight > 1000) {
                throw new RuntimeException("Invalid font-weight in {$name}.");
            }

            return $weight;
        }
        if (in_array($property, [
            'background',
            'background-color',
            'border-color',
            'border-top-color',
            'border-right-color',
            'border-bottom-color',
            'border-left-color',
            'color',
        ], true)) {
            if (
                $property === 'background'
                && strtolower(trim($value)) === 'none'
            ) {
                return 0;
            }
            return CssColor::parse($value, "{$property} in {$name}");
        }
        if ($property === 'opacity' && str_ends_with(trim($value), '%')) {
            return (string) (self::percentage($value, $name) / 100);
        }
        if ($property === 'aspect-ratio' && str_contains($value, '/')) {
            $parts = array_map('trim', explode('/', $value));
            if (
                count($parts) !== 2
                || (float) self::scalar($parts[1], $name) === 0.0
            ) {
                throw new RuntimeException("Invalid aspect-ratio in {$name}.");
            }

            return (string) (
                (float) self::scalar($parts[0], $name)
                / (float) self::scalar($parts[1], $name)
            );
        }
        if ($property === 'overflow') {
            return match (strtolower(trim($value))) {
                'visible', 'hidden' => strtolower(trim($value)),
                'clip' => 'hidden',
                default => throw new RuntimeException(
                    "Native overflow in {$name} supports visible, hidden, or clip.",
                ),
            };
        }
        if ($property === 'position') {
            return match (strtolower(trim($value))) {
                'relative', 'absolute' => strtolower(trim($value)),
                default => throw new RuntimeException(
                    "Native position in {$name} supports relative or absolute.",
                ),
            };
        }
        if ($property === 'text-decoration') {
            $normalized = preg_replace('/\s+/', '-', strtolower(trim($value)))
                ?? strtolower(trim($value));

            return match ($normalized) {
                'none', 'underline', 'line-through',
                'underline-line-through', 'line-through-underline' =>
                    $normalized === 'line-through-underline'
                        ? 'underline-line-through'
                        : $normalized,
                default => throw new RuntimeException(
                    "Unsupported text-decoration in {$name}.",
                ),
            };
        }
        if (in_array($property, [
            'align-items',
            'align-self',
            'justify-content',
            'overflow',
            'position',
            'flex-direction',
            'flex-wrap',
            'text-align',
            'text-decoration',
            'text-transform',
        ], true)) {
            return self::unquote($value);
        }

        return self::scalar($value, $name);
    }

    private static function scalar(string $value, string $name): string
    {
        $trimmed = trim($value);
        if (preg_match('/^-?(?:\d+|\d*\.\d+)(?:px|dp|pt|rem)?$/D', $trimmed) !== 1) {
            throw new RuntimeException("Expected a native numeric CSS value in {$name}, got {$value}.");
        }
        if (str_ends_with($trimmed, 'rem')) {
            return (string) ((float) substr($trimmed, 0, -3) * 16);
        }

        return preg_replace('/(?:px|dp|pt)$/', '', $trimmed) ?? $trimmed;
    }

    /** @param array<string, string|int|bool> $output */
    private static function expandTransform(
        array &$output,
        string $value,
        string $name,
    ): void {
        $remaining = trim($value);
        if ($remaining === 'none') {
            return;
        }
        while ($remaining !== '') {
            if (preg_match('/^([A-Za-z]+)\(([^()]*)\)\s*/D', $remaining, $match) !== 1) {
                throw new RuntimeException("Invalid transform in {$name}: {$value}.");
            }
            $function = strtolower($match[1]);
            $argument = trim($match[2]);
            match ($function) {
                'translatex' => $output['translationX'] = self::scalar($argument, $name),
                'translatey' => $output['translationY'] = self::scalar($argument, $name),
                'scale' => self::setScale($output, $argument, $argument, $name),
                'scalex' => $output['scaleX'] = self::scalar($argument, $name),
                'scaley' => $output['scaleY'] = self::scalar($argument, $name),
                'rotate' => $output['rotation'] = self::angle($argument, $name),
                default => throw new RuntimeException(
                    "Unsupported transform function {$match[1]} in {$name}.",
                ),
            };
            $remaining = ltrim(substr($remaining, strlen($match[0])));
        }
    }

    /** @param array<string, string|int|bool> $output */
    private static function setScale(
        array &$output,
        string $x,
        string $y,
        string $name,
    ): void {
        $output['scaleX'] = self::scalar($x, $name);
        $output['scaleY'] = self::scalar($y, $name);
    }

    private static function angle(string $value, string $name): string
    {
        $trimmed = strtolower(trim($value));
        foreach (['turn' => 360.0, 'grad' => 0.9, 'rad' => 180 / M_PI, 'deg' => 1.0] as $unit => $factor) {
            if (str_ends_with($trimmed, $unit)) {
                $numeric = self::scalar(substr($trimmed, 0, -strlen($unit)), $name);

                return (string) ((float) $numeric * $factor);
            }
        }

        if ((float) self::scalar($trimmed, $name) === 0.0) {
            return '0';
        }

        throw new RuntimeException("Non-zero rotate() requires an angle unit in {$name}.");
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

    private static function fontFamily(string $value, string $name): string
    {
        $families = str_getcsv($value, ',', '"', '\\');
        if ($families === [] || trim((string) $families[0]) === '') {
            throw new RuntimeException("font-family requires at least one family in {$name}.");
        }

        return self::unquote(trim((string) $families[0]));
    }
}
