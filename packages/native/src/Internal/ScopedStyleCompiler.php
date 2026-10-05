<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

use Pam\Native\Style\StyleScope;
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
        '-pam-native-background-color' => 'nativeBackgroundColorResource',
        '-pam-native-text-color' => 'nativeTextColorResource',
        '-pam-native-border-color' => 'nativeBorderColorResource',
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
    private static int $compileDepth = 0;

    public static function compile(
        string $source,
        string $name,
        StyleScope $scope = StyleScope::Scoped,
    ): array
    {
        $source = self::resolveImports($source, $name);
        self::$compileDepth++;
        try {
            return self::compileResolved($source, $name, $scope);
        } catch (CssDiagnostic $diagnostic) {
            if (self::$compileDepth > 1) {
                throw $diagnostic;
            }
            throw new RuntimeException(
                self::locate($source, $diagnostic->selector, $diagnostic->property, $name)
                    .': '.$diagnostic->getMessage(),
                previous: $diagnostic,
            );
        } finally {
            self::$compileDepth--;
        }
    }

    /**
     * Maps a failing declaration back to "<file>:<line>" using the
     * `/*#pam-source <line> <file>*\/` markers inserted while inlining
     * app.css, @import files and component <style> blocks.
     */
    private static function locate(string $source, ?string $selector, string $property, string $name): string
    {
        $offset = null;
        $searchFrom = 0;
        if ($selector !== null && trim($selector) !== '') {
            $pattern = '/(?:^|[\s{},;\/])('
                .(preg_replace('/\\s\+|\s+/', '\\s+', preg_quote(trim($selector), '/')) ?? preg_quote(trim($selector), '/'))
                .')\s*[{,]/';
            if (preg_match($pattern, $source, $match, PREG_OFFSET_CAPTURE) === 1) {
                $offset = $match[1][1];
                $searchFrom = $offset;
            }
        }
        if (preg_match(
            '/(?<![-\w])'.preg_quote($property, '/').'\s*:/i',
            $source,
            $match,
            PREG_OFFSET_CAPTURE,
            $searchFrom,
        ) === 1) {
            $nextRule = strpos($source, '}', $searchFrom);
            if ($selector === null || $nextRule === false || $match[0][1] < $nextRule) {
                $offset = $match[0][1];
            }
        }
        if ($offset === null) {
            return $name;
        }
        $file = $name;
        $line = 1;
        $start = 0;
        if (preg_match_all('/\/\*#pam-source (\d+) ([^*]+)\*\//', $source, $markers, PREG_SET_ORDER | PREG_OFFSET_CAPTURE) > 0) {
            foreach ($markers as $marker) {
                if ($marker[0][1] > $offset) {
                    break;
                }
                $line = (int) $marker[1][0];
                $file = $marker[2][0];
                $start = $marker[0][1] + strlen($marker[0][0]);
            }
        }

        return $file.':'.($line + substr_count(substr($source, $start, $offset - $start), "\n"));
    }

    /** Marks where an inlined CSS chunk starts for diagnostics. */
    public static function sourceMarker(string $file, int $line): string
    {
        return '/*#pam-source '.$line.' '.str_replace('*', '', $file).'*/';
    }

    /** @return array<string, mixed> */
    private static function compileResolved(
        string $source,
        string $name,
        StyleScope $scope,
    ): array {
        $irSource = $source;
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
        $cascadeRules = [];
        $rules = [];
        $variableRules = [];
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
            try {
                $declarations = self::declarations($body, $variables, $name);
            } catch (CssDiagnostic $diagnostic) {
                $diagnostic->selector ??= StyleSelectorCompiler::expand($selectorSource)[0] ?? $selectorSource;
                throw $diagnostic;
            }
            foreach (StyleSelectorCompiler::expand($selectorSource) as $selector) {
                $compiledSelector = StyleSelectorCompiler::compile($selector, $name);
                $important = self::importantProperties($body, $variables);
                $cascadeDeclarations = [];
                foreach ($declarations as $attribute => $value) {
                    $cascadeDeclarations[$attribute] = [
                        'value' => $value,
                        'important' => isset($important[$attribute]),
                    ];
                }
                $cascadeRules[] = [
                    'selector' => $compiledSelector,
                    'declarations' => $cascadeDeclarations,
                    'order' => $sourceOrder,
                ];
                if (str_contains($body, 'var(')) {
                    $variableRules[] = [
                        'selector' => $selector,
                        'order' => $sourceOrder,
                        'body' => $body,
                    ];
                }
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
                // Complex selectors are evaluated from cascadeRules at render time.
            }
            $sourceOrder++;
        }

        $stateRules = [];
        foreach ($language2['states'] as $selector => $states) {
            foreach ($states as $state => $stateDeclarations) {
                $stateRules[] = [
                    'selector' => StyleSelectorCompiler::compile($selector, $name),
                    'state' => $state,
                    'declarations' => $stateDeclarations,
                ];
            }
        }
        $sheet = [
            'scope' => $scope->value,
            'scopeId' => substr(hash('sha256', $name), 0, 16),
            'classes' => $classes,
            'tags' => $tags,
            'classCascade' => $classCascade,
            'cascadeRules' => $cascadeRules,
            'fonts' => $fonts,
            'variables' => $variables,
            'variableRules' => $variableRules,
            'tokens' => $language2['tokens'],
            'states' => $language2['states'],
            'stateRules' => $stateRules,
            'recipes' => $language2['recipes'],
            'queries' => $language2['queries'],
            'keyframes' => $language2['keyframes'],
        ];
        $compiledIr = StyleIrCompiler::compile($sheet, $irSource, $name);

        return [
            ...$sheet,
            'styleIr' => $compiledIr['ir'],
            'styleBytecode' => $compiledIr['bytecode'],
            'styleFingerprint' => $compiledIr['fingerprint'],
            'styleSourceMap' => $compiledIr['sourceMap'],
            'styleCompatibility' => $compiledIr['compatibility'],
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
        // Comments become blank lines so diagnostics keep source line numbers.
        $source = preg_replace_callback(
            '/\/\*[\s\S]*?\*\//',
            static fn (array $comment): string => str_starts_with($comment[0], '/*#pam-source ')
                ? $comment[0]
                : str_repeat("\n", substr_count($comment[0], "\n")),
            $source,
        );
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
                $source,
            ): string {
                $importLine = substr_count(substr($source, 0, (int) $match[0][1]), "\n") + 1;
                $match = array_map(static fn (array $group): string => (string) $group[0], $match);
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
                    return self::sourceMarker(self::relative($path, $root), 1)
                        .self::expandImports(
                            $contents,
                            dirname($path),
                            $root,
                            $stack,
                            $bytes,
                            $depth + 1,
                            $path,
                        )
                        .self::sourceMarker(self::relative($name, $root), $importLine);
                } finally {
                    unset($stack[$path]);
                }
            },
            $source,
            -1,
            $count,
            PREG_OFFSET_CAPTURE,
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

    private static function relative(string $path, string $root): string
    {
        $resolved = realpath($path);
        $path = is_string($resolved) ? $resolved : $path;
        $prefix = rtrim($root, DIRECTORY_SEPARATOR).DIRECTORY_SEPARATOR;

        return str_starts_with($path, $prefix) ? substr($path, strlen($prefix)) : $path;
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
     * Properties with no native rendering meaning (pointer cursors, browser
     * chrome, compositor hints). They are accepted so shared web/native CSS
     * compiles, and documented as no-ops in docs/css.md.
     *
     * @var array<string, true>
     */
    private const IGNORED_PROPERTIES = [
        'cursor' => true,
        '-webkit-tap-highlight-color' => true,
        'touch-action' => true,
        'will-change' => true,
        'contain' => true,
        'isolation' => true,
        '-webkit-font-smoothing' => true,
        '-moz-osx-font-smoothing' => true,
        'text-rendering' => true,
        'appearance' => true,
        '-webkit-appearance' => true,
        '-moz-appearance' => true,
        'outline' => true,
        'outline-color' => true,
        'outline-style' => true,
        'outline-width' => true,
        'outline-offset' => true,
        'scroll-behavior' => true,
        'overscroll-behavior' => true,
        'overscroll-behavior-x' => true,
        'overscroll-behavior-y' => true,
        '-webkit-overflow-scrolling' => true,
        'content-visibility' => true,
        '-webkit-user-drag' => true,
        'backface-visibility' => true,
        '-webkit-backface-visibility' => true,
        'resize' => true,
        'accent-color' => true,
        'print-color-adjust' => true,
        '-webkit-print-color-adjust' => true,
        '-webkit-text-size-adjust' => true,
        'text-size-adjust' => true,
        'font-display' => true,
        'font-optical-sizing' => true,
        'image-rendering' => true,
        'forced-color-adjust' => true,
        'color-scheme' => true,
        '-webkit-box-orient' => true,
        'unicode-bidi' => true,
        'tab-size' => true,
        'font-kerning' => true,
        'text-align-last' => true,
        'scrollbar-width' => true,
        'scrollbar-color' => true,
        'scrollbar-gutter' => true,
    ];

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
        $raw = self::rawDeclarations($source, $name);
        // Custom properties declared inside a rule are visible to that rule.
        // Descendant inheritance of rule-scoped variables is not modelled: an
        // unknown var() still fails at compile time instead of rendering wrong.
        $local = $variables;
        foreach ($raw as $property => $rawValue) {
            if (str_starts_with($property, '--')) {
                $local[$property] = self::unquote(trim(
                    preg_replace('/\s*!important\s*$/i', '', $rawValue) ?? $rawValue,
                ));
            }
        }
        foreach ($raw as $property => $rawValue) {
            if (str_starts_with($property, '--')) {
                continue;
            }
            $value = preg_replace('/\s*!important\s*$/i', '', $rawValue) ?? $rawValue;
            try {
                $value = self::resolveVariables($value, $local, $name);
            } catch (RuntimeException $error) {
                throw new CssDiagnostic($error->getMessage(), $property, null, $error);
            }
            try {
                self::declaration($output, $property, trim($value), $name);
            } catch (CssDiagnostic $diagnostic) {
                throw $diagnostic;
            } catch (RuntimeException|\InvalidArgumentException $error) {
                throw new CssDiagnostic($error->getMessage(), $property, null, $error);
            }
        }
        // Hosts without per-side border colors keep the legacy single color:
        // the last side color authored in the rule.
        $lastSideColor = $output['__lastSideColor'] ?? null;
        unset($output['__lastSideColor']);
        if (!isset($output['borderColor']) && $lastSideColor !== null) {
            $output['borderColor'] = $lastSideColor;
        }

        return $output;
    }

    /** @param array<string, string|int|bool> $output */
    private static function declaration(
        array &$output,
        string $property,
        string $value,
        string $name,
    ): void {
        $lower = strtolower($value);
        if ($value === '') {
            throw new RuntimeException("Empty value for CSS property {$property} in {$name}.");
        }
        if (isset(self::IGNORED_PROPERTIES[$property])) {
            return;
        }
        if (
            in_array($lower, ['inherit', 'initial', 'unset', 'revert', 'revert-layer'], true)
            && $property !== 'flex'
        ) {
            throw new RuntimeException(
                "CSS-wide keyword {$lower} for {$property} is not supported natively in {$name}; author an explicit value.",
            );
        }
        if (in_array($property, ['padding', 'margin'], true)) {
            self::expandBox($output, $property, $value, $name);
            return;
        }
        if (preg_match('/^(padding|margin)-(top|right|bottom|left)$/D', $property, $match) === 1) {
            self::boxEdge($output, $match[1], ucfirst($match[2]), $value, $name);
            return;
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
            return;
        }
        if (preg_match('/^(padding|margin|inset)-(inline|block)-(start|end)$/D', $property, $match) === 1) {
            $edge = match ($match[2].'-'.$match[3]) {
                'inline-start' => 'Left',
                'inline-end' => 'Right',
                'block-start' => 'Top',
                'block-end' => 'Bottom',
            };
            if ($match[1] === 'inset') {
                self::declaration($output, strtolower($edge), $value, $name);
                return;
            }
            self::boxEdge($output, $match[1], $edge, $value, $name);
            return;
        }
        if ($property === 'inset') {
            self::expandInset($output, $value, $name);
            return;
        }
        if (in_array($property, ['top', 'right', 'bottom', 'left'], true)) {
            if ($lower === 'auto') {
                return;
            }
            if (self::isPercentage($value)) {
                $output[$property.'Percent'] = self::percentage($value, $name);
                return;
            }
            $output[$property] = self::scalar($value, $name);
            return;
        }
        if (in_array($property, ['width', 'height', 'min-width', 'min-height', 'max-width', 'max-height'], true)) {
            self::size($output, $property, $value, $name);
            return;
        }
        if ($property === 'border') {
            self::expandBorder($output, $value, $name);
            return;
        }
        if (preg_match('/^border-(top|right|bottom|left)$/D', $property, $match) === 1) {
            self::expandBorder($output, $value, $name, ucfirst($match[1]));
            return;
        }
        if ($property === 'border-width') {
            foreach (self::fourSides($value, $property, $name) as $edge => $edgeValue) {
                $output['border'.$edge.'Width'] = self::borderWidth($edgeValue, $name);
            }
            // Keep the uniform shorthand compact for hosts and IR consumers.
            if (count(array_unique(self::fourSides($value, $property, $name))) === 1) {
                foreach (['Top', 'Right', 'Bottom', 'Left'] as $edge) {
                    unset($output['border'.$edge.'Width']);
                }
                $output['borderWidth'] = self::borderWidth(self::fourSides($value, $property, $name)['Top'], $name);
            }
            return;
        }
        if (preg_match('/^border-(top|right|bottom|left)-width$/D', $property, $match) === 1) {
            $output['border'.ucfirst($match[1]).'Width'] = self::borderWidth($value, $name);
            return;
        }
        if ($property === 'border-color') {
            $sides = self::fourSides($value, $property, $name);
            if (count(array_unique($sides)) === 1) {
                self::uniformBorderColor($output, self::color($sides['Top'], $property, $name));
                return;
            }
            foreach ($sides as $edge => $edgeValue) {
                $output['border'.$edge.'Color'] = self::color($edgeValue, $property, $name);
            }
            $output['borderColor'] = $output['borderTopColor'];
            return;
        }
        if (preg_match('/^border-(top|right|bottom|left)-color$/D', $property, $match) === 1) {
            $output['border'.ucfirst($match[1]).'Color'] = self::color($value, $property, $name);
            $output['__lastSideColor'] = $output['border'.ucfirst($match[1]).'Color'];
            return;
        }
        if ($property === 'border-style') {
            $sides = array_unique(self::fourSides($lower, $property, $name));
            if (count($sides) !== 1) {
                throw new RuntimeException(
                    "Native borders use one style for every side; mixed border-style {$value} is unsupported in {$name}.",
                );
            }
            self::borderStyle($output, (string) reset($sides), $name);
            return;
        }
        if (preg_match('/^border-(top|right|bottom|left)-style$/D', $property) === 1) {
            self::borderStyle($output, $lower, $name);
            return;
        }
        if ($property === 'border-radius') {
            self::expandBorderRadius($output, $value, $name);
            return;
        }
        if (preg_match('/^border-(top|bottom)-(left|right)-radius$/D', $property, $match) === 1) {
            $output['border'.ucfirst($match[1]).ucfirst($match[2]).'Radius'] = self::radius($value, $name);
            return;
        }
        if (preg_match('/^border-(start|end)-(start|end)-radius$/D', $property, $match) === 1) {
            $output['border'.($match[1] === 'start' ? 'Top' : 'Bottom').($match[2] === 'start' ? 'Left' : 'Right').'Radius'] =
                self::radius($value, $name);
            return;
        }
        if ($property === 'flex') {
            self::expandFlex($output, $value, $name);
            return;
        }
        if ($property === 'flex-basis') {
            self::flexBasis($output, $value, $name);
            return;
        }
        if ($property === 'flex-flow') {
            foreach (self::cssValueParts($lower, $name) as $part) {
                if (in_array($part, ['row', 'row-reverse', 'column', 'column-reverse'], true)) {
                    $output['flexDirection'] = $part;
                } elseif (in_array($part, ['wrap', 'nowrap', 'wrap-reverse'], true)) {
                    $output['flexWrap'] = $part;
                } else {
                    throw new RuntimeException("Invalid flex-flow value {$value} in {$name}.");
                }
            }
            return;
        }
        if ($property === 'flex-direction') {
            $output['flexDirection'] = self::keyword($lower, ['row', 'row-reverse', 'column', 'column-reverse'], $property, $name);
            return;
        }
        if ($property === 'flex-wrap') {
            $output['flexWrap'] = self::keyword($lower, ['wrap', 'nowrap', 'wrap-reverse'], $property, $name);
            return;
        }
        if ($property === 'order') {
            if (preg_match('/^-?\d+$/D', $value) !== 1) {
                throw new RuntimeException("CSS order must be an integer in {$name}, got {$value}.");
            }
            if ((int) $value < 0) {
                throw new RuntimeException("Native order supports 0 or greater in {$name}; reorder siblings with positive values.");
            }
            $output['order'] = (int) $value;
            return;
        }
        if (in_array($property, ['align-items', 'align-self'], true)) {
            $allowed = ['flex-start', 'start', 'self-start', 'center', 'flex-end', 'end', 'self-end', 'stretch', 'baseline', 'first baseline', 'normal'];
            if ($property === 'align-self') {
                $allowed[] = 'auto';
            }
            $keyword = self::keyword($lower, $allowed, $property, $name);
            if ($keyword === 'auto') {
                return;
            }
            $output[$property === 'align-items' ? 'alignItems' : 'alignSelf'] = match ($keyword) {
                'self-start' => 'flex-start',
                'self-end' => 'flex-end',
                'first baseline' => 'baseline',
                'normal' => 'stretch',
                default => $keyword,
            };
            return;
        }
        if ($property === 'justify-content') {
            $keyword = self::keyword($lower, ['flex-start', 'start', 'left', 'normal', 'stretch', 'center', 'flex-end', 'end', 'right', 'space-between', 'space-around', 'space-evenly'], $property, $name);
            $output['justifyContent'] = match ($keyword) {
                'left', 'normal', 'stretch' => 'flex-start',
                'right' => 'flex-end',
                default => $keyword,
            };
            return;
        }
        if ($property === 'align-content') {
            $output['alignContent'] = self::keyword($lower, ['flex-start', 'start', 'normal', 'center', 'flex-end', 'end', 'stretch', 'space-between', 'space-around', 'space-evenly'], $property, $name);
            return;
        }
        if ($property === 'place-content') {
            $parts = self::cssValueParts($lower, $name);
            self::declaration($output, 'align-content', $parts[0] ?? $lower, $name);
            self::declaration($output, 'justify-content', $parts[1] ?? $parts[0] ?? $lower, $name);
            return;
        }
        if ($property === 'place-self') {
            $parts = self::cssValueParts($lower, $name);
            if (isset($parts[1]) && $parts[1] !== $parts[0] && $parts[1] !== 'auto') {
                throw new RuntimeException("Native layout supports place-self only for the cross axis in {$name}.");
            }
            self::declaration($output, 'align-self', $parts[0] ?? $lower, $name);
            return;
        }
        if ($property === 'place-items') {
            $parts = self::cssValueParts($value, $name);
            $output['alignItems'] = self::unquote($parts[0] ?? 'stretch');
            $output['justifyContent'] = self::unquote($parts[1] ?? $parts[0] ?? 'stretch');
            return;
        }
        if ($property === 'gap') {
            $parts = self::cssValueParts($value, $name);
            if (count($parts) === 1) {
                $output['gap'] = self::scalar($parts[0], $name);
                return;
            }
            if (count($parts) !== 2) {
                throw new RuntimeException("Invalid gap shorthand {$value} in {$name}.");
            }
            $output['gridRowGap'] = self::scalar($parts[0], $name);
            $output['gridColumnGap'] = self::scalar($parts[1], $name);
            return;
        }
        if (in_array($property, ['row-gap', 'column-gap', 'grid-row-gap', 'grid-column-gap'], true)) {
            if ($lower === 'normal') {
                $value = '0';
            }
            $output[str_contains($property, 'column') ? 'gridColumnGap' : 'gridRowGap'] = self::scalar($value, $name);
            return;
        }
        if ($property === 'grid-template-columns') {
            $output['columns'] = self::gridColumns($value, $name);
            return;
        }
        if ($property === 'grid-column') {
            if (preg_match('/^span\s+([1-9]|[1-5][0-9]|6[0-4])$/iD', $value, $match) !== 1) {
                throw new RuntimeException("Native grid-column in {$name} must be 'span 1' through 'span 64'.");
            }
            $output['span'] = $match[1];
            return;
        }
        if ($property === 'display') {
            $output['visible'] = match ($lower) {
                'none' => false,
                'flex', 'inline-flex', 'grid', 'inline-grid', 'block', 'flow-root',
                '-webkit-box', '-webkit-inline-box' => true,
                'contents' => throw new RuntimeException(
                    "display: contents is unsupported natively in {$name}; move the children up or style the parent.",
                ),
                default => throw new RuntimeException(
                    "Native display in {$name} supports flex, grid, block or none; got {$value}.",
                ),
            };
            return;
        }
        if ($property === 'position') {
            $output['position'] = match ($lower) {
                'relative', 'static' => 'relative',
                'absolute' => 'absolute',
                'fixed' => 'fixed',
                'sticky' => throw new RuntimeException(
                    "position: sticky is unsupported natively in {$name}; use a header outside the ScrollView or stickyHeaderIndices.",
                ),
                default => throw new RuntimeException("Invalid position value {$value} in {$name}."),
            };
            return;
        }
        if ($property === 'overflow' || $property === 'overflow-x' || $property === 'overflow-y') {
            $parts = array_unique(self::cssValueParts($lower, $name));
            if (count($parts) !== 1) {
                throw new RuntimeException("Native overflow uses one value for both axes in {$name}.");
            }
            $output['overflow'] = match ((string) reset($parts)) {
                'visible' => 'visible',
                'hidden', 'clip' => 'hidden',
                'auto', 'scroll' => throw new RuntimeException(
                    "overflow: {$lower} is unsupported natively in {$name}; wrap the content in <ScrollView>.",
                ),
                default => throw new RuntimeException("Invalid overflow value {$value} in {$name}."),
            };
            return;
        }
        if ($property === 'z-index') {
            if ($lower === 'auto') {
                return;
            }
            $output['zIndex'] = self::scalar($value, $name);
            return;
        }
        if ($property === 'opacity') {
            $output['opacity'] = self::isPercentage($value)
                ? (string) ((float) self::percentage($value, $name) / 100)
                : self::scalar($value, $name);
            return;
        }
        if ($property === 'aspect-ratio') {
            $ratio = trim(preg_replace('/^auto\s+|\s+auto$/i', '', $value) ?? $value);
            if (strtolower($ratio) === 'auto') {
                return;
            }
            $parts = array_map('trim', explode('/', $ratio));
            if (count($parts) > 2 || (count($parts) === 2 && (float) self::scalar($parts[1], $name) === 0.0)) {
                throw new RuntimeException("Invalid aspect-ratio in {$name}.");
            }
            $output['aspectRatio'] = count($parts) === 2
                ? (string) ((float) self::scalar($parts[0], $name) / (float) self::scalar($parts[1], $name))
                : self::scalar($parts[0], $name);
            return;
        }
        if ($property === 'box-sizing') {
            if ($lower !== 'border-box') {
                throw new RuntimeException(
                    "Pam Native uses border-box layout; content-box is unsupported in {$name}.",
                );
            }
            return;
        }
        if (in_array($property, ['background', 'background-color'], true)) {
            if (in_array($lower, ['none', 'transparent'], true)) {
                $output['backgroundColor'] = 0;
                if ($property === 'background') {
                    $output['backgroundGradient'] = '';
                }
                return;
            }
            if ($property === 'background') {
                $background = CssEffects::background($value, true, $name);
                $output['backgroundColor'] = $background['color'] ?? 0;
                $output['backgroundGradient'] = CssEffects::encode($background['layers']);
                return;
            }
            $output['backgroundColor'] = self::color($value, $property, $name);
            return;
        }
        if ($property === 'background-image') {
            $output['backgroundGradient'] = CssEffects::encode(
                CssEffects::background($value, false, $name)['layers'],
            );
            return;
        }
        if (in_array($property, ['border-image', 'border-image-source'], true)) {
            self::borderImage($output, $property, $value, $name);
            return;
        }
        if (in_array($property, ['border-image-slice', 'border-image-width', 'border-image-outset', 'border-image-repeat'], true)) {
            $initial = match ($property) {
                'border-image-slice' => ['1', '100%', '1 fill', 'fill 1'],
                'border-image-width' => ['1', 'auto'],
                'border-image-outset' => ['0', '0px'],
                default => ['stretch'],
            };
            if (!in_array($lower, $initial, true)) {
                throw new RuntimeException(
                    "Native {$property} supports only its initial value in {$name}; a gradient border-image strokes the whole border.",
                );
            }
            return;
        }
        if (in_array($property, ['background-size', 'background-position', 'background-repeat', 'background-clip', 'background-origin', 'background-attachment'], true)) {
            $neutral = match ($property) {
                'background-size' => ['auto', 'auto auto', '100%', '100% 100%', 'cover'],
                'background-position' => ['0 0', '0% 0%', 'left top', 'top left', '0px 0px'],
                'background-repeat' => ['repeat', 'no-repeat'],
                'background-clip', 'background-origin' => ['border-box'],
                default => ['scroll'],
            };
            if (!in_array($lower, $neutral, true)) {
                throw new RuntimeException(
                    "Native {$property}: {$value} is unsupported in {$name}; native gradients cover the border box.",
                );
            }
            return;
        }
        if ($property === 'color') {
            $output['textColor'] = self::color($value, $property, $name);
            return;
        }
        if ($property === '-pam-placeholder-color' || $property === '-pam-selection-color') {
            $output[$property === '-pam-placeholder-color' ? 'placeholderColor' : 'selectionColor'] =
                self::color($value, $property === '-pam-placeholder-color' ? '::placeholder' : '::selection', $name);
            return;
        }
        if ($property === 'caret-color') {
            if ($lower === 'auto') {
                return;
            }
            $output['cursorColor'] = self::color($value, $property, $name);
            return;
        }
        if ($property === 'box-shadow') {
            self::expandBoxShadow($output, $value, $name);
            return;
        }
        if ($property === 'text-shadow') {
            self::expandTextShadow($output, $value, $name);
            return;
        }
        if ($property === 'font') {
            self::expandFont($output, $value, $name);
            return;
        }
        if ($property === 'font-family') {
            $output['fontFamily'] = self::fontFamily($value, $name);
            return;
        }
        if ($property === 'font-size') {
            $output['fontSize'] = self::fontSize($value, $name);
            return;
        }
        if ($property === 'font-style') {
            $output['fontStyle'] = match (true) {
                $lower === 'normal' => 'normal',
                $lower === 'italic', str_starts_with($lower, 'oblique') => 'italic',
                default => throw new RuntimeException("Invalid font-style in {$name}."),
            };
            return;
        }
        if ($property === 'font-weight') {
            $output['fontWeight'] = self::fontWeight($value, $name);
            return;
        }
        if (in_array($property, ['font-variant', 'font-variant-numeric', 'font-variant-caps', 'font-variant-ligatures', 'font-feature-settings'], true)) {
            self::fontFeatures($output, $property, $value, $name);
            return;
        }
        if ($property === 'line-height') {
            self::lineHeight($output, $value, $name);
            return;
        }
        if ($property === 'letter-spacing') {
            $output['letterSpacing'] = $lower === 'normal' ? '0' : self::scalar($value, $name);
            return;
        }
        if ($property === 'text-align') {
            $output['textAlign'] = match (self::keyword($lower, ['left', 'right', 'center', 'start', 'end', 'justify', 'match-parent'], $property, $name)) {
                'left', 'start', 'match-parent' => 'left',
                'right', 'end' => 'right',
                'center' => 'center',
                'justify' => 'justify',
            };
            return;
        }
        if ($property === 'text-transform') {
            $output['textTransform'] = self::keyword($lower, ['none', 'uppercase', 'lowercase', 'capitalize'], $property, $name);
            return;
        }
        if (in_array($property, ['text-decoration', 'text-decoration-line'], true)) {
            self::textDecoration($output, $value, $name);
            return;
        }
        if ($property === 'text-decoration-style') {
            if ($lower !== 'solid') {
                throw new RuntimeException("Native text decorations are solid; text-decoration-style {$value} is unsupported in {$name}.");
            }
            return;
        }
        if ($property === 'text-decoration-color') {
            if ($lower !== 'currentcolor') {
                throw new RuntimeException("Native text decorations use the text color; text-decoration-color is unsupported in {$name}.");
            }
            return;
        }
        if (in_array($property, ['text-decoration-thickness', 'text-underline-offset'], true)) {
            if (!in_array($lower, ['auto', 'from-font'], true)) {
                throw new RuntimeException("Native {$property} is unsupported in {$name}.");
            }
            return;
        }
        if ($property === 'text-overflow') {
            $output['ellipsizeMode'] = match ($lower) {
                'ellipsis' => 'tail',
                'clip' => 'clip',
                default => throw new RuntimeException("Native text-overflow supports ellipsis or clip in {$name}."),
            };
            return;
        }
        if (in_array($property, ['-webkit-line-clamp', 'line-clamp'], true)) {
            if ($lower === 'none') {
                return;
            }
            if (preg_match('/^[1-9]\d{0,2}$/D', $value) !== 1) {
                throw new RuntimeException("{$property} must be a positive integer in {$name}.");
            }
            $output['numberOfLines'] = (int) $value;
            $output['ellipsizeMode'] ??= 'tail';
            return;
        }
        if ($property === 'white-space' || $property === 'text-wrap' || $property === 'text-wrap-mode') {
            if (in_array($lower, ['nowrap', 'pre'], true)) {
                $output['numberOfLines'] = 1;
                $output['ellipsizeMode'] ??= 'clip';
                return;
            }
            if (!in_array($lower, ['normal', 'wrap', 'pre-wrap', 'pre-line', 'break-spaces', 'balance', 'pretty', 'stable'], true)) {
                throw new RuntimeException("Invalid {$property} value {$value} in {$name}.");
            }
            return;
        }
        if (in_array($property, ['word-break', 'overflow-wrap', 'word-wrap'], true)) {
            // Native text already breaks words that cannot fit a line.
            if (!in_array($lower, ['normal', 'break-word', 'anywhere', 'break-all', 'keep-all'], true)) {
                throw new RuntimeException("Invalid {$property} value {$value} in {$name}.");
            }
            if ($property === 'word-break' && $lower === 'break-all') {
                $output['textBreakStrategy'] = 'simple';
            }
            return;
        }
        if (in_array($property, ['hyphens', '-webkit-hyphens'], true)) {
            $output['androidHyphenationFrequency'] = match ($lower) {
                'none' => 'none',
                'manual' => 'normal',
                'auto' => 'full',
                default => throw new RuntimeException("Invalid hyphens value {$value} in {$name}."),
            };
            return;
        }
        if ($property === 'direction') {
            $output['layoutDirection'] = self::keyword($lower, ['ltr', 'rtl'], $property, $name);
            return;
        }
        if ($property === 'writing-mode') {
            if ($lower !== 'horizontal-tb') {
                throw new RuntimeException("Native text supports writing-mode: horizontal-tb only in {$name}.");
            }
            return;
        }
        if (in_array($property, ['vertical-align', 'text-indent', 'content', 'clip-path', 'mask', 'mask-image', '-webkit-mask-image', 'mix-blend-mode', 'columns', 'column-count', 'float', 'clear', 'list-style', 'list-style-type', 'table-layout', 'shape-outside'], true)) {
            if (
                ($property === 'mix-blend-mode' && $lower === 'normal')
                || (in_array($property, ['clip-path', 'mask', 'mask-image', '-webkit-mask-image', 'float', 'list-style', 'list-style-type'], true) && $lower === 'none')
                || ($property === 'text-indent' && in_array($lower, ['0', '0px'], true))
                || ($property === 'vertical-align' && $lower === 'baseline')
            ) {
                return;
            }
            throw new RuntimeException("CSS property {$property} has no native layout/paint equivalent in {$name}.");
        }
        if ($property === 'object-fit') {
            $output['resizeMode'] = match ($lower) {
                'contain', 'cover', 'fill' => $lower,
                'none' => 'center',
                'scale-down' => 'contain',
                default => throw new RuntimeException(
                    "Unsupported object-fit value {$value} in {$name}.",
                ),
            };
            return;
        }
        if ($property === 'object-position') {
            if (!in_array(preg_replace('/\s+/', ' ', $lower), ['center', 'center center', '50% 50%', '50%'], true)) {
                throw new RuntimeException("Native images are centered; object-position {$value} is unsupported in {$name}.");
            }
            return;
        }
        if ($property === 'visibility') {
            $output['visible'] = match ($lower) {
                'visible' => true,
                'hidden', 'collapse' => false,
                default => throw new RuntimeException(
                    "Unsupported visibility value {$value} in {$name}.",
                ),
            };
            return;
        }
        if ($property === 'pointer-events') {
            $output['pointerEvents'] = match ($lower) {
                'auto', 'all', 'visible', 'visiblepainted' => 'auto',
                'none' => 'none',
                'box-none' => 'box-none',
                'box-only' => 'box-only',
                default => throw new RuntimeException("Invalid pointer-events value {$value} in {$name}."),
            };
            return;
        }
        if (in_array($property, ['user-select', '-webkit-user-select'], true)) {
            match ($lower) {
                'none' => $output['selectable'] = false,
                'text', 'all', 'contain' => $output['selectable'] = true,
                'auto' => null,
                default => throw new RuntimeException("Invalid user-select value {$value} in {$name}."),
            };
            return;
        }
        if ($property === 'transform') {
            self::expandTransform($output, $value, $name);
            return;
        }
        if (in_array($property, ['translate', 'scale', 'rotate'], true)) {
            if ($lower === 'none') {
                return;
            }
            $parts = self::cssValueParts($value, $name);
            $function = match ($property) {
                'translate' => 'translate('.implode(', ', $parts).')',
                'scale' => 'scale('.implode(', ', $parts).')',
                'rotate' => 'rotate('.$value.')',
            };
            self::expandTransform($output, $function, $name);
            return;
        }
        if ($property === 'transform-origin') {
            self::transformOrigin($output, $value, $name);
            return;
        }
        if ($property === 'filter') {
            self::filter($output, $value, $name);
            return;
        }
        if (in_array($property, ['backdrop-filter', '-webkit-backdrop-filter'], true)) {
            $filter = CssEffects::filter($value, 'backdrop-filter', $name);
            $output['backdropBlurRadius'] = CssEffects::number($filter['blur']);
            $output['backdropColorMatrix'] = CssEffects::encodeMatrix($filter['matrix']);
            return;
        }
        if (str_starts_with($property, 'transition')) {
            self::transition($output, $property, $value, $name);
            return;
        }
        if (str_starts_with($property, 'animation')) {
            throw new RuntimeException(
                "CSS {$property} in {$name} must be attached with <Animated keyframes=\"…\"> (keyframes run on the native UI thread).",
            );
        }
        if ($property === 'elevation') {
            $output['elevation'] = self::scalar($value, $name);
            return;
        }
        if (in_array($property, ['translation-x', 'translation-y'], true)) {
            $output[$property === 'translation-x' ? 'translationX' : 'translationY'] = self::scalar($value, $name);
            return;
        }
        if (in_array($property, [
            '-pam-native-background-color',
            '-pam-native-text-color',
            '-pam-native-border-color',
        ], true)) {
            $output[self::PROPERTIES[$property]] = self::propertyValue($property, $value, $name);
            return;
        }
        $attribute = self::PROPERTIES[$property] ?? null;
        if ($attribute !== null) {
            $output[$attribute] = self::propertyValue($property, $value, $name);
            return;
        }

        throw new RuntimeException(
            "Unsupported native CSS property {$property} in {$name}.",
        );
    }

    /** @return array<string, true> Native attribute names marked important. */
    private static function importantProperties(string $source, array $variables = []): array
    {
        $important = [];
        foreach (self::rawDeclarations($source, 'style rule') as $property => $value) {
            if (str_starts_with($property, '--') || preg_match('/!important\s*$/i', $value) !== 1) {
                continue;
            }
            try {
                $compiled = self::declarations($property.': '.$value, $variables, 'style rule');
            } catch (RuntimeException|\InvalidArgumentException) {
                continue;
            }
            foreach (array_keys($compiled) as $native) {
                $important[$native] = true;
            }
        }
        return $important;
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
            $value = trim($parts[1]);
            // Repeated declarations are CSS fallbacks: the last one wins
            // unless an earlier declaration is !important.
            if (
                array_key_exists($property, $output)
                && preg_match('/!important\s*$/i', $output[$property]) === 1
                && preg_match('/!important\s*$/i', $value) !== 1
            ) {
                continue;
            }
            unset($output[$property]);
            $output[$property] = $value;
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
        foreach (self::fourSides($value, $property, $name) as $edge => $edgeValue) {
            self::boxEdge($output, $property, $edge, $edgeValue, $name);
        }
    }

    /** @param array<string, string|bool> $output */
    private static function expandInset(
        array &$output,
        string $value,
        string $name,
    ): void {
        foreach (self::fourSides($value, 'inset', $name) as $edge => $edgeValue) {
            self::declaration($output, strtolower($edge), $edgeValue, $name);
        }
    }

    /** @param array<string, string|int|bool> $output */
    private static function expandLogicalBox(
        array &$output,
        string $property,
        string $value,
        string $name,
    ): void {
        $parts = self::cssValueParts(trim($value), $name);
        if ($parts === [] || count($parts) > 2) {
            throw new RuntimeException("Invalid {$property} shorthand in {$name}.");
        }
        [$first, $second] = match ($property) {
            'padding-inline', 'margin-inline', 'inset-inline' => ['Left', 'Right'],
            'padding-block', 'margin-block', 'inset-block' => ['Top', 'Bottom'],
        };
        $kind = explode('-', $property)[0];
        foreach ([$first => $parts[0], $second => $parts[1] ?? $parts[0]] as $edge => $edgeValue) {
            if ($kind === 'inset') {
                self::declaration($output, strtolower($edge), $edgeValue, $name);
            } else {
                self::boxEdge($output, $kind, $edge, $edgeValue, $name);
            }
        }
    }

    /**
     * `border` and `border-<side>` shorthands: width, style and color in any
     * order (CSS Backgrounds §3.6).
     *
     * @param array<string, string|int|bool> $output
     */
    private static function expandBorder(
        array &$output,
        string $value,
        string $name,
        string $edge = '',
    ): void {
        $widthKey = $edge === '' ? 'borderWidth' : 'border'.$edge.'Width';
        $colorKey = $edge === '' ? 'borderColor' : 'border'.$edge.'Color';
        if (in_array(strtolower(trim($value)), ['none', 'hidden', '0', '0px', '0dp', '0pt'], true)) {
            $output[$widthKey] = '0';
            if ($edge === '') {
                $output['borderColor'] = 0;
            }
            return;
        }
        $width = null;
        $style = null;
        $color = null;
        foreach (self::cssValueParts(trim($value), $name) as $part) {
            $lower = strtolower($part);
            if ($width === null && (self::isScalarToken($part) || in_array($lower, ['thin', 'medium', 'thick'], true) || StyleValueCompiler::isDynamic($part))) {
                $width = self::borderWidth($part, $name);
            } elseif ($style === null && in_array($lower, ['none', 'hidden', 'solid', 'dashed', 'dotted', 'double', 'groove', 'ridge', 'inset', 'outset'], true)) {
                $style = $lower;
            } elseif ($color === null) {
                $color = self::color($part, 'border', $name);
            } else {
                throw new RuntimeException("Invalid border shorthand {$value} in {$name}; expected '<width> <style> <color>'.");
            }
        }
        if ($style === null || in_array($style, ['none', 'hidden'], true)) {
            // CSS: a border without a style (or with none) is not painted.
            $output[$widthKey] = '0';
            if ($color !== null) {
                $output[$colorKey] = $color;
            }
            return;
        }
        $output[$widthKey] = $width ?? '3';
        if ($color !== null && $edge === '') {
            self::uniformBorderColor($output, $color);
        } elseif ($color !== null) {
            $output[$colorKey] = $color;
            $output['__lastSideColor'] = $color;
        }
        if ($style !== 'solid') {
            self::borderStyle($output, $style, $name);
        }
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
        $corners = self::fourSides($value, 'border-radius', $name);
        if (count(array_unique($corners)) === 1) {
            $output['borderRadius'] = self::radius($corners['Top'], $name);
            return;
        }
        // fourSides() returns top-left, top-right, bottom-right, bottom-left order.
        foreach ([
            'borderTopLeftRadius' => $corners['Top'],
            'borderTopRightRadius' => $corners['Right'],
            'borderBottomRightRadius' => $corners['Bottom'],
            'borderBottomLeftRadius' => $corners['Left'],
        ] as $attribute => $cornerValue) {
            $output[$attribute] = self::radius($cornerValue, $name);
        }
    }

    /** @param array<string, string|int|bool> $output */
    private static function boxEdge(
        array &$output,
        string $property,
        string $edge,
        string $value,
        string $name,
    ): void {
        if ($property === 'margin' && strtolower(trim($value)) === 'auto') {
            $output['margin'.$edge] = '0';
            $output['margin'.$edge.'Auto'] = true;
            return;
        }
        if ($property === 'margin') {
            unset($output['margin'.$edge.'Auto']);
        }
        $output[$property.$edge] = self::scalar($value, $name);
    }

    private static function isPercentage(string $value): bool
    {
        return preg_match('/^-?(?:\d+|\d*\.\d+)%$/D', trim($value)) === 1;
    }

    /** @param array<string, string|int|bool> $output */
    private static function size(
        array &$output,
        string $property,
        string $value,
        string $name,
    ): void {
        $lower = strtolower(trim($value));
        if ($lower === 'auto' || ($lower === 'none' && str_starts_with($property, 'max-'))) {
            return;
        }
        if (in_array($lower, ['min-content', 'max-content', 'fit-content', 'stretch', '-webkit-fill-available'], true)) {
            throw new RuntimeException(
                "{$property}: {$lower} is unsupported natively in {$name}; native boxes are content-sized unless a length or percentage is set.",
            );
        }
        $attribute = lcfirst(str_replace(' ', '', ucwords(str_replace('-', ' ', $property))));
        if (self::isPercentage($value)) {
            $output[$attribute.'Percent'] = self::percentage($value, $name);
            return;
        }
        $output[$attribute] = self::scalar($value, $name);
    }

    /** @return array{Top: string, Right: string, Bottom: string, Left: string} */
    private static function fourSides(string $value, string $property, string $name): array
    {
        $parts = self::cssValueParts(trim($value), $name);
        if ($parts === [] || count($parts) > 4) {
            throw new RuntimeException("Invalid {$property} shorthand in {$name}.");
        }
        [$top, $right, $bottom, $left] = match (count($parts)) {
            1 => [$parts[0], $parts[0], $parts[0], $parts[0]],
            2 => [$parts[0], $parts[1], $parts[0], $parts[1]],
            3 => [$parts[0], $parts[1], $parts[2], $parts[1]],
            4 => $parts,
        };

        return ['Top' => $top, 'Right' => $right, 'Bottom' => $bottom, 'Left' => $left];
    }

    /**
     * A uniform border color also resets every side color so a later rule
     * overrides side colors authored by an earlier rule (cascade order).
     *
     * @param array<string, string|int|bool> $output
     */
    private static function uniformBorderColor(array &$output, int $color): void
    {
        $output['borderColor'] = $color;
        foreach (['Top', 'Right', 'Bottom', 'Left'] as $edge) {
            $output['border'.$edge.'Color'] = $color;
        }
    }

    private static function borderWidth(string $value, string $name): string
    {
        return match (strtolower(trim($value))) {
            'thin' => '1',
            'medium' => '3',
            'thick' => '5',
            default => self::scalar($value, $name),
        };
    }

    private static function color(string $value, string $property, string $name): int
    {
        if (strtolower(trim($value)) === 'currentcolor') {
            throw new RuntimeException(
                "currentColor in {$property} needs the text color at compile time in {$name}; use the same var() as color.",
            );
        }

        return CssColor::parse($value, "{$property} in {$name}");
    }

    /** @param array<string, string|int|bool> $output */
    private static function borderStyle(array &$output, string $style, string $name): void
    {
        match ($style) {
            'solid' => $output['borderStyle'] = 1,
            'dashed' => $output['borderStyle'] = 2,
            'dotted' => $output['borderStyle'] = 3,
            'none', 'hidden' => $output['borderWidth'] = '0',
            default => throw new RuntimeException(
                "Unsupported border-style value {$style} in {$name}; native borders are solid, dashed or dotted.",
            ),
        };
    }

    private static function radius(string $value, string $name): string
    {
        $trimmed = trim($value);
        if (str_contains($trimmed, ' ') || str_contains($trimmed, '/')) {
            throw new RuntimeException("Elliptical border radii are not supported in {$name}.");
        }
        if (self::isPercentage($trimmed)) {
            if ((float) self::percentage($trimmed, $name) >= 50.0) {
                // A full pill/circle: hosts clamp radii to half the shorter side.
                return '9999';
            }
            if ((float) self::percentage($trimmed, $name) === 0.0) {
                return '0';
            }
            throw new RuntimeException(
                "Percentage border-radius below 50% depends on the box size and is unsupported in {$name}; use a length.",
            );
        }

        return self::scalar($trimmed, $name);
    }

    /**
     * CSS `flex` shorthand. A growing item whose basis is the shorthand's
     * implicit `0%` gets an explicit zero basis; `flex: 0` keeps the content
     * size because native layout has no automatic minimum size.
     *
     * @param array<string, string|int|bool> $output
     */
    private static function expandFlex(array &$output, string $value, string $name): void
    {
        $lower = strtolower(trim($value));
        [$grow, $shrink, $basis] = match ($lower) {
            'none' => ['0', '0', 'auto'],
            'auto' => ['1', '1', 'auto'],
            'initial' => ['0', '1', 'auto'],
            default => [null, null, null],
        };
        if ($grow === null) {
            $numbers = [];
            $basisPart = null;
            foreach (self::cssValueParts($lower, $name) as $part) {
                if (preg_match('/^(?:\d+|\d*\.\d+)$/D', $part) === 1 && count($numbers) < 2 && $basisPart === null) {
                    $numbers[] = $part;
                    continue;
                }
                if ($basisPart !== null) {
                    throw new RuntimeException("Invalid flex shorthand {$value} in {$name}.");
                }
                $basisPart = $part;
            }
            if ($numbers === [] && $basisPart === null) {
                throw new RuntimeException("Invalid flex shorthand {$value} in {$name}.");
            }
            $grow = $numbers[0] ?? '1';
            $shrink = $numbers[1] ?? '1';
            $basis = $basisPart ?? ((float) $grow > 0.0 ? '0' : 'auto');
        }
        $output['flexGrow'] = self::scalar($grow, $name);
        $output['flexShrink'] = self::scalar($shrink, $name);
        self::flexBasis($output, $basis, $name);
    }

    /** @param array<string, string|int|bool> $output */
    private static function flexBasis(array &$output, string $value, string $name): void
    {
        unset($output['flexBasis'], $output['flexBasisPercent'], $output['flexBasisContent']);
        $lower = strtolower(trim($value));
        if (in_array($lower, ['auto', 'content', 'max-content', 'fit-content'], true)) {
            $output['flexBasisContent'] = true;
            return;
        }
        if ($lower === 'min-content') {
            throw new RuntimeException("flex-basis: min-content is unsupported natively in {$name}.");
        }
        if (self::isPercentage($value)) {
            $output['flexBasisPercent'] = self::percentage($value, $name);
            return;
        }
        $output['flexBasis'] = self::scalar($value, $name);
    }

    /** @param list<string> $allowed */
    private static function keyword(string $value, array $allowed, string $property, string $name): string
    {
        $normalized = preg_replace('/\s+/', ' ', strtolower(trim(self::unquote($value)))) ?? $value;
        if (!in_array($normalized, $allowed, true)) {
            throw new RuntimeException(
                "Unsupported {$property} value {$value} in {$name}; expected ".implode(', ', $allowed).'.',
            );
        }

        return $normalized;
    }

    /** @param array<string, string|int|bool> $output */
    private static function expandTextShadow(array &$output, string $value, string $name): void
    {
        $trimmed = trim($value);
        if (strtolower($trimmed) === 'none') {
            $output['textShadowOffsetX'] = '0';
            $output['textShadowOffsetY'] = '0';
            $output['textShadowRadius'] = '0';
            $output['textShadowColor'] = 0;
            return;
        }
        if (self::containsTopLevelComma($trimmed)) {
            throw new RuntimeException("Native text-shadow in {$name} supports one shadow.");
        }
        $parts = self::cssValueParts($trimmed, $name);
        $lengths = array_values(array_filter($parts, static fn (string $part): bool => self::isScalarToken($part)));
        $colors = array_values(array_filter($parts, static fn (string $part): bool => !self::isScalarToken($part)));
        if (count($lengths) < 2 || count($lengths) > 3 || count($colors) > 1) {
            throw new RuntimeException("text-shadow in {$name} expects x-offset, y-offset, optional blur and an optional color.");
        }
        $output['textShadowOffsetX'] = self::scalar($lengths[0], $name);
        $output['textShadowOffsetY'] = self::scalar($lengths[1], $name);
        $output['textShadowRadius'] = self::scalar($lengths[2] ?? '0', $name);
        $output['textShadowColor'] = $colors === []
            ? CssColor::parse('rgba(0, 0, 0, 0.5)', "text-shadow in {$name}")
            : self::color($colors[0], 'text-shadow', $name);
    }

    /** @param array<string, string|int|bool> $output */
    private static function expandFont(array &$output, string $value, string $name): void
    {
        $trimmed = trim($value);
        if (preg_match('/^(caption|icon|menu|message-box|small-caption|status-bar|system-ui)$/iD', $trimmed) === 1) {
            throw new RuntimeException("System font keyword {$trimmed} is unsupported in {$name}; author font-size and font-family.");
        }
        // [style] [variant] [weight] [stretch] size[/line-height] family
        if (preg_match('/^(.*?)((?:\d+|\d*\.\d+)(?:px|dp|pt|rem|em|%)|calc\([^)]*\)|var\([^)]*\))(?:\s*\/\s*(\S+))?\s+(.+)$/iD', $trimmed, $match) !== 1) {
            throw new RuntimeException("Invalid font shorthand {$value} in {$name}; expected '[style] [weight] size[/line-height] family'.");
        }
        foreach (self::cssValueParts(strtolower(trim($match[1])), $name) as $part) {
            if ($part === 'normal') {
                continue;
            }
            if ($part === 'italic' || $part === 'oblique') {
                $output['fontStyle'] = 'italic';
            } elseif ($part === 'small-caps') {
                self::fontFeatures($output, 'font-variant-caps', 'small-caps', $name);
            } elseif (preg_match('/^(?:bold|bolder|lighter|[1-9]00|\d+)$/D', $part) === 1) {
                $output['fontWeight'] = self::fontWeight($part, $name);
            } elseif (!in_array($part, ['condensed', 'expanded', 'semi-condensed', 'semi-expanded'], true)) {
                throw new RuntimeException("Unsupported font shorthand part {$part} in {$name}.");
            }
        }
        $output['fontSize'] = self::fontSize($match[2], $name);
        if (($match[3] ?? '') !== '') {
            self::lineHeight($output, $match[3], $name);
        }
        $output['fontFamily'] = self::fontFamily($match[4], $name);
    }

    private static function fontSize(string $value, string $name): string
    {
        $lower = strtolower(trim($value));
        $keywords = [
            'xx-small' => '9', 'x-small' => '10', 'small' => '13', 'medium' => '16',
            'large' => '18', 'x-large' => '24', 'xx-large' => '32', 'xxx-large' => '48',
        ];
        if (isset($keywords[$lower])) {
            return $keywords[$lower];
        }
        if (in_array($lower, ['smaller', 'larger'], true) || self::isPercentage($lower)) {
            throw new RuntimeException("Relative font-size {$value} needs a parent font natively in {$name}; use px or rem.");
        }

        return self::scalar($value, $name);
    }

    private static function fontWeight(string $value, string $name): string
    {
        $weight = match (strtolower(trim($value))) {
            'normal' => '400',
            'bold' => '700',
            'bolder' => '700',
            'lighter' => '300',
            default => self::scalar($value, $name),
        };
        if ((float) $weight < 1 || (float) $weight > 1000) {
            throw new RuntimeException("Invalid font-weight in {$name}.");
        }

        return $weight;
    }

    /** @param array<string, string|int|bool> $output */
    private static function fontFeatures(array &$output, string $property, string $value, string $name): void
    {
        $lower = strtolower(trim($value));
        $existing = isset($output['fontFeatureSettings']) && is_string($output['fontFeatureSettings'])
            ? array_filter(array_map('trim', explode(',', $output['fontFeatureSettings'])))
            : [];
        $features = [];
        if ($property === 'font-feature-settings') {
            if ($lower === 'normal') {
                unset($output['fontFeatureSettings']);
                return;
            }
            foreach (array_map('trim', explode(',', $value)) as $setting) {
                if (preg_match('/^["\']([A-Za-z0-9]{4})["\'](?:\s+(on|off|\d+))?$/D', $setting, $match) !== 1) {
                    throw new RuntimeException("Invalid font-feature-settings {$value} in {$name}.");
                }
                $state = $match[2] ?? '1';
                $features[] = "'".$match[1]."' ".match ($state) { 'on' => '1', 'off' => '0', default => $state };
            }
        } else {
            $map = [
                'tabular-nums' => 'tnum', 'proportional-nums' => 'pnum', 'lining-nums' => 'lnum',
                'oldstyle-nums' => 'onum', 'slashed-zero' => 'zero', 'diagonal-fractions' => 'frac',
                'stacked-fractions' => 'afrc', 'ordinal' => 'ordn', 'small-caps' => 'smcp',
                'all-small-caps' => 'c2sc', 'petite-caps' => 'pcap', 'unicase' => 'unic',
                'titling-caps' => 'titl', 'common-ligatures' => 'liga', 'discretionary-ligatures' => 'dlig',
                'historical-ligatures' => 'hlig', 'contextual' => 'calt',
            ];
            $off = [
                'no-common-ligatures' => 'liga', 'no-discretionary-ligatures' => 'dlig',
                'no-historical-ligatures' => 'hlig', 'no-contextual' => 'calt',
            ];
            if ($lower === 'normal') {
                unset($output['fontFeatureSettings']);
                return;
            }
            if ($lower === 'none' && $property === 'font-variant-ligatures') {
                $features = ["'liga' 0", "'clig' 0", "'calt' 0"];
            } else {
                foreach (self::cssValueParts($lower, $name) as $part) {
                    if ($part === 'all-small-caps') {
                        $features[] = "'smcp' 1";
                    }
                    if (isset($map[$part])) {
                        $features[] = "'".$map[$part]."' 1";
                    } elseif (isset($off[$part])) {
                        $features[] = "'".$off[$part]."' 0";
                    } else {
                        throw new RuntimeException("Unsupported {$property} value {$part} in {$name}.");
                    }
                }
            }
        }
        $output['fontFeatureSettings'] = implode(', ', array_values(array_unique([...$existing, ...$features])));
    }

    /** @param array<string, string|int|bool> $output */
    private static function lineHeight(array &$output, string $value, string $name): void
    {
        $lower = strtolower(trim($value));
        unset($output['lineHeight'], $output['lineHeightMultiplier']);
        if ($lower === 'normal') {
            return;
        }
        $multiplier = null;
        if (preg_match('/^(?:\d+|\d*\.\d+)$/D', $lower) === 1) {
            $multiplier = (float) $lower;
        } elseif (self::isPercentage($lower)) {
            $multiplier = (float) self::percentage($lower, $name) / 100;
        } elseif (preg_match('/^((?:\d+|\d*\.\d+))em$/D', $lower, $match) === 1) {
            $multiplier = (float) $match[1];
        }
        if ($multiplier === null) {
            $output['lineHeight'] = self::scalar($value, $name);
            return;
        }
        $fontSize = $output['fontSize'] ?? null;
        if (is_string($fontSize) && is_numeric($fontSize)) {
            $output['lineHeight'] = (string) (round($multiplier * (float) $fontSize * 100) / 100);
            return;
        }
        // Resolved against the element's final font size at render time.
        $output['lineHeightMultiplier'] = (string) $multiplier;
    }

    /** @param array<string, string|int|bool> $output */
    private static function textDecoration(array &$output, string $value, string $name): void
    {
        $lines = [];
        foreach (self::cssValueParts(strtolower(trim($value)), $name) as $part) {
            if (in_array($part, ['none', 'underline', 'line-through'], true)) {
                $lines[] = $part;
            } elseif (in_array($part, ['solid', 'currentcolor', 'auto', 'from-font'], true)) {
                continue;
            } elseif ($part === 'overline' || in_array($part, ['dashed', 'dotted', 'double', 'wavy'], true)) {
                throw new RuntimeException("Native text-decoration supports solid underline/line-through only in {$name}.");
            } else {
                throw new RuntimeException("Native text decorations use the text color; text-decoration {$value} is unsupported in {$name}.");
            }
        }
        $lines = array_values(array_unique($lines));
        sort($lines);
        $output['textDecoration'] = match ($lines) {
            [], ['none'] => 'none',
            ['underline'] => 'underline',
            ['line-through'] => 'line-through',
            ['line-through', 'underline'] => 'underline-line-through',
            default => throw new RuntimeException("Invalid text-decoration {$value} in {$name}."),
        };
    }

    /** @param array<string, string|int|bool> $output */
    private static function transformOrigin(array &$output, string $value, string $name): void
    {
        $parts = self::cssValueParts(strtolower(trim($value)), $name);
        if ($parts === [] || count($parts) > 3) {
            throw new RuntimeException("Invalid transform-origin {$value} in {$name}.");
        }
        if (isset($parts[2]) && !in_array($parts[2], ['0', '0px'], true)) {
            throw new RuntimeException("3D transform-origin is unsupported natively in {$name}.");
        }
        $keywords = ['left' => ['x', 0.0], 'right' => ['x', 100.0], 'top' => ['y', 0.0], 'bottom' => ['y', 100.0], 'center' => [null, 50.0]];
        $x = null;
        $y = null;
        $pending = [];
        foreach (array_slice($parts, 0, 2) as $index => $part) {
            if (isset($keywords[$part])) {
                [$axis, $percent] = $keywords[$part];
                if ($axis === 'x') {
                    $x = $percent;
                } elseif ($axis === 'y') {
                    $y = $percent;
                } else {
                    $pending[] = $percent;
                }
                continue;
            }
            if (!self::isPercentage($part)) {
                throw new RuntimeException("Native transform-origin supports keywords and percentages in {$name}; got {$part}.");
            }
            $percent = (float) self::percentage($part, $name);
            if ($index === 0) {
                $x = $percent;
            } else {
                $y = $percent;
            }
        }
        foreach ($pending as $percent) {
            if ($x === null) {
                $x = $percent;
            } elseif ($y === null) {
                $y = $percent;
            }
        }
        $output['transformOriginX'] = (string) ($x ?? 50.0);
        $output['transformOriginY'] = (string) ($y ?? 50.0);
    }

    /** @param array<string, string|int|bool> $output */
    private static function filter(array &$output, string $value, string $name): void
    {
        $filter = CssEffects::filter($value, 'filter', $name);
        $output['blurRadius'] = CssEffects::number($filter['blur']);
        $output['filterColorMatrix'] = CssEffects::encodeMatrix($filter['matrix']);
    }

    /** @param array<string, string|int|bool> $output */
    private static function borderImage(array &$output, string $property, string $value, string $name): void
    {
        $tokens = self::splitTopLevel(trim($value), ' ');
        $gradient = null;
        foreach ($tokens as $token) {
            $lower = strtolower($token);
            if ($lower === 'none') {
                continue;
            }
            if (str_contains($lower, 'gradient(') && $gradient === null) {
                $gradient = CssEffects::gradient($token, $name);
                continue;
            }
            // border-image: <gradient> 1 / <slice> fill / stretch.
            if ($property === 'border-image' && in_array($lower, ['1', '100%', 'fill', 'stretch', '/'], true)) {
                continue;
            }
            throw new RuntimeException(
                "Native {$property} in {$name} supports a linear or radial gradient stroke (slice 1); {$token} is unsupported.",
            );
        }
        $output['borderGradient'] = $gradient === null ? '' : CssEffects::encode([$gradient]);
    }

    /** @param array<string, string|int|bool> $output */
    private static function transition(array &$output, string $property, string $value, string $name): void
    {
        $lower = strtolower(trim($value));
        $easings = [
            'linear' => 1, 'ease-in' => 2, 'ease-out' => 3, 'ease-in-out' => 4, 'ease' => 4,
            'step-start' => 1, 'step-end' => 1,
        ];
        $time = static function (string $part) use ($name): int {
            if (preg_match('/^((?:\d+|\d*\.\d+))(ms|s)$/D', $part, $match) !== 1) {
                throw new RuntimeException("Invalid transition time {$part} in {$name}.");
            }
            return (int) round((float) $match[1] * ($match[2] === 's' ? 1000 : 1));
        };
        if ($property === 'transition-property') {
            $output['animate'] = $lower !== 'none';
            return;
        }
        if ($property === 'transition-duration') {
            $output['animationDuration'] = $time(trim(explode(',', $lower)[0]));
            $output['animate'] ??= true;
            return;
        }
        if ($property === 'transition-timing-function') {
            $function = trim(explode(',', $lower)[0]);
            $output['animationEasing'] = $easings[$function] ?? (str_starts_with($function, 'cubic-bezier(') ? 4 : throw new RuntimeException("Unsupported transition-timing-function {$function} in {$name}."));
            return;
        }
        if ($property === 'transition-delay') {
            if ($time(trim(explode(',', $lower)[0])) !== 0) {
                throw new RuntimeException("Native transitions start immediately; transition-delay is unsupported in {$name}.");
            }
            return;
        }
        if ($property === 'transition-behavior') {
            return;
        }
        if ($property !== 'transition') {
            throw new RuntimeException("Unsupported native CSS property {$property} in {$name}.");
        }
        if ($lower === 'none') {
            $output['animate'] = false;
            return;
        }
        $duration = null;
        $easing = null;
        foreach (self::splitTopLevel($lower, ',') as $layer) {
            $times = [];
            foreach (self::cssValueParts($layer, $name) as $part) {
                if (preg_match('/^(?:\d+|\d*\.\d+)(?:ms|s)$/D', $part) === 1) {
                    $times[] = $time($part);
                } elseif (isset($easings[$part])) {
                    $easing ??= $easings[$part];
                } elseif (str_starts_with($part, 'cubic-bezier(') || str_starts_with($part, 'steps(')) {
                    $easing ??= 4;
                }
            }
            if (($times[1] ?? 0) !== 0) {
                throw new RuntimeException("Native transitions start immediately; transition delays are unsupported in {$name}.");
            }
            $duration = max($duration ?? 0, $times[0] ?? 0);
        }
        $output['animate'] = ($duration ?? 0) > 0;
        $output['animationDuration'] = $duration ?? 0;
        $output['animationEasing'] = $easing ?? 4;
    }

    /** @return list<string> */
    private static function splitTopLevel(string $value, string $separator): array
    {
        $parts = [];
        $depth = 0;
        $start = 0;
        $length = strlen($value);
        for ($index = 0; $index < $length; $index++) {
            if ($value[$index] === '(') {
                $depth++;
            } elseif ($value[$index] === ')') {
                $depth--;
            } elseif ($value[$index] === $separator && $depth === 0) {
                $parts[] = trim(substr($value, $start, $index - $start));
                $start = $index + 1;
            }
        }
        $parts[] = trim(substr($value, $start));

        return array_values(array_filter($parts, static fn (string $part): bool => $part !== ''));
    }

    /** @param array<string, string|int|bool> $output */
    private static function expandBoxShadow(
        array &$output,
        string $value,
        string $name,
    ): void {
        $trimmed = trim($value);
        $shadows = strtolower($trimmed) === 'none' ? [] : CssEffects::boxShadows($trimmed, $name);
        // The first outer shadow keeps the legacy single-shadow keys (iOS and
        // older hosts); the full list only travels when it adds information.
        $outer = null;
        foreach ($shadows as $shadow) {
            if ($shadow[5] === 0) {
                $outer = $shadow;
                break;
            }
        }
        $output['shadowOffsetX'] = CssEffects::number($outer[0] ?? 0.0);
        $output['shadowOffsetY'] = CssEffects::number($outer[1] ?? 0.0);
        $output['shadowBlurRadius'] = CssEffects::number($outer[2] ?? 0.0);
        $output['shadowSpreadRadius'] = CssEffects::number($outer[3] ?? 0.0);
        $output['shadowColor'] = $outer[4] ?? 0;
        $output['boxShadows'] = count($shadows) > 1 || ($shadows !== [] && $outer === null)
            ? json_encode($shadows, JSON_THROW_ON_ERROR | JSON_PRESERVE_ZERO_FRACTION)
            : '';
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
        if (in_array($property, [
            '-pam-native-background-color',
            '-pam-native-text-color',
            '-pam-native-border-color',
        ], true)) {
            $resource = self::unquote(trim($value));
            if (preg_match('/^[A-Za-z][A-Za-z0-9_.-]{0,127}$/D', $resource) !== 1) {
                throw new RuntimeException("Invalid native color resource in {$name}.");
            }
            return $resource;
        }
        if ($property === 'display') {
            return match (strtolower($value)) {
                'none' => false,
                'flex', 'grid' => true,
                default => throw new RuntimeException(
                    "Native display in {$name} supports flex, grid, or none.",
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
        if (StyleValueCompiler::isDynamic($trimmed)) {
            return StyleValueCompiler::encode($trimmed, $name);
        }
        if (preg_match('/^-?(?:\d+|\d*\.\d+)(?:px|dp|pt|rem)?$/D', $trimmed) !== 1) {
            throw new RuntimeException("Expected a native numeric CSS value in {$name}, got {$value}.");
        }
        if (str_ends_with($trimmed, 'rem')) {
            return (string) ((float) substr($trimmed, 0, -3) * 16);
        }

        return preg_replace('/(?:px|dp|pt)$/', '', $trimmed) ?? $trimmed;
    }

    private static function gridColumns(string $value, string $name): string
    {
        $trimmed = trim($value);
        if (preg_match('/^repeat\(\s*([1-9]|[1-5][0-9]|6[0-4])\s*,\s*(?:minmax\([^)]*\)|[^)]+)\)$/iD', $trimmed, $match) === 1) {
            return $match[1];
        }
        $tracks = self::cssValueParts($trimmed, $name);
        if ($tracks === [] || count($tracks) > 64) {
            throw new RuntimeException("Native grid-template-columns in {$name} supports 1 through 64 tracks.");
        }
        foreach ($tracks as $track) {
            if (preg_match('/^(?:\d+(?:\.\d+)?fr|auto|min-content|max-content|minmax\([^)]*\))$/iD', $track) !== 1) {
                throw new RuntimeException("Unsupported native grid track {$track} in {$name}.");
            }
        }
        return (string) count($tracks);
    }

    /** @param array<string, string|int|bool> $output */
    private static function expandTransform(
        array &$output,
        string $value,
        string $name,
    ): void {
        $remaining = trim($value);
        if (strtolower($remaining) === 'none') {
            $output['translationX'] = '0';
            $output['translationY'] = '0';
            $output['scaleX'] = '1';
            $output['scaleY'] = '1';
            $output['rotation'] = '0';
            return;
        }
        while ($remaining !== '') {
            if (preg_match('/^([A-Za-z0-9]+)\(((?:[^()]|\([^()]*\))*)\)\s*/D', $remaining, $match) !== 1) {
                throw new RuntimeException("Invalid transform in {$name}: {$value}.");
            }
            $function = strtolower($match[1]);
            $arguments = array_map('trim', self::splitTopLevel(trim($match[2]), ','));
            if (count($arguments) === 1 && !str_contains($match[2], ',')) {
                $arguments = self::cssValueParts(trim($match[2]), $name);
            }
            $argument = $arguments[0] ?? '';
            match ($function) {
                'translate' => self::translate($output, $arguments[0] ?? '0', $arguments[1] ?? '0', $name),
                'translate3d' => (($arguments[2] ?? '0') !== '0' && ($arguments[2] ?? '0') !== '0px')
                    ? throw new RuntimeException("3D translateZ is unsupported natively in {$name}.")
                    : self::translate($output, $arguments[0] ?? '0', $arguments[1] ?? '0', $name),
                'translatex' => self::translateAxis($output, 'X', $argument, $name),
                'translatey' => self::translateAxis($output, 'Y', $argument, $name),
                'scale' => self::setScale($output, $argument, $arguments[1] ?? $argument, $name),
                'scale3d' => self::setScale($output, $argument, $arguments[1] ?? $argument, $name),
                'scalex' => $output['scaleX'] = self::scale($argument, $name),
                'scaley' => $output['scaleY'] = self::scale($argument, $name),
                'rotate', 'rotatez' => $output['rotation'] = self::angle($argument, $name),
                'matrix' => self::matrix($output, $arguments, $name),
                default => throw new RuntimeException(
                    "Unsupported transform function {$match[1]}() in {$name}; native views support translate, scale, rotate and non-skewing matrix().",
                ),
            };
            $remaining = ltrim(substr($remaining, strlen($match[0])));
        }
    }

    /** @param array<string, string|int|bool> $output */
    private static function translate(array &$output, string $x, string $y, string $name): void
    {
        self::translateAxis($output, 'X', $x, $name);
        self::translateAxis($output, 'Y', $y, $name);
    }

    /** @param array<string, string|int|bool> $output */
    private static function translateAxis(array &$output, string $axis, string $value, string $name): void
    {
        unset($output['translation'.$axis.'Percent']);
        if (self::isPercentage($value)) {
            // Percentages are relative to the element's own box.
            $output['translation'.$axis] = '0';
            $output['translation'.$axis.'Percent'] = self::percentage($value, $name);
            return;
        }
        $output['translation'.$axis] = self::scalar($value, $name);
    }

    private static function scale(string $value, string $name): string
    {
        return self::isPercentage($value)
            ? (string) ((float) self::percentage($value, $name) / 100)
            : self::scalar($value, $name);
    }

    /**
     * Decomposes a 2D affine matrix into translate/rotate/scale. Skewing
     * matrices have no native view-property equivalent and are rejected.
     *
     * @param array<string, string|int|bool> $output
     * @param list<string> $arguments
     */
    private static function matrix(array &$output, array $arguments, string $name): void
    {
        if (count($arguments) !== 6) {
            throw new RuntimeException("matrix() expects six numbers in {$name}.");
        }
        $values = array_map(static fn (string $value): float => (float) self::scalar($value, $name), $arguments);
        [$a, $b, $c, $d, $e, $f] = $values;
        $scaleX = sqrt($a * $a + $b * $b);
        if ($scaleX === 0.0 || abs($a * $c + $b * $d) > 1e-6 * max(1.0, $scaleX)) {
            throw new RuntimeException("Skewing matrix() is unsupported natively in {$name}.");
        }
        $output['scaleX'] = (string) round($scaleX, 6);
        $output['scaleY'] = (string) round(($a * $d - $b * $c) / $scaleX, 6);
        $output['rotation'] = (string) round(rad2deg(atan2($b, $a)), 6);
        $output['translationX'] = (string) round($e, 6);
        $output['translationY'] = (string) round($f, 6);
    }

    /** @param array<string, string|int|bool> $output */
    private static function setScale(
        array &$output,
        string $x,
        string $y,
        string $name,
    ): void {
        $output['scaleX'] = self::scale($x, $name);
        $output['scaleY'] = self::scale($y, $name);
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
