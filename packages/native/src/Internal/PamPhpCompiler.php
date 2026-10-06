<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

use Pam\Native\Style\StyleScope;
use FilesystemIterator;
use JsonException;
use RecursiveDirectoryIterator;
use RecursiveIteratorIterator;
use ReflectionClass;
use RuntimeException;
use SplFileInfo;
use Pam\Native\BuildConfiguration;
use Pam\Native\LanguageVersion;
use Pam\Native\UI\Ir\UiIr;

final class PamPhpCompiler
{
    private const MAX_COMPONENTS = 10_000;
    private const MAX_SOURCE_BYTES = 2_097_152;
    private const CACHE_VERSION = 5;

    /**
     * Content fingerprints of stylesheets read during one compileDirectory()
     * pass; every component shares src/app.css, so it is hashed once.
     *
     * @var array<string, string>
     */
    private static array $dependencyFingerprints = [];

    /** @var array<string, ?string> app.css location per component directory */
    private static array $appStylePaths = [];

    /** Memoizes the two maps above only within one directory pass. */
    private static bool $directoryPass = false;

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

        self::$dependencyFingerprints = [];
        self::$appStylePaths = [];
        self::$directoryPass = true;
        try {
            return array_map(
                static fn (string $source): PamPhpComponent =>
                    self::compileFile($source, $cachePath),
                $sources,
            );
        } finally {
            self::$dependencyFingerprints = [];
            self::$appStylePaths = [];
            self::$directoryPass = false;
        }
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

        $cacheKey = hash('sha256', $source);
        $classFile = rtrim($cachePath, DIRECTORY_SEPARATOR)
            .DIRECTORY_SEPARATOR.$cacheKey.'.class.php';
        $templateFile = rtrim($cachePath, DIRECTORY_SEPARATOR)
            .DIRECTORY_SEPARATOR.$cacheKey.'.template.json';
        $metadataFile = rtrim($cachePath, DIRECTORY_SEPARATOR)
            .DIRECTORY_SEPARATOR.$cacheKey.'.json';
        $sourceFingerprint = hash('xxh128', $contents);

        // Components compiled into the application bundle at build time
        // (relative identities, fingerprint-validated) need no compilation.
        $prebuilt = self::prebuiltComponent($source, $sourceFingerprint);
        if ($prebuilt !== null) {
            return $prebuilt;
        }

        // Boot fast path: an unchanged source (and unchanged stylesheets it
        // pulled in) reuses its compiled identity without tokenizing the
        // component again; the template tree is decoded on first render.
        $fresh = self::freshComponent(
            $source,
            $sourceFingerprint,
            $metadataFile,
            $templateFile,
            $classFile,
        );
        if ($fresh !== null) {
            return $fresh;
        }

        [$php, $template, $templateLine, $style, $language, $styleScope] =
            self::split($contents, $source);
        $imports = ScopedStyleCompiler::collectImports();
        try {
            $style = self::resolvedStyleSheet($style, $source, $contents);
        } finally {
            $imported = ScopedStyleCompiler::finishCollectingImports($imports);
        }
        self::validateHotPath($php, $source);
        [$className, $tag] = self::classIdentity($php, $source);
        $hash = hash('sha256', $contents."\0".$style."\0".$styleScope->value);
        $dependencies = [];
        $appStyle = self::appStylePath($source);
        foreach (array_unique([...($appStyle === null ? [] : [$appStyle]), ...$imported]) as $dependency) {
            $dependencies[$dependency] = self::dependencyFingerprint($dependency);
        }
        $tree = self::cachedTree(
            $metadataFile,
            $templateFile,
            $classFile,
            $hash,
            $className,
        );
        $fingerprint = [
            'sourceFingerprint' => $sourceFingerprint,
            'appStyle' => $appStyle,
            'dependencies' => $dependencies,
        ];

        if ($tree !== null) {
            // Same compiled output, e.g. after the bundle was re-extracted:
            // record the fingerprint so the next boot takes the fast path.
            self::writeMetadata($metadataFile, $source, $hash, $className, $tag, $language, $fingerprint);
        } else {
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
            } catch (JsonException $error) {
                throw new RuntimeException(
                    "Cannot encode PAM component cache for {$source}.",
                    previous: $error,
                );
            }
            self::writeAtomic($templateFile, $encodedTree."\n");
            self::writeExpressions(self::expressionsFile($templateFile), $tree);
            self::writeMetadata($metadataFile, $source, $hash, $className, $tag, $language, $fingerprint);
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
     * @param array{sourceFingerprint: string, appStyle: ?string, dependencies: array<string, ?string>} $fingerprint
     */
    private static function writeMetadata(
        string $metadataFile,
        string $source,
        string $hash,
        string $className,
        string $tag,
        LanguageVersion $language,
        array $fingerprint,
    ): void {
        try {
            $encodedMetadata = json_encode([
                'version' => self::CACHE_VERSION,
                'language' => $language->value,
                'uiIr' => UiIr::manifest($language),
                'hash' => $hash,
                'class' => $className,
                'tag' => $tag,
                'strict' => BuildConfiguration::strict(),
                ...$fingerprint,
            ], JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES);
        } catch (JsonException $error) {
            throw new RuntimeException(
                "Cannot encode PAM component cache for {$source}.",
                previous: $error,
            );
        }
        self::writeAtomic($metadataFile, $encodedMetadata."\n");
    }

    /**
     * Returns the cached component when its source, the project stylesheet
     * location and every stylesheet it imported still have the fingerprints
     * recorded at compile time, under the same compiler settings.
     */
    private static function freshComponent(
        string $source,
        string $sourceFingerprint,
        string $metadataFile,
        string $templateFile,
        string $classFile,
    ): ?PamPhpComponent {
        $encoded = @file_get_contents($metadataFile);
        if ($encoded === false) {
            return null;
        }
        try {
            $metadata = json_decode($encoded, true, 16, JSON_THROW_ON_ERROR);
        } catch (JsonException) {
            return null;
        }
        if (
            !is_array($metadata)
            || ($metadata['version'] ?? null) !== self::CACHE_VERSION
            || ($metadata['sourceFingerprint'] ?? null) !== $sourceFingerprint
            || ($metadata['strict'] ?? null) !== BuildConfiguration::strict()
            || !is_string($metadata['class'] ?? null)
            || !is_string($metadata['tag'] ?? null)
            || !is_int($metadata['language'] ?? null)
            || !is_array($metadata['dependencies'] ?? null)
            || !array_key_exists('appStyle', $metadata)
            || $metadata['appStyle'] !== self::appStylePath($source)
        ) {
            return null;
        }
        $language = LanguageVersion::tryFrom($metadata['language']);
        if ($language === null || ($metadata['uiIr'] ?? null) !== UiIr::manifest($language)) {
            return null;
        }
        foreach ($metadata['dependencies'] as $dependency => $fingerprint) {
            if (self::dependencyFingerprint((string) $dependency) !== $fingerprint) {
                return null;
            }
        }
        if (!is_file($classFile) || !is_file($templateFile)) {
            return null;
        }

        return new PamPhpComponent(
            className: $metadata['class'],
            tag: $metadata['tag'],
            source: $source,
            classFile: $classFile,
            template: self::lazyTemplate($templateFile, $source),
            language: $language,
        );
    }

    private static function lazyTemplate(string $templateFile, string $source, bool $relocated = false): CompiledTemplateNode
    {
        return (new ReflectionClass(CompiledTemplateNode::class))->newLazyProxy(
            static function () use ($templateFile, $source, $relocated): CompiledTemplateNode {
                TemplateExpressionCatalog::load(self::expressionsFile($templateFile));
                try {
                    $tree = CompiledTemplateNode::hydrate(json_decode(
                        (string) file_get_contents($templateFile),
                        true,
                        512,
                        JSON_THROW_ON_ERROR,
                    ), $relocated ? $source : null);
                } catch (JsonException $error) {
                    throw new RuntimeException("PAM component cache for {$source} is corrupt.", previous: $error);
                }
                if ($tree === null) {
                    throw new RuntimeException("PAM component cache for {$source} is corrupt.");
                }

                return $tree;
            },
        );
    }

    private static function expressionsFile(string $templateFile): string
    {
        return substr($templateFile, 0, -strlen('.template.json')).'.expressions.php';
    }

    private static function writeExpressions(string $file, CompiledTemplateNode $tree): void
    {
        try {
            self::writeAtomic($file, TemplateExpressionCatalog::source($tree));
        } catch (RuntimeException) {
            // Expressions still compile on first use.
        }
    }

    /**
     * Directory of the components precompiled into the application bundle,
     * relative to the project root (the directory holding composer.json).
     */
    public const PREBUILT_DIRECTORY = 'pam-prebuilt/components';

    /** @var array<string, ?string> project root per component directory */
    private static array $projectRoots = [];

    /** @var array<string, bool> */
    private static array $prebuiltDirectories = [];

    private static function projectRoot(string $component): ?string
    {
        $directory = dirname($component);
        if (array_key_exists($directory, self::$projectRoots)) {
            return self::$projectRoots[$directory];
        }
        $root = null;
        $cursor = $directory;
        while (true) {
            if (is_file($cursor.DIRECTORY_SEPARATOR.'composer.json')) {
                $root = $cursor;
                break;
            }
            $parent = dirname($cursor);
            if ($parent === $cursor) {
                break;
            }
            $cursor = $parent;
        }
        if (count(self::$projectRoots) >= 1024) {
            self::$projectRoots = [];
        }

        return self::$projectRoots[$directory] = $root;
    }

    private static function relativeTo(string $root, string $path): ?string
    {
        $prefix = rtrim($root, DIRECTORY_SEPARATOR).DIRECTORY_SEPARATOR;

        return str_starts_with($path, $prefix) ? substr($path, strlen($prefix)) : null;
    }

    /**
     * A bundle-precompiled component whose source and stylesheets still have
     * the fingerprints recorded at build time, or null.
     */
    private static function prebuiltComponent(string $source, string $sourceFingerprint): ?PamPhpComponent
    {
        $root = self::projectRoot($source);
        if ($root === null) {
            return null;
        }
        $directory = $root.DIRECTORY_SEPARATOR.self::PREBUILT_DIRECTORY;
        if (!(self::$prebuiltDirectories[$directory] ??= is_dir($directory))) {
            return null;
        }
        $relative = self::relativeTo($root, $source);
        if ($relative === null) {
            return null;
        }
        $key = hash('sha256', str_replace(DIRECTORY_SEPARATOR, '/', $relative));
        $base = $directory.DIRECTORY_SEPARATOR.$key;
        $encoded = @file_get_contents($base.'.json');
        if ($encoded === false) {
            return null;
        }
        try {
            $metadata = json_decode($encoded, true, 16, JSON_THROW_ON_ERROR);
        } catch (JsonException) {
            return null;
        }
        if (
            !is_array($metadata)
            || ($metadata['version'] ?? null) !== self::CACHE_VERSION
            || ($metadata['relocatable'] ?? null) !== true
            || ($metadata['sourceFingerprint'] ?? null) !== $sourceFingerprint
            || ($metadata['strict'] ?? null) !== BuildConfiguration::strict()
            || !is_string($metadata['class'] ?? null)
            || !is_string($metadata['tag'] ?? null)
            || !is_int($metadata['language'] ?? null)
            || !is_array($metadata['dependencies'] ?? null)
            || !array_key_exists('appStyle', $metadata)
        ) {
            return null;
        }
        $appStyle = self::appStylePath($source);
        $relativeAppStyle = $appStyle === null ? null : self::relativeTo($root, $appStyle);
        if ($relativeAppStyle !== null) {
            $relativeAppStyle = str_replace(DIRECTORY_SEPARATOR, '/', $relativeAppStyle);
        }
        if ($metadata['appStyle'] !== $relativeAppStyle) {
            return null;
        }
        $language = LanguageVersion::tryFrom($metadata['language']);
        if ($language === null || ($metadata['uiIr'] ?? null) !== UiIr::manifest($language)) {
            return null;
        }
        foreach ($metadata['dependencies'] as $dependency => $fingerprint) {
            $path = $root.DIRECTORY_SEPARATOR.str_replace('/', DIRECTORY_SEPARATOR, (string) $dependency);
            if (self::dependencyFingerprint($path) !== $fingerprint) {
                return null;
            }
        }
        $classFile = $base.'.class.php';
        $templateFile = $base.'.template.json';
        if (!is_file($classFile) || !is_file($templateFile)) {
            return null;
        }

        return new PamPhpComponent(
            className: $metadata['class'],
            tag: $metadata['tag'],
            source: $source,
            classFile: $classFile,
            template: self::lazyTemplate($templateFile, $source, true),
            language: $language,
        );
    }

    /**
     * Precompiles every component under $sourcePath into the relocatable
     * bundle cache of $projectRoot (PREBUILT_DIRECTORY): class, template and
     * expression files keyed by project-relative source path, with metadata
     * whose stylesheet dependencies are project-relative too.
     *
     * @return int number of components written
     */
    public static function prebuild(string $projectRoot, string $sourcePath): int
    {
        $root = realpath($projectRoot);
        if ($root === false || !is_file($root.DIRECTORY_SEPARATOR.'composer.json')) {
            throw new RuntimeException("PAM prebuild root {$projectRoot} is not a Composer project.");
        }
        $output = $root.DIRECTORY_SEPARATOR.self::PREBUILT_DIRECTORY;
        $staging = sys_get_temp_dir().DIRECTORY_SEPARATOR.'pam-prebuild-'.bin2hex(random_bytes(8));
        if (is_dir($output)) {
            self::removeTree($output);
        }
        try {
            $components = self::compileDirectory($sourcePath, $staging);
            if (!is_dir($output) && !mkdir($output, 0o755, true) && !is_dir($output)) {
                throw new RuntimeException("Cannot create PAM prebuild directory {$output}.");
            }
            foreach ($components as $component) {
                $relative = self::relativeTo($root, $component->source);
                if ($relative === null) {
                    continue;
                }
                $relative = str_replace(DIRECTORY_SEPARATOR, '/', $relative);
                $compiledBase = substr($component->classFile, 0, -strlen('.class.php'));
                $metadata = json_decode(
                    (string) file_get_contents($compiledBase.'.json'),
                    true,
                    16,
                    JSON_THROW_ON_ERROR,
                );
                if (!is_array($metadata)) {
                    throw new RuntimeException("Invalid compiled metadata for {$component->source}.");
                }
                $dependencies = [];
                foreach ((array) ($metadata['dependencies'] ?? []) as $dependency => $fingerprint) {
                    $dependencyRelative = self::relativeTo($root, (string) $dependency);
                    if ($dependencyRelative === null) {
                        // Stylesheets outside the bundle cannot be validated on device.
                        continue 2;
                    }
                    $dependencies[str_replace(DIRECTORY_SEPARATOR, '/', $dependencyRelative)] = $fingerprint;
                }
                $appStyle = $metadata['appStyle'] ?? null;
                if (is_string($appStyle)) {
                    $appStyle = self::relativeTo($root, $appStyle);
                    if ($appStyle === null) {
                        continue;
                    }
                    $appStyle = str_replace(DIRECTORY_SEPARATOR, '/', $appStyle);
                }
                $metadata['appStyle'] = $appStyle;
                $metadata['dependencies'] = $dependencies;
                $metadata['relocatable'] = true;
                $base = $output.DIRECTORY_SEPARATOR.hash('sha256', $relative);
                $template = self::bundleTemplate(
                    (string) file_get_contents($compiledBase.'.template.json'),
                    $root,
                );
                self::writeAtomic($base.'.class.php', (string) file_get_contents($component->classFile));
                self::writeAtomic($base.'.template.json', $template);
                self::writeExpressions($base.'.expressions.php', $component->template);
                self::writeAtomic($base.'.json', json_encode($metadata, JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES)."\n");
            }

            self::$prebuiltDirectories = [];

            return count(glob($output.DIRECTORY_SEPARATOR.'*.class.php') ?: []);
        } finally {
            if (is_dir($staging)) {
                self::removeTree($staging);
            }
        }
    }

    /**
     * The runtime form of a compiled template: style IR, bytecode, source
     * maps and compatibility reports serve tooling only, and diagnostic
     * paths become project-relative (node sources are restored to the
     * installed location when hydrated).
     */
    private static function bundleTemplate(string $encoded, string $root): string
    {
        $tree = json_decode($encoded, true, 512, JSON_THROW_ON_ERROR);
        if (is_array($tree) && is_string($tree['attributes']['__pamStyles'] ?? null)) {
            $styles = json_decode($tree['attributes']['__pamStyles'], true, 512, JSON_THROW_ON_ERROR);
            if (is_array($styles)) {
                unset(
                    $styles['styleIr'],
                    $styles['styleBytecode'],
                    $styles['styleSourceMap'],
                    $styles['styleCompatibility'],
                );
                $tree['attributes']['__pamStyles'] = json_encode(
                    $styles,
                    JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES,
                );
            }
            $encoded = json_encode($tree, JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES)."\n";
        }

        return str_replace($root.DIRECTORY_SEPARATOR, '', $encoded);
    }

    private static function removeTree(string $directory): void
    {
        foreach (new RecursiveIteratorIterator(
            new RecursiveDirectoryIterator($directory, FilesystemIterator::SKIP_DOTS),
            RecursiveIteratorIterator::CHILD_FIRST,
        ) as $entry) {
            if (!$entry instanceof SplFileInfo) {
                continue;
            }
            $entry->isDir() && !$entry->isLink() ? rmdir($entry->getPathname()) : unlink($entry->getPathname());
        }
        rmdir($directory);
    }

    private static function dependencyFingerprint(string $path): ?string
    {
        if (array_key_exists($path, self::$dependencyFingerprints)) {
            return self::$dependencyFingerprints[$path];
        }
        $contents = is_file($path) ? file_get_contents($path) : false;
        $fingerprint = $contents === false ? null : hash('xxh128', $contents);
        if (self::$directoryPass) {
            self::$dependencyFingerprints[$path] = $fingerprint;
        }

        return $fingerprint;
    }

    /**
     * Prepends the conventional project-wide `src/app.css` sheet to a
     * component's local scoped CSS. Both sources are expanded independently so
     * relative imports keep resolving from the file that declared them.
     */
    private static function resolvedStyleSheet(
        string $localStyle,
        string $component,
        string $componentSource = '',
    ): string {
        $sources = [];
        $appStyle = self::appStylePath($component);
        if ($appStyle !== null) {
            $contents = file_get_contents($appStyle);
            if ($contents === false) {
                throw new RuntimeException("Cannot read PAM application stylesheet {$appStyle}.");
            }
            $sources[] = ScopedStyleCompiler::sourceMarker(self::displayPath($appStyle, $component), 1)
                .ScopedStyleCompiler::resolveImports($contents, $appStyle);
        }
        if ($localStyle !== '') {
            $styleOffset = $componentSource === '' ? false : strpos($componentSource, $localStyle);
            $styleLine = $styleOffset === false
                ? 1
                : substr_count(substr($componentSource, 0, $styleOffset), "\n") + 1;
            $sources[] = ScopedStyleCompiler::sourceMarker(self::displayPath($component, $component), $styleLine)
                .ScopedStyleCompiler::resolveImports($localStyle, $component);
        }

        return implode("\n", $sources);
    }

    /** Project-relative path for diagnostics (absolute paths stay out of caches). */
    private static function displayPath(string $path, string $component): string
    {
        $resolved = realpath($path);
        $path = is_string($resolved) ? $resolved : $path;
        $directory = dirname((string) (realpath($component) ?: $component));
        while (true) {
            if (is_file($directory.DIRECTORY_SEPARATOR.'composer.json')) {
                $prefix = rtrim($directory, DIRECTORY_SEPARATOR).DIRECTORY_SEPARATOR;
                return str_starts_with($path, $prefix) ? substr($path, strlen($prefix)) : $path;
            }
            $parent = dirname($directory);
            if ($parent === $directory) {
                return $path;
            }
            $directory = $parent;
        }
    }

    private static function appStylePath(string $component): ?string
    {
        $directory = dirname($component);
        if (array_key_exists($directory, self::$appStylePaths)) {
            return self::$appStylePaths[$directory];
        }

        $path = self::locateAppStyle($directory);
        if (self::$directoryPass) {
            self::$appStylePaths[$directory] = $path;
        }

        return $path;
    }

    private static function locateAppStyle(string $directory): ?string
    {
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
            || ($metadata['version'] ?? null) !== self::CACHE_VERSION
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
