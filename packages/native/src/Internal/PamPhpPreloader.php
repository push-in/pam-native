<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

use JsonException;
use Pam\Native\Protocol;
use RuntimeException;

final class PamPhpPreloader
{
    private const VERSION = 1;

    private function __construct()
    {
    }

    /**
     * @param list<string> $entrypoints Root component tags or class names. Empty includes every component.
     * @return array{components: int, discovered: int, eliminated: int, preload: string, manifest: string, freeze: string, buildId: string}
     */
    public static function optimize(string $sourcePath, string $cachePath, array $entrypoints = []): array
    {
        $components = PamPhpCompiler::compileDirectory($sourcePath, $cachePath);
        $discovered = count($components);
        $components = self::reachableComponents($components, $entrypoints);
        $cacheRoot = realpath($cachePath);
        if ($cacheRoot === false) {
            throw new RuntimeException('PAM optimization cache could not be resolved.');
        }

        $compiledEntries = [];
        foreach ($components as $component) {
            $classFile = realpath($component->classFile);
            if (
                $classFile === false
                || !str_starts_with($classFile, $cacheRoot.DIRECTORY_SEPARATOR)
            ) {
                throw new RuntimeException('Compiled PAM class escaped the optimization cache.');
            }
            $sha256 = hash_file('sha256', $classFile);
            if ($sha256 === false) {
                throw new RuntimeException("Cannot fingerprint compiled PAM class {$classFile}.");
            }
            $compiledEntries[] = [
                'class' => $component->className,
                'tag' => $component->tag,
                'file' => basename($classFile),
                'sha256' => $sha256,
            ];
        }
        usort(
            $compiledEntries,
            static fn (array $left, array $right): int =>
                $left['class'] <=> $right['class'],
        );

        $manifest = [
            'version' => self::VERSION,
            'components' => $compiledEntries,
        ];
        try {
            $json = json_encode(
                $manifest,
                JSON_THROW_ON_ERROR | JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES,
            );
        } catch (JsonException $error) {
            throw new RuntimeException('Cannot encode PAM preload manifest.', previous: $error);
        }

        $manifestPath = $cacheRoot.DIRECTORY_SEPARATOR.'pam-preload.json';
        $preloadPath = $cacheRoot.DIRECTORY_SEPARATOR.'pam-preload.php';
        self::writeAtomic($manifestPath, $json."\n");
        self::writeAtomic($preloadPath, self::preloadSource($compiledEntries));

        $buildId = hash('sha256', $json);
        $freeze = [
            'version' => 1,
            'abiVersion' => Protocol::ABI_VERSION,
            'protocolVersion' => Protocol::VERSION,
            'capabilities' => Protocol::CAPABILITIES,
            'buildId' => $buildId,
            'discoveredComponents' => $discovered,
            'includedComponents' => count($compiledEntries),
            'eliminatedComponents' => $discovered - count($compiledEntries),
            'entrypoints' => array_values($entrypoints === [] ? ['*'] : $entrypoints),
        ];
        try {
            $freezeJson = json_encode($freeze, JSON_THROW_ON_ERROR | JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES);
        } catch (JsonException $error) {
            throw new RuntimeException('Cannot encode PAM Freeze manifest.', previous: $error);
        }
        $freezePath = $cacheRoot.DIRECTORY_SEPARATOR.'pam-freeze.json';
        self::writeAtomic($freezePath, $freezeJson."\n");

        return [
            'components' => count($compiledEntries),
            'discovered' => $discovered,
            'eliminated' => $discovered - count($compiledEntries),
            'preload' => $preloadPath,
            'manifest' => $manifestPath,
            'freeze' => $freezePath,
            'buildId' => $buildId,
        ];
    }

    /** @param list<PamPhpComponent> $components @param list<string> $entries @return list<PamPhpComponent> */
    private static function reachableComponents(array $components, array $entries): array
    {
        if ($entries === []) {
            return $components;
        }
        $byIdentity = [];
        foreach ($components as $index => $component) {
            $byIdentity[$component->tag] = $index;
            $byIdentity[$component->className] = $index;
        }
        $pending = $entries;
        $reachable = [];
        while (($identity = array_shift($pending)) !== null) {
            $index = $byIdentity[$identity] ?? null;
            if (!is_int($index)) {
                throw new RuntimeException("Unknown PAM Freeze entrypoint {$identity}.");
            }
            if (isset($reachable[$index])) {
                continue;
            }
            $reachable[$index] = true;
            foreach (self::templateTags($components[$index]->template) as $tag) {
                if (isset($byIdentity[$tag])) {
                    $pending[] = $tag;
                }
            }
        }
        $selected = array_values(array_intersect_key($components, $reachable));
        usort($selected, static fn (PamPhpComponent $left, PamPhpComponent $right): int => $left->className <=> $right->className);
        return $selected;
    }

    /** @return list<string> */
    private static function templateTags(CompiledTemplateNode $node): array
    {
        $tags = $node->kind === 1 ? [$node->name] : [];
        foreach ($node->children as $child) {
            array_push($tags, ...self::templateTags($child));
        }
        return $tags;
    }

    /** @param list<array{class: string, tag: string, file: string, sha256: string}> $entries */
    private static function preloadSource(array $entries): string
    {
        $files = array_column($entries, 'sha256', 'file');
        $classes = array_column($entries, 'class');
        $export = var_export($files, true);
        $classExport = var_export($classes, true);

        return <<<PHP
<?php

declare(strict_types=1);

// Generated by PAM Native. The paths stay relative so signed bundles remain relocatable.
foreach ({$export} as \$file => \$expectedSha256) {
    \$path = __DIR__.DIRECTORY_SEPARATOR.\$file;
    if (!is_file(\$path)) {
        throw new RuntimeException("Missing preloaded PAM component {\$file}.");
    }
    \$actualSha256 = hash_file('sha256', \$path);
    if (!is_string(\$actualSha256) || !hash_equals(\$expectedSha256, \$actualSha256)) {
        throw new RuntimeException("Integrity check failed for preloaded PAM component {\$file}.");
    }
    if (function_exists('opcache_compile_file')) {
        @opcache_compile_file(\$path);
    }
    require_once \$path;
}
\Pam\Native\Internal\PamPhpRegistry::preloadMetadata({$classExport});

PHP;
    }

    private static function writeAtomic(string $path, string $contents): void
    {
        $temporary = $path.'.'.bin2hex(random_bytes(8)).'.tmp';
        if (file_put_contents($temporary, $contents, LOCK_EX) === false) {
            throw new RuntimeException("Cannot write PAM optimization file {$path}.");
        }
        if (!rename($temporary, $path)) {
            @unlink($temporary);
            throw new RuntimeException("Cannot publish PAM optimization file {$path}.");
        }
    }
}
