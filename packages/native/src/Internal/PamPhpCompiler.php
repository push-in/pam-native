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

use function array_key_exists;
use function count;
use function in_array;
use function is_array;
use function is_int;
use function is_string;
use function strlen;

final class PamPhpCompiler
{
    private const MAX_COMPONENTS = 10_000;
    private const MAX_SOURCE_BYTES = 2_097_152;
    private const CACHE_VERSION = 6;

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

        // The cache directory is resolved (and created) only when a component
        // actually compiles: a bundle whose prebuilt pack serves every
        // component never touches it, which an iOS app bundle (read-only on
        // a device) depends on. See cacheDirectory().
        $bundled = self::bundledComponents($sourceRoot, $cachePath);
        if ($bundled !== null) {
            return $bundled;
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
        self::$themePaths = [];
        self::$cacheDirectories = [];
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
            self::$themePaths = [];
            self::$cacheDirectories = [];
            self::$directoryPass = false;
        }
    }

    /**
     * A staged application bundle (manifest.sha256 at its root) ships the
     * prebuilt pack compiled from these very sources on the build host, with
     * the list of every component source. Boot from that listing: no walk of
     * the source tree and no read or fingerprint of each component (about
     * 30 ms of file I/O before a phone's first frame). A component the pack
     * does not hold (a stylesheet outside the bundle) compiles from source.
     * Development trees and hot reload bundles carry no such pack.
     *
     * @return list<PamPhpComponent>|null
     */
    private static function bundledComponents(string $sourceRoot, string $cachePath): ?array
    {
        $root = self::projectRoot($sourceRoot.DIRECTORY_SEPARATOR.'.');
        if ($root === null || !is_file($root.DIRECTORY_SEPARATOR.self::BUNDLE_MANIFEST)) {
            return null;
        }
        $directory = $root.DIRECTORY_SEPARATOR.self::PREBUILT_DIRECTORY;
        if (!(self::$prebuiltDirectories[$directory] ??= is_dir($directory))) {
            return null;
        }
        $pack = self::$prebuiltPacks[$directory] ??= self::readPrebuiltPack($directory);
        if ($pack === false || !is_array($pack['sources'] ?? null)) {
            return null;
        }
        $prefix = self::relativeTo($root, $sourceRoot);
        if ($prefix === null) {
            return null;
        }
        $prefix = str_replace(DIRECTORY_SEPARATOR, '/', $prefix).'/';
        $sources = [];
        foreach ($pack['sources'] as $relative) {
            if (
                !is_string($relative)
                || str_contains($relative, "\0")
                || str_contains('/'.$relative.'/', '/../')
                || !self::isComponentFile(basename($relative))
            ) {
                return null;
            }
            if (str_starts_with($relative, $prefix)) {
                $sources[] = $root.DIRECTORY_SEPARATOR.str_replace('/', DIRECTORY_SEPARATOR, $relative);
            }
        }
        if ($sources === [] || count($sources) > self::MAX_COMPONENTS) {
            return null;
        }
        sort($sources, SORT_STRING);

        self::$dependencyFingerprints = [];
        self::$appStylePaths = [];
        self::$themePaths = [];
        self::$cacheDirectories = [];
        self::$directoryPass = true;
        try {
            return array_map(
                static fn (string $source): PamPhpComponent =>
                    self::prebuiltComponent($source, null) ?? self::compileFile($source, $cachePath),
                $sources,
            );
        } finally {
            self::$dependencyFingerprints = [];
            self::$appStylePaths = [];
            self::$themePaths = [];
            self::$cacheDirectories = [];
            self::$directoryPass = false;
        }
    }

    /** @var array<string, string> resolved cache directory per requested one, within one directory pass */
    private static array $cacheDirectories = [];

    /**
     * Where components that must compile on this device are written:
     * $cachePath when it is (or can be made) a writable directory; otherwise
     * (an iOS app runs its PHP from the signed bundle, read-only on a device)
     * a directory of its own under the app's caches, see fallbackCacheDirectory().
     */
    private static function cacheDirectory(string $cachePath): string
    {
        if (self::$directoryPass && isset(self::$cacheDirectories[$cachePath])) {
            return self::$cacheDirectories[$cachePath];
        }
        if (!is_dir($cachePath)) {
            @mkdir($cachePath, 0o755, true);
        }
        $directory = is_dir($cachePath) && is_writable($cachePath)
            ? $cachePath
            : self::fallbackCacheDirectory($cachePath);
        if (self::$directoryPass) {
            self::$cacheDirectories[$cachePath] = $directory;
        }

        return $directory;
    }

    /**
     * A writable stand-in for a cache path inside a read-only bundle:
     * `$HOME/Library/Caches/pam-native/components/<id>` on iOS (and macOS),
     * else under PAM_NATIVE_STATE_DIR, else under sys_get_temp_dir(). The id
     * hashes the requested path (an iOS update installs the bundle at a new
     * path) and the bundle's content digest (manifest.sha256), so builds never
     * share compiled files. On a device (PAM_NATIVE_STATE_DIR set: the
     * directory belongs to this app alone) the first use of an id drops the
     * directories of earlier builds.
     */
    private static function fallbackCacheDirectory(string $cachePath): string
    {
        $home = getenv('HOME');
        $state = getenv('PAM_NATIVE_STATE_DIR');
        if (!is_string($state) || $state === '' || str_contains($state, "\0")) {
            $state = null;
        }
        $caches = is_string($home) && $home !== '' && !str_contains($home, "\0")
            ? $home.DIRECTORY_SEPARATOR.'Library'.DIRECTORY_SEPARATOR.'Caches'
            : null;
        $base = $caches !== null && is_dir($caches) ? $caches : ($state ?? sys_get_temp_dir());
        $root = rtrim($base, DIRECTORY_SEPARATOR).DIRECTORY_SEPARATOR.'pam-native'
            .DIRECTORY_SEPARATOR.'components';
        $projectRoot = self::projectRoot($cachePath);
        $manifest = $projectRoot === null
            ? false
            : @file_get_contents($projectRoot.DIRECTORY_SEPARATOR.self::BUNDLE_MANIFEST, false, null, 0, 128);
        $directory = $root.DIRECTORY_SEPARATOR
            .hash('xxh128', $cachePath."\0".(is_string($manifest) ? trim($manifest) : ''));
        if (is_dir($directory)) {
            return $directory;
        }
        if ($state !== null) {
            foreach (glob($root.DIRECTORY_SEPARATOR.'*', GLOB_ONLYDIR) ?: [] as $stale) {
                self::removeTree($stale);
            }
        }
        if (!@mkdir($directory, 0o755, true) && !is_dir($directory)) {
            throw new RuntimeException(
                "Cannot create PAM component cache {$cachePath} nor its fallback {$directory}.",
            );
        }

        return $directory;
    }

    /** Written by the CLI at the root of every staged application bundle. */
    private const BUNDLE_MANIFEST = 'manifest.sha256';

    /**
     * The device type a variant file is for: `tv` for `Home.tv.pam.php`
     * (also `phone` and `tablet`), null for an ordinary component.
     */
    public static function variantOf(string $source): ?string
    {
        return preg_match('/\.(tv|phone|tablet)\.pam(?:\.php)?$/D', $source, $match) === 1
            ? $match[1]
            : null;
    }

    /** `Home.pam.php` (or `Home.pam`) beside the variant `Home.tv.pam.php`. */
    private static function variantBase(string $source): string
    {
        $stem = (string) preg_replace('/\.(?:tv|phone|tablet)\.pam(?:\.php)?$/D', '', $source);
        foreach ([$stem.'.pam.php', $stem.'.pam'] as $candidate) {
            if (is_file($candidate)) {
                return $candidate;
            }
        }
        throw new RuntimeException(
            'PAM variant '.basename($source).' needs its component beside it: '.basename($stem).'.pam.php.',
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

        $sourceFingerprint = hash('xxh128', $contents);

        // Components compiled into the application bundle at build time
        // (relative identities, fingerprint-validated) need no compilation.
        $prebuilt = self::prebuiltComponent($source, $sourceFingerprint);
        if ($prebuilt !== null) {
            return $prebuilt;
        }

        $cacheDirectory = rtrim(self::cacheDirectory($cachePath), DIRECTORY_SEPARATOR);
        $cacheKey = hash('sha256', $source);
        $classFile = $cacheDirectory.DIRECTORY_SEPARATOR.$cacheKey.'.class.php';
        $templateFile = $cacheDirectory.DIRECTORY_SEPARATOR.$cacheKey.'.template.json';
        $metadataFile = $cacheDirectory.DIRECTORY_SEPARATOR.$cacheKey.'.json';

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

        $variantBase = null;
        if (self::variantOf($source) !== null) {
            $variantBase = self::variantBase($source);
            if (!str_starts_with(ltrim($contents), '<?php')) {
                // A variant may hold only <template> and <style>.
                $contents = '<?php ?>'.$contents;
            }
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
        if ($variantBase !== null) {
            // `Home.tv.pam.php` is another template for the class declared in
            // `Home.pam.php`: same state, actions and props.
            $baseContents = file_get_contents($variantBase);
            if ($baseContents === false) {
                throw new RuntimeException("Cannot read PAM component {$variantBase}.");
            }
            [$basePhp] = self::split($baseContents, $variantBase);
            [$className, $tag] = self::classIdentity($basePhp, $variantBase);
        } else {
            [$className, $tag] = self::classIdentity($php, $source);
        }
        $hash = hash('sha256', $contents."\0".$style."\0".$styleScope->value);
        $dependencies = [];
        $appStyle = self::appStylePath($source);
        foreach (array_unique([...($appStyle === null ? [] : [$appStyle]), ...$imported]) as $dependency) {
            $dependencies[$dependency] = self::dependencyFingerprint($dependency);
        }
        if ($variantBase !== null) {
            $dependencies[$variantBase] = self::dependencyFingerprint($variantBase);
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
            'theme' => self::themeTokensPath($source),
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
     * @param array{sourceFingerprint: string, appStyle: ?string, theme: ?string, dependencies: array<string, ?string>} $fingerprint
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
            || !self::strictnessFits($metadata['strict'] ?? null)
            || !is_string($metadata['class'] ?? null)
            || !is_string($metadata['tag'] ?? null)
            || !is_int($metadata['language'] ?? null)
            || !is_array($metadata['dependencies'] ?? null)
            || !array_key_exists('appStyle', $metadata)
            || $metadata['appStyle'] !== self::appStylePath($source)
            || ($metadata['theme'] ?? null) !== self::themeTokensPath($source)
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
    /**
     * @param string|null $sourceFingerprint null: a bundle listed by its own
     *        pack (see bundledComponents()), whose sources and stylesheets
     *        were compiled together and are not fingerprinted again.
     */
    private static function prebuiltComponent(
        string $source,
        ?string $sourceFingerprint,
    ): ?PamPhpComponent
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
        $pack = self::$prebuiltPacks[$directory] ??= self::readPrebuiltPack($directory);
        if ($pack !== false) {
            $entry = $pack['components'][$key] ?? null;
            $metadata = is_array($entry) ? ($entry['metadata'] ?? null) : null;
        } elseif ($sourceFingerprint === null) {
            return null;
        } else {
            // Bundles prebuilt by 1.14: one metadata file per component.
            $entry = null;
            $metadata = self::legacyPrebuiltMetadata($base.'.json');
        }
        if (
            !is_array($metadata)
            || ($metadata['version'] ?? null) !== self::CACHE_VERSION
            || ($metadata['relocatable'] ?? null) !== true
            || ($sourceFingerprint !== null && ($metadata['sourceFingerprint'] ?? null) !== $sourceFingerprint)
            || !self::strictnessFits($metadata['strict'] ?? null)
            || !is_string($metadata['class'] ?? null)
            || !is_string($metadata['tag'] ?? null)
            || !is_int($metadata['language'] ?? null)
            || !is_array($metadata['dependencies'] ?? null)
            || !array_key_exists('appStyle', $metadata)
        ) {
            return null;
        }
        if ($sourceFingerprint !== null) {
            $appStyle = self::appStylePath($source);
            $relativeAppStyle = $appStyle === null ? null : self::relativeTo($root, $appStyle);
            if ($relativeAppStyle !== null) {
                $relativeAppStyle = str_replace(DIRECTORY_SEPARATOR, '/', $relativeAppStyle);
            }
            if ($metadata['appStyle'] !== $relativeAppStyle) {
                return null;
            }
            $theme = self::themeTokensPath($source);
            $relativeTheme = $theme === null ? null : self::relativeTo($root, $theme);
            if ($relativeTheme !== null) {
                $relativeTheme = str_replace(DIRECTORY_SEPARATOR, '/', $relativeTheme);
            }
            if (($metadata['theme'] ?? null) !== $relativeTheme) {
                return null;
            }
        }
        $language = LanguageVersion::tryFrom($metadata['language']);
        if ($language === null || ($metadata['uiIr'] ?? null) !== UiIr::manifest($language)) {
            return null;
        }
        if ($sourceFingerprint !== null) {
            foreach ($metadata['dependencies'] as $dependency => $fingerprint) {
                $path = $root.DIRECTORY_SEPARATOR.str_replace('/', DIRECTORY_SEPARATOR, (string) $dependency);
                if (self::dependencyFingerprint($path) !== $fingerprint) {
                    return null;
                }
            }
        }
        if ($pack !== false) {
            $class = $entry['class'] ?? null;
            $template = $entry['template'] ?? null;
            if (!self::validPackSlice($class, $pack['size']) || !self::validPackSlice($template, $pack['size'])) {
                return null;
            }
            // Written from the pack the first time they are needed (class on
            // autoload, template on first render), never all at boot.
            $target = $pack['files'].DIRECTORY_SEPARATOR.$key;
            $classFile = $target.'.class.php';
            $templateFile = $target.'.template.php';
            self::$pendingPrebuiltFiles[$classFile] = [$pack['path'], $pack['data'] + $class[0], $class[1]];
            self::$pendingPrebuiltFiles[$templateFile] = [$pack['path'], $pack['data'] + $template[0], $template[1]];

            return new PamPhpComponent(
                className: $metadata['class'],
                tag: $metadata['tag'],
                source: $source,
                classFile: $classFile,
                template: self::lazyBundleTemplate($templateFile, $source),
                language: $language,
            );
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
     * One file holding every prebuilt component of a bundle: `PNC1`, a
     * little-endian u32 index length, the JSON index
     * (`{"components": {key: {"metadata", "class": [offset, length],
     * "template": [offset, length]}}}`), then the class and template sources.
     * A bundle installs it as one file instead of hundreds.
     */
    public const PREBUILT_PACK = 'components.pack';

    private const PREBUILT_PACK_MAGIC = 'PNC1';

    private const PREBUILT_PACK_MAX_INDEX_BYTES = 64 * 1024 * 1024;

    /** @var array<string, array{path: string, files: string, data: int, size: int, components: array<array-key, mixed>, sources: array<array-key, mixed>|null}|false> */
    private static array $prebuiltPacks = [];

    /** @var array<string, array{0: string, 1: int, 2: int}> file => [pack, offset, length] */
    private static array $pendingPrebuiltFiles = [];

    /**
     * Reuses a complete prebuilt file or restores it from its bundle pack.
     * An interrupted device write can leave an empty/truncated derived file
     * across launches; validate its size once, never on the render hot path.
     */
    public static function materializePrebuilt(string $file): void
    {
        $slice = self::$pendingPrebuiltFiles[$file] ?? null;
        if ($slice === null) {
            return;
        }
        [$pack, $offset, $length] = $slice;
        clearstatcache(true, $file);
        $cached = is_file($file);
        if ($cached && filesize($file) === $length) {
            unset(self::$pendingPrebuiltFiles[$file]);
            return;
        }
        $contents = file_get_contents($pack, false, null, $offset, $length);
        if (!is_string($contents) || strlen($contents) !== $length) {
            throw new RuntimeException("PAM prebuilt component pack {$pack} is truncated.");
        }
        if ($cached) {
            // Recovery is exceptional: use the compiler's unique temporary
            // file and atomic publication, keeping first-install writes lean.
            self::writeAtomic($file, $contents);
            if (function_exists('opcache_invalidate')) {
                // The empty PHP file may already have been compiled before
                // recovery. Invalidate only this repaired derived file.
                @opcache_invalidate($file, true);
            }
            unset(self::$pendingPrebuiltFiles[$file]);
            return;
        }
        // On the first frame's path: a plain write + rename (no tempnam,
        // lock or chmod); the rename still never exposes a partial file.
        $directory = dirname($file);
        if (!is_dir($directory) && !@mkdir($directory, 0o755, true) && !is_dir($directory)) {
            throw new RuntimeException("Cannot create PAM cache {$directory}.");
        }
        $temporary = $file.'.'.getmypid().'.tmp';
        if (file_put_contents($temporary, $contents) !== $length || !rename($temporary, $file)) {
            @unlink($temporary);
            throw new RuntimeException("Cannot write PAM component cache {$file}.");
        }
        unset(self::$pendingPrebuiltFiles[$file]);
    }

    /** @return array{path: string, files: string, data: int, size: int, components: array<array-key, mixed>, sources: array<array-key, mixed>|null}|false */
    private static function readPrebuiltPack(string $directory): array|false
    {
        $file = $directory.DIRECTORY_SEPARATOR.self::PREBUILT_PACK;
        $handle = @fopen($file, 'rb');
        if ($handle === false) {
            return false;
        }
        try {
            $header = fread($handle, 8);
            if (!is_string($header) || strlen($header) !== 8 || !str_starts_with($header, self::PREBUILT_PACK_MAGIC)) {
                return false;
            }
            $length = unpack('V', substr($header, 4))[1] ?? 0;
            if (!is_int($length) || $length < 2 || $length > self::PREBUILT_PACK_MAX_INDEX_BYTES) {
                return false;
            }
            $encoded = stream_get_contents($handle, $length);
            $size = fstat($handle)['size'] ?? 0;
            if (!is_string($encoded) || strlen($encoded) !== $length) {
                return false;
            }
            $index = json_decode($encoded, true, 32, JSON_THROW_ON_ERROR);
        } catch (JsonException) {
            return false;
        } finally {
            fclose($handle);
        }
        if (!is_array($index) || !is_array($index['components'] ?? null)) {
            return false;
        }

        // The index records every slice and fingerprint: a different pack
        // never reuses files written from another one.
        $id = hash('xxh128', $encoded);

        return [
            'path' => $file,
            'files' => self::prebuiltFilesDirectory($directory, $id),
            'data' => 8 + $length,
            'size' => max(0, $size - 8 - $length),
            'components' => $index['components'],
            'sources' => is_array($index['sources'] ?? null) ? $index['sources'] : null,
        ];
    }

    /**
     * Where a pack's component files are written: beside the pack when the
     * bundle is writable (Android installs it per release, so it is pruned
     * with the release), otherwise (an iOS app bundle is read-only) under the
     * runtime state directory, where files of other packs are dropped the
     * first time this one is used.
     */
    private static function prebuiltFilesDirectory(string $directory, string $id): string
    {
        if (is_writable($directory)) {
            return $directory.DIRECTORY_SEPARATOR.'.'.$id;
        }
        $state = getenv('PAM_NATIVE_STATE_DIR');
        if (!is_string($state) || $state === '' || str_contains($state, "\0")) {
            $state = sys_get_temp_dir().DIRECTORY_SEPARATOR.'pam-native-state';
        }
        $root = rtrim($state, DIRECTORY_SEPARATOR).DIRECTORY_SEPARATOR.'prebuilt-components';
        $files = $root.DIRECTORY_SEPARATOR.$id;
        if (!is_dir($files)) {
            foreach (glob($root.DIRECTORY_SEPARATOR.'*', GLOB_ONLYDIR) ?: [] as $stale) {
                self::removeTree($stale);
            }
        }

        return $files;
    }

    private static function validPackSlice(mixed $slice, int $size): bool
    {
        return is_array($slice)
            && is_int($slice[0] ?? null)
            && is_int($slice[1] ?? null)
            && $slice[0] >= 0
            && $slice[1] > 0
            && $slice[0] + $slice[1] <= $size;
    }

    /** @return array<string, mixed>|null */
    private static function legacyPrebuiltMetadata(string $file): ?array
    {
        $encoded = @file_get_contents($file);
        if ($encoded === false) {
            return null;
        }
        try {
            $metadata = json_decode($encoded, true, 16, JSON_THROW_ON_ERROR);
        } catch (JsonException) {
            return null;
        }

        return is_array($metadata) ? $metadata : null;
    }

    private static function lazyBundleTemplate(string $file, string $source): CompiledTemplateNode
    {
        return (new ReflectionClass(CompiledTemplateNode::class))->newLazyProxy(
            static function () use ($file, $source): CompiledTemplateNode {
                self::materializePrebuilt($file);
                $encoded = TemplateExpressionCatalog::loadBundle($file);
                try {
                    $tree = $encoded === null ? null : CompiledTemplateNode::hydrate(json_decode(
                        $encoded,
                        true,
                        512,
                        JSON_THROW_ON_ERROR,
                    ), $source);
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

    /**
     * Precompiles every component under $sourcePath into the relocatable
     * bundle cache of $projectRoot (PREBUILT_DIRECTORY): class, template and
     * expression files keyed by project-relative source path, with metadata
     * whose stylesheet dependencies are project-relative too.
     *
     * @return int number of components written
     */
    private static function bytes(string $limit): int
    {
        $value = (int) $limit;

        return match (strtolower(substr(trim($limit), -1))) {
            'g' => $value * 1024 * 1024 * 1024,
            'm' => $value * 1024 * 1024,
            'k' => $value * 1024,
            default => $value,
        };
    }

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
            // Component libraries of installed plugins ship inside vendor/:
            // pack them too, so the device compiles none of them either.
            $pluginDirectories = \Pam\Native\Plugin\PluginManager::componentDirectories($root);
            foreach ($pluginDirectories as $directory) {
                array_push($components, ...self::compileDirectory($directory, $staging));
            }

            if (!is_dir($output) && !mkdir($output, 0o755, true) && !is_dir($output)) {
                throw new RuntimeException("Cannot create PAM prebuild directory {$output}.");
            }
            // Ordered by pack key up front so the sources stream to disk in
            // the pack's order: a large app (400+ components) held every
            // source twice in memory and ran out of the CLI's 128 MB.
            $ordered = [];
            $sources = [];
            foreach ($components as $component) {
                $relative = self::relativeTo($root, $component->source);
                if ($relative !== null) {
                    $relative = str_replace(DIRECTORY_SEPARATOR, '/', $relative);
                    // Every component source of the bundle, packed or not: the
                    // device boots from this listing (see bundledComponents()).
                    $sources[] = $relative;
                    $ordered[hash('sha256', $relative)] = [$relative, $component];
                }
            }
            unset($components, $component);
            ksort($ordered, SORT_STRING);
            $dataPath = tempnam($staging, 'pam-pack-data-');
            $data = $dataPath === false ? false : fopen($dataPath, 'w+b');
            if ($data === false) {
                throw new RuntimeException("Cannot stage the PAM component pack in {$output}.");
            }
            $index = [];
            $offset = 0;
            foreach (array_keys($ordered) as $key) {
                [$relative, $component] = $ordered[$key];
                unset($ordered[$key]);
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
                $theme = $metadata['theme'] ?? null;
                if (is_string($theme)) {
                    $theme = self::relativeTo($root, $theme);
                    if ($theme === null) {
                        continue;
                    }
                    $theme = str_replace(DIRECTORY_SEPARATOR, '/', $theme);
                }
                $metadata['theme'] = $theme;
                $metadata['dependencies'] = $dependencies;
                $metadata['relocatable'] = true;
                $template = self::bundleTemplate(
                    (string) file_get_contents($compiledBase.'.template.json'),
                    $root,
                );
                $class = (string) file_get_contents($component->classFile);
                $template = TemplateExpressionCatalog::bundleSource($component->template, $template);
                unset($component);
                if (fwrite($data, $class) !== strlen($class) || fwrite($data, $template) !== strlen($template)) {
                    throw new RuntimeException("Cannot stage the PAM component pack in {$output}.");
                }
                $index[$key] = [
                    'metadata' => $metadata,
                    'class' => [$offset, strlen($class)],
                    'template' => [$offset + strlen($class), strlen($template)],
                ];
                $offset += strlen($class) + strlen($template);
            }
            // Every bundle file is created on device at the first launch after
            // an install: all components ship as one pack file (see PREBUILT_PACK).
            $count = count($index);
            $encoded = json_encode(
                ['version' => 1, 'components' => (object) $index, 'sources' => $sources],
                JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES,
            );
            unset($index);
            $header = self::PREBUILT_PACK_MAGIC.pack('V', strlen($encoded)).$encoded;
            unset($encoded);
            $pack = $output.DIRECTORY_SEPARATOR.self::PREBUILT_PACK;
            $temporary = tempnam($output, 'pam-component-');
            $target = $temporary === false ? false : fopen($temporary, 'wb');
            rewind($data);
            $written = $target === false ? false : fwrite($target, $header);
            $copied = $written === strlen($header) ? stream_copy_to_stream($data, $target) : false;
            $closed = $target !== false && fclose($target);
            fclose($data);
            if ($copied !== $offset || !$closed || !rename((string) $temporary, $pack)) {
                if (is_string($temporary)) {
                    @unlink($temporary);
                }
                throw new RuntimeException("Cannot write PAM component cache {$pack}.");
            }
            @chmod($pack, 0o644);

            self::$prebuiltDirectories = [];
            self::$prebuiltPacks = [];

            // O aparelho em produção monta os contratos sob demanda e não valida
            // mais os templates no boot: a validação completa roda aqui, no build,
            // depois do pacote gravado (os fontes já saíram da memória). Plugins
            // primeiro, porque os templates do app usam os componentes deles. Carregar
            // todas as classes de um app grande passa dos 128 MB do CLI.
            $limit = ini_get('memory_limit');
            if ($limit !== false && $limit !== '-1' && self::bytes($limit) < 512 * 1024 * 1024) {
                ini_set('memory_limit', '512M');
            }
            try {
                PamPhpRegistry::validateIsolated([...$pluginDirectories, $sourcePath], $staging);
            } finally {
                if ($limit !== false) {
                    ini_set('memory_limit', $limit);
                }
            }

            return $count;
        } finally {
            if (isset($data) && is_resource($data)) {
                fclose($data);
            }
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
        if (is_array($tree)) {
            // Node sources are restored from the installed component path.
            $tree = self::withoutNodeSources($tree);
        }
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
        }
        if (is_array($tree)) {
            $encoded = json_encode($tree, JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES)."\n";
        }

        return str_replace($root.DIRECTORY_SEPARATOR, '', $encoded);
    }

    /**
     * @param array<string, mixed> $node
     * @return array<string, mixed>
     */
    private static function withoutNodeSources(array $node): array
    {
        $node['source'] = '';
        if (is_array($node['children'] ?? null)) {
            foreach ($node['children'] as $index => $child) {
                if (is_array($child)) {
                    $node['children'][$index] = self::withoutNodeSources($child);
                }
            }
        }

        return $node;
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
        // The application's theme tokens (pam-native.json "theme.tokens")
        // come after every package sheet: a component library's `:root`
        // custom properties take the app's values, resolved at compile time.
        $theme = self::themeTokensPath($component);
        if ($theme !== null) {
            $sources[] = ScopedStyleCompiler::sourceMarker(self::displayPath($theme, $component), 1)
                .self::themeTokens($theme);
            ScopedStyleCompiler::recordImport($theme);
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

    /** @var array<string, ?string> theme tokens location per component directory */
    private static array $themePaths = [];

    /**
     * `theme.tokens` of the nearest pam-native.json above the component (the
     * application, also for components of installed packages), or null.
     * Projects without it pay one cached lookup per directory.
     */
    private static function themeTokensPath(string $component): ?string
    {
        $directory = dirname((string) (realpath($component) ?: $component));
        if (array_key_exists($directory, self::$themePaths)) {
            return self::$themePaths[$directory];
        }
        $path = null;
        $current = $directory;
        while (true) {
            $manifest = $current.DIRECTORY_SEPARATOR.'pam-native.json';
            if (is_file($manifest)) {
                $decoded = json_decode((string) file_get_contents($manifest), true);
                $tokens = is_array($decoded) && is_array($decoded['theme'] ?? null)
                    ? ($decoded['theme']['tokens'] ?? null)
                    : null;
                if (is_string($tokens) && $tokens !== '') {
                    if (str_contains($tokens, "\0") || str_starts_with($tokens, '/') || !str_ends_with(strtolower($tokens), '.css')) {
                        throw new RuntimeException("pam-native.json theme.tokens must be a project-relative .css file, got {$tokens}.");
                    }
                    $candidate = realpath($current.DIRECTORY_SEPARATOR.$tokens);
                    $root = realpath($current);
                    if (!is_string($candidate) || !is_file($candidate) || !is_string($root)
                        || !str_starts_with($candidate, rtrim($root, DIRECTORY_SEPARATOR).DIRECTORY_SEPARATOR)) {
                        throw new RuntimeException("pam-native.json theme.tokens {$tokens} is missing or outside the project.");
                    }
                    $path = $candidate;
                }
                break;
            }
            $parent = dirname($current);
            if ($parent === $current) {
                break;
            }
            $current = $parent;
        }
        if (self::$directoryPass) {
            self::$themePaths[$directory] = $path;
        }

        return $path;
    }

    /**
     * The theme file holds only `:root { --name: value; }` blocks: tokens,
     * never selectors, so overriding a library's palette cannot restyle its
     * components' structure (or leak rules into the application's own).
     */
    private static function themeTokens(string $path): string
    {
        $contents = file_get_contents($path);
        if ($contents === false || strlen($contents) > 65_536) {
            throw new RuntimeException("Cannot read the theme tokens {$path} (64 KiB at most).");
        }
        $plain = preg_replace('/\/\*[\s\S]*?\*\//', '', $contents) ?? '';
        $block = '/\G\s*:root\s*\{(?:\s*--[A-Za-z0-9_-]+\s*:\s*[^;{}]+;)*\s*\}/';
        $offset = 0;
        while (preg_match($block, $plain, $match, 0, $offset) === 1) {
            $offset += strlen($match[0]);
        }
        if (trim(substr($plain, $offset)) !== '') {
            throw new RuntimeException(
                "Theme tokens {$path} may only declare `:root { --token: value; }` custom properties.",
            );
        }

        return $contents;
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

    /**
     * Strict validation only rejects code (PAM2104/PAM2105) and never changes
     * the compiled output, so a strict-validated cache serves every mode; a
     * lenient one only serves lenient runtimes. Release builds prebuild strict.
     */
    private static function strictnessFits(mixed $strict): bool
    {
        return $strict === true || ($strict === false && !BuildConfiguration::strict());
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

    /** @param string|list<string> $contents parts are written in order, never joined in memory */
    private static function writeAtomic(string $path, string|array $contents): void
    {
        $length = is_array($contents) ? array_sum(array_map('strlen', $contents)) : strlen($contents);
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
            || file_put_contents($temporary, $contents, LOCK_EX) !== $length
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
