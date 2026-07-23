<?php

declare(strict_types=1);

namespace Pam\Native\Plugin;

use JsonException;
use Pam\Native\Protocol;
use ReflectionClass;

final class PluginManager
{
    private const int MAX_INSTALLED_BYTES = 8_388_608;
    private const int MAX_DESCRIPTOR_BYTES = 1_048_576;

    /** @var list<PluginProvider> */
    private static array $providers = [];

    private static bool $booted = false;

    private function __construct()
    {
    }

    public static function boot(?string $projectRoot = null): void
    {
        if (self::$booted) {
            return;
        }

        $root = $projectRoot !== null
            ? realpath($projectRoot)
            : self::findProjectRoot();

        if ($root === false || $root === null) {
            self::$booted = true;

            return;
        }

        $providerClasses = self::discover($root);
        $providers = [];

        foreach ($providerClasses as $providerClass) {
            if (!class_exists($providerClass)) {
                throw new PluginException("Pam Native plugin provider {$providerClass} cannot be autoloaded.");
            }

            $reflection = new ReflectionClass($providerClass);

            if (!$reflection->implementsInterface(PluginProvider::class)) {
                throw new PluginException(
                    "Pam Native plugin provider {$providerClass} must implement ".PluginProvider::class.'.',
                );
            }

            if (!$reflection->isInstantiable() || $reflection->getConstructor()?->getNumberOfRequiredParameters() > 0) {
                throw new PluginException(
                    "Pam Native plugin provider {$providerClass} must have a public zero-argument constructor.",
                );
            }

            $provider = $reflection->newInstance();
            $providers[] = $provider;
        }

        foreach ($providers as $provider) {
            $provider->register();
        }

        foreach ($providers as $provider) {
            $provider->boot();
        }

        self::$providers = $providers;
        self::$booted = true;
    }

    /** @return list<class-string<PluginProvider>> */
    public static function discover(string $projectRoot): array
    {
        $root = realpath($projectRoot);

        if ($root === false || !is_dir($root)) {
            throw new PluginException("Pam Native project root {$projectRoot} does not exist.");
        }

        $vendor = realpath($root.'/vendor');
        $composer = realpath($root.'/vendor/composer');
        $installedPath = $root.'/vendor/composer/installed.json';

        if ($vendor === false || $composer === false || !is_file($installedPath)) {
            return [];
        }

        $size = filesize($installedPath);

        if ($size === false || $size > self::MAX_INSTALLED_BYTES) {
            throw new PluginException('Composer installed.json exceeds the 8 MiB safety limit.');
        }

        $contents = file_get_contents($installedPath);

        if ($contents === false) {
            throw new PluginException('Cannot read Composer installed.json.');
        }

        try {
            $decoded = json_decode($contents, true, flags: JSON_THROW_ON_ERROR);
        } catch (JsonException $error) {
            throw new PluginException('Composer installed.json is invalid.', previous: $error);
        }

        if (!is_array($decoded)) {
            throw new PluginException('Composer installed.json must contain an object or list.');
        }

        $packages = isset($decoded['packages']) && is_array($decoded['packages'])
            ? $decoded['packages']
            : $decoded;
        $providers = [];
        $providerNames = [];

        foreach ($packages as $package) {
            if (!is_array($package)) {
                continue;
            }

            $name = $package['name'] ?? null;
            $installPath = $package['install-path'] ?? null;
            $packageExtra = $package['extra'] ?? null;
            $extra = is_array($packageExtra) ? ($packageExtra['pam-native'] ?? null) : null;
            $descriptorPath = is_array($extra) ? ($extra['plugin'] ?? null) : null;

            if (!is_string($descriptorPath)) {
                continue;
            }

            if (!is_string($name) || !is_string($installPath)) {
                throw new PluginException('A Pam Native Composer plugin is missing name or install-path.');
            }

            $packageRoot = realpath($composer.'/'.$installPath);

            if (
                $packageRoot === false
                || !is_dir($packageRoot)
                || !self::isWithin($packageRoot, $vendor)
            ) {
                throw new PluginException("Pam Native plugin {$name} escapes the Composer vendor directory.");
            }

            if (!self::isSafeRelativePath($descriptorPath)) {
                throw new PluginException("Pam Native plugin {$name} has an unsafe descriptor path.");
            }

            $descriptor = realpath($packageRoot.'/'.$descriptorPath);

            if (
                $descriptor === false
                || !is_file($descriptor)
                || !self::isWithin($descriptor, $packageRoot)
            ) {
                throw new PluginException("Pam Native plugin {$name} descriptor escapes its package.");
            }

            $descriptorSize = filesize($descriptor);

            if ($descriptorSize === false || $descriptorSize > self::MAX_DESCRIPTOR_BYTES) {
                throw new PluginException("Pam Native plugin {$name} descriptor exceeds one MiB.");
            }

            $manifest = self::decodeDescriptor($descriptor, $name);
            $version = $manifest['version'] ?? null;
            $protocol = $manifest['protocol'] ?? null;

            if ($version !== 1 || $protocol !== Protocol::VERSION) {
                throw new PluginException(
                    "Pam Native plugin {$name} uses an unsupported manifest or protocol version.",
                );
            }

            $compatibility = $manifest['pamNative'] ?? null;
            $minimum = is_array($compatibility) ? ($compatibility['minimum'] ?? null) : null;
            $maximum = is_array($compatibility) ? ($compatibility['maximumExclusive'] ?? null) : null;

            if (
                !is_string($minimum)
                || !is_string($maximum)
                || preg_match('/^[0-9]+\.[0-9]+\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?$/D', $minimum) !== 1
                || preg_match('/^[0-9]+\.[0-9]+\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?$/D', $maximum) !== 1
                || version_compare(Protocol::SDK_VERSION, $minimum, '<')
                || version_compare(Protocol::SDK_VERSION, $maximum, '>=')
            ) {
                throw new PluginException(
                    "Pam Native plugin {$name} is incompatible with SDK ".Protocol::SDK_VERSION.'.',
                );
            }

            $php = $manifest['php'] ?? null;
            $provider = is_array($php) ? ($php['provider'] ?? null) : null;

            if ($provider === null) {
                continue;
            }

            if (!is_string($provider) || !self::isClassName($provider)) {
                throw new PluginException("Pam Native plugin {$name} has an invalid PHP provider.");
            }

            if (isset($providerNames[$provider])) {
                throw new PluginException("Pam Native plugin provider {$provider} is registered twice.");
            }

            $providers[$name] = $provider;
            $providerNames[$provider] = true;
        }

        ksort($providers, SORT_STRING);

        /** @var list<class-string<PluginProvider>> $classes */
        $classes = array_values($providers);

        return $classes;
    }

    /** @return list<PluginProvider> */
    public static function providers(): array
    {
        return self::$providers;
    }

    public static function reset(): void
    {
        self::$providers = [];
        self::$booted = false;
    }

    private static function findProjectRoot(): ?string
    {
        $candidates = [];
        $configured = getenv('PAM_NATIVE_PROJECT_ROOT');

        if (is_string($configured) && $configured !== '') {
            $candidates[] = $configured;
        }

        $script = $_SERVER['SCRIPT_FILENAME'] ?? null;

        if (is_string($script) && $script !== '') {
            $candidates[] = dirname($script);
        }

        $workingDirectory = getcwd();

        if ($workingDirectory !== false) {
            $candidates[] = $workingDirectory;
        }

        $candidates[] = __DIR__;

        foreach ($candidates as $candidate) {
            $directory = realpath($candidate);

            while ($directory !== false && dirname($directory) !== $directory) {
                if (is_file($directory.'/vendor/composer/installed.json')) {
                    return $directory;
                }

                $directory = dirname($directory);
            }
        }

        return null;
    }

    /** @return array<string, mixed> */
    private static function decodeDescriptor(string $path, string $package): array
    {
        $contents = file_get_contents($path);

        if ($contents === false) {
            throw new PluginException("Cannot read Pam Native plugin descriptor for {$package}.");
        }

        try {
            $manifest = json_decode($contents, true, flags: JSON_THROW_ON_ERROR);
        } catch (JsonException $error) {
            throw new PluginException(
                "Pam Native plugin descriptor for {$package} is invalid.",
                previous: $error,
            );
        }

        if (!is_array($manifest)) {
            throw new PluginException("Pam Native plugin descriptor for {$package} must be an object.");
        }

        $normalized = [];

        foreach ($manifest as $key => $value) {
            if (!is_string($key)) {
                throw new PluginException(
                    "Pam Native plugin descriptor for {$package} must use string object keys.",
                );
            }

            $normalized[$key] = $value;
        }

        return $normalized;
    }

    private static function isWithin(string $path, string $root): bool
    {
        return $path === $root || str_starts_with($path, rtrim($root, DIRECTORY_SEPARATOR).DIRECTORY_SEPARATOR);
    }

    private static function isSafeRelativePath(string $path): bool
    {
        if ($path === '' || str_starts_with($path, '/') || str_contains($path, "\0")) {
            return false;
        }

        foreach (preg_split('~[/\\\\]+~', $path) ?: [] as $part) {
            if ($part === '' || $part === '.' || $part === '..') {
                return false;
            }
        }

        return true;
    }

    private static function isClassName(string $class): bool
    {
        return preg_match('/^[A-Za-z_][A-Za-z0-9_]*(\\\\[A-Za-z_][A-Za-z0-9_]*)+$/D', $class) === 1;
    }
}
