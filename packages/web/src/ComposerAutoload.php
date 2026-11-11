<?php

declare(strict_types=1);

namespace Pam\Native\Web;

/**
 * Finds the files of Composer packages from the app's `vendor/composer`
 * maps (classmap, PSR-4, PSR-0 and files), without running the autoloader:
 * the build pulls in only the classes and functions the app uses.
 */
final class ComposerAutoload
{
    /** @var array<string, string> lower-case class => file */
    private array $classmap = [];
    /** @var array<string, list<string>> namespace prefix => directories */
    private array $psr4 = [];
    /** @var array<string, list<string>> */
    private array $psr0 = [];
    /** @var list<string> */
    public array $files = [];

    public function __construct(string $root)
    {
        $composer = $root.'/vendor/composer';
        if (!is_dir($composer)) {
            return;
        }
        foreach (self::load($composer.'/autoload_classmap.php', $root) as $class => $file) {
            $this->classmap[strtolower($class)] = $file;
        }
        $this->psr4 = self::load($composer.'/autoload_psr4.php', $root);
        $this->psr0 = self::load($composer.'/autoload_namespaces.php', $root);
        // Longest prefixes first, as Composer does.
        krsort($this->psr4);
        krsort($this->psr0);
        $this->files = array_values(self::load($composer.'/autoload_files.php', $root));
    }

    public function find(string $class): ?string
    {
        $class = ltrim($class, '\\');
        if (isset($this->classmap[strtolower($class)]) && is_file($this->classmap[strtolower($class)])) {
            return $this->classmap[strtolower($class)];
        }
        $relative = strtr($class, '\\', '/').'.php';
        foreach ($this->psr4 as $prefix => $directories) {
            if ($prefix !== '' && !str_starts_with($class, $prefix)) {
                continue;
            }
            foreach ((array) $directories as $directory) {
                $file = $directory.'/'.substr($relative, strlen($prefix));
                if (is_file($file)) {
                    return $file;
                }
            }
        }
        $position = strrpos($class, '\\');
        $legacy = $position === false
            ? strtr($class, '_', '/').'.php'
            : strtr(substr($class, 0, $position), '\\', '/').'/'.strtr(substr($class, $position + 1), '_', '/').'.php';
        foreach ($this->psr0 as $prefix => $directories) {
            if ($prefix !== '' && !str_starts_with($class, $prefix)) {
                continue;
            }
            foreach ((array) $directories as $directory) {
                if (is_file($directory.'/'.$legacy)) {
                    return $directory.'/'.$legacy;
                }
            }
        }

        return null;
    }

    /** @return array<string, mixed> */
    private static function load(string $file, string $root): array
    {
        if (!is_file($file)) {
            return [];
        }
        // Composer's generated maps only build arrays from these two paths.
        $map = (static function (string $file, string $vendorDir, string $baseDir): mixed {
            return require $file;
        })($file, $root.'/vendor', $root);

        return is_array($map) ? $map : [];
    }
}
