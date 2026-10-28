<?php

declare(strict_types=1);

namespace Pam\Native\Plugin;

use function class_alias;
use function class_exists;
use function enum_exists;
use function interface_exists;
use function sprintf;
use function trigger_error;

use const E_USER_DEPRECATED;

/**
 * Forwards a deprecated core class name to the plugin that now owns it.
 *
 * The core keeps one thin shim file per moved class for one minor version:
 * with the plugin installed the old name becomes an alias of the plugin class
 * (same class, so `instanceof`, enum cases and type hints keep working) and a
 * silenced deprecation is raised; without the plugin the autoload fails with
 * the exact `composer require` that restores it.
 */
final class MovedToPlugin
{
    /** @var array<string, string> legacy class => plugin package */
    private static array $aliased = [];

    private function __construct()
    {
    }

    public static function alias(string $legacy, string $target, string $package): void
    {
        if (!class_exists($target) && !interface_exists($target) && !enum_exists($target)) {
            throw new PluginException(sprintf(
                '%s moved out of the PAM Native core into %s (%s). Run `composer require %s`.',
                $legacy,
                $package,
                $target,
                $package,
            ));
        }
        if (!isset(self::$aliased[$legacy])) {
            class_alias($target, $legacy);
            self::$aliased[$legacy] = $package;
            @trigger_error(sprintf(
                '%s is deprecated since pam-native 1.46 and removed in 1.47; use %s from %s.',
                $legacy,
                $target,
                $package,
            ), E_USER_DEPRECATED);
        }
    }

    /** @return array<string, string> legacy class => plugin package, for doctor/audit output. */
    public static function aliased(): array
    {
        return self::$aliased;
    }
}
