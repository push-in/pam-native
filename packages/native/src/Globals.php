<?php

declare(strict_types=1);

namespace Pam\Native;

use LogicException;
use Pam\Native\Signals\Signal;

use function array_keys;
use function preg_match;

/**
 * Registry behind App::globals(), App::global() and App::forget().
 *
 * Every entry is held in a signal, so replacing or forgetting a global
 * re-renders whoever read it. Values are returned as they were registered:
 * a signal stays a signal (`$count.value` in a template), a composable stays
 * an object (`$scheme.value`, `$insets.top`), scalars and closures stay as is.
 *
 * Lookup order in a template is: loop/slot data, then the component's own
 * properties, then globals. In PHP, `$this->name` reaches a global only when
 * the component declares no property of that name.
 */
final class Globals
{
    public const string OWNER_APP = 'app';

    private const array RESERVED = ['this', 'data', 'scope', 'slot', 'props', 'state', 'GLOBALS'];

    /** @var array<string, array{signal: Signal<mixed>, owner: string}> */
    private static array $entries = [];

    /** @var Signal<int>|null bumped when the set of names changes, so lookups that missed re-run */
    private static ?Signal $names = null;

    private function __construct()
    {
    }

    /**
     * Registers one global. Without $replace a second registration of the
     * same name is a clear error in development builds (and overwrites in
     * production builds).
     */
    public static function set(string $name, mixed $value, bool $replace = false, string $owner = self::OWNER_APP): void
    {
        self::assertName($name);
        $entry = self::$entries[$name] ?? null;
        if ($entry !== null) {
            if (!$replace && BuildConfiguration::mode() === BuildMode::Development) {
                throw new LogicException(
                    "Global \${$name} is already registered by {$entry['owner']}. "
                    ."Pass replace: true to App::global('{$name}', ..., replace: true) to overwrite it, "
                    .'or App::forget() it first.',
                );
            }
            $entry['owner'] = $owner;
            self::$entries[$name] = $entry;
            $entry['signal']->set($value);

            return;
        }
        self::$entries[$name] = ['signal' => new Signal($value), 'owner' => $owner];
        self::names()->update(static fn (int $revision): int => $revision + 1);
    }

    /** @param array<string, mixed> $values registers every pair, overwriting existing names */
    public static function merge(array $values, string $owner = self::OWNER_APP): void
    {
        foreach ($values as $name => $value) {
            self::set((string) $name, $value, true, $owner);
        }
    }

    public static function forget(string $name): void
    {
        $entry = self::$entries[$name] ?? null;
        if ($entry === null) {
            return;
        }
        unset(self::$entries[$name]);
        // Readers of the old value re-render and miss it from now on.
        $entry['signal']->set(null);
        self::names()->update(static fn (int $revision): int => $revision + 1);
    }

    /** True when $name is registered; subscribes the reader to registrations. */
    public static function has(string $name): bool
    {
        if (isset(self::$entries[$name])) {
            return true;
        }
        self::names()->get();

        return false;
    }

    /** The value of $name (subscribing the reader), or $default when it is not registered. */
    public static function get(string $name, mixed $default = null): mixed
    {
        $entry = self::$entries[$name] ?? null;
        if ($entry === null) {
            self::names()->get();

            return $default;
        }

        return $entry['signal']->get();
    }

    public static function owner(string $name): ?string
    {
        return self::$entries[$name]['owner'] ?? null;
    }

    /** @return Signal<int> */
    private static function names(): Signal
    {
        return self::$names ??= new Signal(0);
    }

    /** @return list<string> */
    public static function keys(): array
    {
        return array_keys(self::$entries);
    }

    /** @return array<string, mixed> every registered value, without subscribing */
    public static function all(): array
    {
        $values = [];
        foreach (self::$entries as $name => $entry) {
            $values[$name] = $entry['signal']->peek();
        }

        return $values;
    }

    /** @internal Clears every global (tests and runtime resets). */
    public static function reset(): void
    {
        self::$entries = [];
        self::$names = null;
    }

    private static function assertName(string $name): void
    {
        if (preg_match('/^[A-Za-z_][A-Za-z0-9_]{0,63}$/D', $name) !== 1) {
            throw new LogicException("Global name \"{$name}\" must be a PHP identifier (it is read as \${$name}).");
        }
        foreach (self::RESERVED as $reserved) {
            if ($reserved === $name) {
                throw new LogicException("Global name \${$name} is reserved by the template engine.");
            }
        }
    }
}
