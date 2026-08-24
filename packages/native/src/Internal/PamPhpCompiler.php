<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

use Pam\Native\Style\StyleScope;
use FilesystemIterator;
use JsonException;
use RecursiveDirectoryIterator;
use RecursiveIteratorIterator;
use RuntimeException;
use SplFileInfo;
use Pam\Native\BuildConfiguration;
use Pam\Native\LanguageVersion;
use Pam\Native\UI\Ir\UiIr;

final class PamPhpCompiler
{
    private const MAX_COMPONENTS = 10_000;
    private const MAX_SOURCE_BYTES = 2_097_152;

    private function __construct()
    {
    }

    /** @return list<PamPhpComponent> */
    public static function compileDirectory(
        string $sourcePath,
        string $cachePath,
    ): array {
        $sourceRoot = realpath($sourcePath);

        if ($sourceRoot === false || !is_dir($sourceRoot)) {
            throw new RuntimeException(
                "PAM component directory {$sourcePath} does not exist.",
            );
        }
        if (str_contains($cachePath, "\0")) {
            throw new RuntimeException('PAM component cache path is invalid.');
        }
        if (
            !is_dir($cachePath)
            && !mkdir($cachePath, 0o755, true)
            && !is_dir($cachePath)
        ) {
            throw new RuntimeException(
                "Cannot create PAM component cache {$cachePath}.",
            );
        }

        $iterator = new RecursiveIteratorIterator(
            new RecursiveDirectoryIterator(
                $sourceRoot,
                FilesystemIterator::SKIP_DOTS,
            ),
        );
        $sources = [];

        foreach ($iterator as $file) {
            if (!$file instanceof SplFileInfo) {
                continue;
            }
            if ($file->isLink()) {
                throw new RuntimeException(
                    "PAM component directories cannot contain symlinks: {$file->getPathname()}.",
                );
            }
            if (!$file->isFile() || !self::isComponentFile($file->getFilename())) {
                continue;
            }
            $resolved = $file->getRealPath();
            if (
                $resolved === false
                || !str_starts_with($resolved, $sourceRoot.DIRECTORY_SEPARATOR)
            ) {
                throw new RuntimeException('PAM component escaped its source directory.');
            }
            $sources[] = $resolved;

            if (count($sources) > self::MAX_COMPONENTS) {
                throw new RuntimeException('PAM component directory exceeds 10,000 files.');
            }
        }

        sort($sources, SORT_STRING);

        return array_map(
            static fn (string $source): PamPhpComponent =>
                self::compileFile($source, $cachePath),
            $sources,
        );
    }

    private static function isComponentFile(string $filename): bool
    {
        return str_ends_with($filename, '.pam')
            || str_ends_with($filename, '.pam.php');
    }

    public static function compileFile(
        string $source,
        string $cachePath,
    ): PamPhpComponent {
        $contents = file_get_contents($source);

        if ($contents === false) {
            throw new RuntimeException("Cannot read PAM component {$source}.");
        }
        if (strlen($contents) > self::MAX_SOURCE_BYTES) {
            throw new RuntimeException(
                "PAM component {$source} exceeds two megabytes.",
            );
        }

        [$php, $template, $templateLine, $style, $language, $styleScope] =
            self::split($contents, $source);
        $style = self::resolvedStyleSheet($style, $source);
        self::validateHotPath($php, $source);
        [$className, $tag] = self::classIdentity($php, $source);
        $cacheKey = hash('sha256', $source);
        $classFile = rtrim($cachePath, DIRECTORY_SEPARATOR)
            .DIRECTORY_SEPARATOR.$cacheKey.'.class.php';
        $templateFile = rtrim($cachePath, DIRECTORY_SEPARATOR)
            .DIRECTORY_SEPARATOR.$cacheKey.'.template.json';
        $metadataFile = rtrim($cachePath, DIRECTORY_SEPARATOR)
            .DIRECTORY_SEPARATOR.$cacheKey.'.json';
        $hash = hash('sha256', $contents."\0".$style."\0".$styleScope->value);
        $tree = self::cachedTree(
            $metadataFile,
            $templateFile,
            $classFile,
            $hash,
            $className,
        );

        if ($tree === null) {
            $tree = TemplateCompiler::compile(
                str_repeat("\n", max(0, $templateLine - 1)).$template,
                $source,
                $language,
            );
            if ($style !== '') {
                $tree = self::withStyleSheet(
                    $tree,
                    ScopedStyleCompiler::compile($style, $source, $styleScope),
                );
            }
            self::writeAtomic($classFile, rtrim($php)."\n");
            try {
                $encodedTree = json_encode(
                    $tree->toArray(),
                    JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES,
                );
                $encodedMetadata = json_encode([
                    'version' => 4,
                    'language' => $language->value,
                    'uiIr' => UiIr::manifest($language),
                    'hash' => $hash,
                    'class' => $className,
                    'tag' => $tag,
                    'strict' => BuildConfiguration::strict(),
                ], JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES);
            } catch (JsonException $error) {
                throw new RuntimeException(
                    "Cannot encode PAM component cache for {$source}.",
                    previous: $error,
                );
            }
            self::writeAtomic($templateFile, $encodedTree."\n");
            self::writeAtomic($metadataFile, $encodedMetadata."\n");
        }

        return new PamPhpComponent(
            className: $className,
            tag: $tag,
            source: $source,
            classFile: $classFile,
            template: $tree,
            language: $language,
        );
    }

    /**
     * Prepends the conventional project-wide `src/app.css` sheet to a
     * component's local scoped CSS. Both sources are expanded independently so
     * relative imports keep resolving from the file that declared them.
     */
    private static function resolvedStyleSheet(
        string $localStyle,
        string $component,
    ): string {
        $sources = [];
        $appStyle = self::appStylePath($component);
        if ($appStyle !== null) {
            $contents = file_get_contents($appStyle);
            if ($contents === false) {
                throw new RuntimeException("Cannot read PAM application stylesheet {$appStyle}.");
            }
            $sources[] = ScopedStyleCompiler::resolveImports($contents, $appStyle);
        }
        if ($localStyle !== '') {
            $sources[] = ScopedStyleCompiler::resolveImports($localStyle, $component);
        }

        return implode("\n", $sources);
    }

    private static function appStylePath(string $component): ?string
    {
        $directory = dirname($component);
        while (true) {
            $manifest = $directory.DIRECTORY_SEPARATOR.'composer.json';
            if (is_file($manifest)) {
                $candidate = $directory.DIRECTORY_SEPARATOR.'src'
                    .DIRECTORY_SEPARATOR.'app.css';
                if (!is_file($candidate)) {
                    return null;
                }
                $resolved = realpath($candidate);
                if (!is_string($resolved)) {
                    throw new RuntimeException(
                        "Cannot resolve PAM application stylesheet {$candidate}.",
                    );
                }

                return $resolved;
            }
            $parent = dirname($directory);
            if ($parent === $directory) {
                return null;
            }
            $directory = $parent;
        }
    }

    private static function validateHotPath(string $php, string $source): void
    {
        if (!BuildConfiguration::strict()) {
            return;
        }
        if (preg_match('/\\$this\\s*->\\s*\\{/', $php) === 1) {
            throw new RuntimeException(
                "PAM2104 {$source}: dynamic component property access prevents dependency tracking.",
            );
        }
        if (preg_match('/\\b(eval|extract)\\s*\\(/i', $php, $match) === 1) {
            throw new RuntimeException(
                "PAM2105 {$source}: {$match[1]} is not allowed in strict production components.",
            );
        }
    }

    /** @return array{string, string, int, string, LanguageVersion, StyleScope} */
    private static function split(string $source, string $name): array
    {
        $tokens = token_get_all($source);
        $offset = 0;
        $closeOffset = null;
        $closeLength = 0;

        foreach ($tokens as $token) {
            $text = is_array($token) ? $token[1] : $token;

            if (
                is_array($token)
                && $token[0] === T_CLOSE_TAG
            ) {
                $closeOffset = $offset;
                $closeLength = strlen($text);
                break;
            }
            $offset += strlen($text);
        }

        if ($closeOffset === null) {
            throw new RuntimeException(
                "PAM component {$name} must close its PHP block before <template>.",
            );
        }

        $php = substr($source, 0, $closeOffset);
        $markup = substr($source, $closeOffset + $closeLength);
        $match = [];

        if (preg_match(
            '/\A\s*<template(?:\s+([^>]*))?>([\s\S]*?)<\/template>'
            .'\s*(?:<style(?:\s+(scoped|module|global))?\s*>([\s\S]*?)<\/style>)?\s*\z/D',
            $markup,
            $match,
            PREG_OFFSET_CAPTURE,
        ) !== 1) {
            throw new RuntimeException(
                "PAM component {$name} must contain exactly one root <template> block.",
            );
        }
        $templateAttributes = is_array($match[1] ?? null)
            ? (string) ($match[1][0] ?? '')
            : '';
        $capture = $match[2];
        $styleMode = is_array($match[3] ?? null)
            ? strtolower((string) ($match[3][0] ?? ''))
            : '';
        $style = is_array($match[4] ?? null)
            ? (string) ($match[4][0] ?? '')
            : '';
        $styleScope = match ($styleMode) {
            '', 'scoped' => StyleScope::Scoped,
            'module' => StyleScope::Module,
            'global' => StyleScope::Global,
            default => throw new RuntimeException(
                "Unsupported PAM style scope {$styleMode} in {$name}.",
            ),
        };

        $language = LanguageVersion::Language1;
        if (preg_match('/(?:language|version)\s*=\s*(["\'])2\1/D', trim($templateAttributes)) === 1) {
            $language = LanguageVersion::Language2;
        } elseif (trim($templateAttributes) !== '') {
            throw new RuntimeException(
                "PAM component {$name} has unsupported template attributes; use language=\"2\".",
            );
        }

        $templateOffset = $closeOffset + $closeLength + $capture[1];
        $templateLine = substr_count(substr($source, 0, $templateOffset), "\n") + 1;

        return [$php, $capture[0], $templateLine, $style, $language, $styleScope];
    }

    /**
     * @param array{
     *     classes: array<string, array<string, string|int|bool>>,
     *     tags: array<string, array<string, string|int|bool>>,
     *     classCascade: array<string, array<string, array{order: int, value: string|int|bool}>>,
     *     fonts: array<string, list<array{source: string, weight: string, style: string}>>
     * } $styleSheet
     */
    private static function withStyleSheet(
        CompiledTemplateNode $tree,
        array $styleSheet,
    ): CompiledTemplateNode {
        try {
            $encoded = json_encode(
                $styleSheet,
                JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES,
            );
        } catch (JsonException $error) {
            throw new RuntimeException(
                "Cannot encode scoped PAM styles for {$tree->source}.",
                previous: $error,
            );
        }
        $copy = new CompiledTemplateNode(
            kind: $tree->kind,
            name: $tree->name,
            attributes: [...$tree->attributes, '__pamStyles' => $encoded],
            source: $tree->source,
            line: $tree->line,
            column: $tree->column,
            value: $tree->value,
        );
        $copy->children = $tree->children;

        return $copy;
    }

    /** @return array{string, string} */
    private static function classIdentity(string $php, string $source): array
    {
        $tokens = token_get_all($php);
        $namespace = '';
        $class = null;
        $count = count($tokens);

        for ($index = 0; $index < $count; $index++) {
            $token = $tokens[$index];

            if (!is_array($token)) {
                continue;
            }
            if ($token[0] === T_NAMESPACE) {
                $parts = '';
                for ($cursor = $index + 1; $cursor < $count; $cursor++) {
                    $next = $tokens[$cursor];
                    if ($next === ';' || $next === '{') {
                        break;
                    }
                    if (
                        is_array($next)
                        && in_array(
                            $next[0],
                            [T_STRING, T_NAME_QUALIFIED, T_NS_SEPARATOR],
                            true,
                        )
                    ) {
                        $parts .= $next[1];
                    }
                }
                $namespace = trim($parts, '\\');
                continue;
            }
            if ($token[0] !== T_CLASS) {
                continue;
            }

            $previous = self::previousSignificantToken($tokens, $index);
            if (
                is_array($previous)
                && in_array($previous[0], [T_NEW, T_DOUBLE_COLON], true)
            ) {
                continue;
            }
            for ($cursor = $index + 1; $cursor < $count; $cursor++) {
                $next = $tokens[$cursor];
                if (is_array($next) && $next[0] === T_STRING) {
                    if ($class !== null) {
                        throw new RuntimeException(
                            "PAM component {$source} must declare exactly one class.",
                        );
                    }
                    $class = $next[1];
                    break;
                }
            }
        }

        if ($class === null) {
            throw new RuntimeException(
                "PAM component {$source} must declare one named class.",
            );
        }

        $tag = $class;
        if (preg_match(
            '/#\[\s*(?:(?:[A-Za-z_][A-Za-z0-9_]*\\\\)*)Tag\s*\(\s*(?:name\s*:\s*)?(["\'])([A-Za-z][A-Za-z0-9_.-]{0,127})\1\s*\)\s*\]/D',
            $php,
            $tagMatch,
        ) === 1) {
            $tag = $tagMatch[2];
        }

        return [
            $namespace === '' ? $class : $namespace.'\\'.$class,
            $tag,
        ];
    }

    /**
     * @param list<array{int, string, int}|string> $tokens
     * @return array{int, string, int}|string|null
     */
    private static function previousSignificantToken(array $tokens, int $index): array|string|null
    {
        for ($cursor = $index - 1; $cursor >= 0; $cursor--) {
            $token = $tokens[$cursor];
            if (
                is_array($token)
                && in_array($token[0], [T_WHITESPACE, T_COMMENT, T_DOC_COMMENT], true)
            ) {
                continue;
            }

            return $token;
        }

        return null;
    }

    private static function cachedTree(
        string $metadataFile,
        string $templateFile,
        string $classFile,
        string $hash,
        string $className,
    ): ?CompiledTemplateNode {
        if (
            !is_file($metadataFile)
            || !is_file($templateFile)
            || !is_file($classFile)
        ) {
            return null;
        }

        try {
            $metadata = json_decode(
                (string) file_get_contents($metadataFile),
                true,
                16,
                JSON_THROW_ON_ERROR,
            );
            $template = json_decode(
                (string) file_get_contents($templateFile),
                true,
                512,
                JSON_THROW_ON_ERROR,
            );
        } catch (JsonException) {
            return null;
        }

        if (
            !is_array($metadata)
            || ($metadata['version'] ?? null) !== 4
            || ($metadata['hash'] ?? null) !== $hash
            || ($metadata['class'] ?? null) !== $className
        ) {
            return null;
        }

        return CompiledTemplateNode::hydrate($template);
    }

    private static function writeAtomic(string $path, string $contents): void
    {
        $directory = dirname($path);
        if (
            !is_dir($directory)
            && !mkdir($directory, 0o755, true)
            && !is_dir($directory)
        ) {
            throw new RuntimeException("Cannot create PAM cache {$directory}.");
        }
        $temporary = tempnam($directory, 'pam-component-');

        if (
            $temporary === false
            || file_put_contents($temporary, $contents, LOCK_EX) === false
            || !rename($temporary, $path)
        ) {
            if (is_string($temporary)) {
                @unlink($temporary);
            }
            throw new RuntimeException("Cannot write PAM component cache {$path}.");
        }
        @chmod($path, 0o644);
    }
}
