<?php

declare(strict_types=1);

namespace Pam\Native\HotReload;

use Pam\Native\Attributes\State as StateAttribute;
use Pam\Native\Component;
use ReflectionClass;
use ReflectionNamedType;
use ReflectionProperty;
use ReflectionUnionType;
use Throwable;
use UnitEnum;

use function array_is_list;
use function count;
use function file_get_contents;
use function file_put_contents;
use function getenv;
use function is_array;
use function is_bool;
use function is_file;
use function is_float;
use function is_int;
use function is_string;
use function json_decode;
use function json_encode;
use function rename;
use function rtrim;
use function strlen;
use function unlink;

/**
 * Fast Refresh for development hot reload.
 *
 * Right before a hot reload shuts the PHP request down, the runtime records
 * the `#[State]` properties of every mounted component, keyed by the
 * component's position in the tree (class and index among same-class
 * siblings, from the root). After the new code boots, a component mounted at
 * the same position with the same class gets those values back right before
 * its mount() runs, so mount() and effects see the preserved state. Props,
 * services and objects are never carried over, and
 * a property whose new declaration rejects the old value starts fresh.
 *
 * The snapshot lives in the runtime state directory only between the two
 * requests: the new request consumes it, and components mounted more than
 * five seconds later (or after every entry was restored) start fresh, so a
 * cold start or later navigation never restores development state.
 *
 * @internal
 */
final class ComponentStatePreserver
{
    private const string FILE = 'hot-reload-components.json';
    private const int SCHEMA = 1;
    private const int MAX_BYTES = 4_194_304;
    private const int MAX_DEPTH = 16;
    private const int MAX_COMPONENTS = 4_096;
    private const int RESTORE_WINDOW_NANOS = 5_000_000_000;

    /** @var array<string, array{0: string, 1: array<string, mixed>}>|null */
    private static ?array $pending = null;
    private static bool $loaded = false;
    private static int $deadline = 0;
    /** @var array<class-string, list<ReflectionProperty>> */
    private static array $stateProperties = [];

    private function __construct()
    {
    }

    /**
     * @param array<string, Component> $components mounted components by tree key
     */
    public static function checkpoint(array $components): int
    {
        $snapshot = [];
        foreach ($components as $key => $component) {
            if (count($snapshot) >= self::MAX_COMPONENTS) {
                break;
            }
            $state = self::capture($component);
            if ($state !== []) {
                $snapshot[$key] = [$component::class, $state];
            }
        }
        $path = self::path();
        if ($path === null) {
            return 0;
        }
        if ($snapshot === []) {
            @unlink($path);
            return 0;
        }
        try {
            $encoded = json_encode(
                ['schema' => self::SCHEMA, 'components' => $snapshot],
                JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES | JSON_PRESERVE_ZERO_FRACTION,
            );
        } catch (\JsonException) {
            return 0;
        }
        if (strlen($encoded) > self::MAX_BYTES) {
            return 0;
        }
        $temporary = $path.'.tmp';
        if (@file_put_contents($temporary, $encoded) === false || !@rename($temporary, $path)) {
            @unlink($temporary);
            return 0;
        }

        return count($snapshot);
    }

    /** True while a snapshot from the previous request waits to be restored. */
    public static function restoring(): bool
    {
        if (!self::$loaded) {
            self::$loaded = true;
            self::$pending = self::load();
            self::$deadline = hrtime(true) + self::RESTORE_WINDOW_NANOS;
        }
        if (self::$pending !== null && (self::$pending === [] || hrtime(true) > self::$deadline)) {
            self::$pending = null;
        }

        return self::$pending !== null;
    }

    /** Restores the recorded state of the component mounted at `$key`, if any. */
    public static function restore(Component $component, string $key): bool
    {
        $entry = self::$pending[$key] ?? null;
        if ($entry === null || $entry[0] !== $component::class) {
            return false;
        }
        unset(self::$pending[$key]);
        $applied = false;
        foreach (self::stateProperties($component::class) as $property) {
            $name = $property->getName();
            if (!array_key_exists($name, $entry[1])) {
                continue;
            }
            try {
                $property->setValue($component, self::decode($entry[1][$name], 0));
                $applied = true;
            } catch (Throwable) {
                // The declaration changed (type, enum case...): keep the fresh value.
            }
        }
        return $applied;
    }

    /** Request teardown: the next request loads its own snapshot. */
    public static function reset(): void
    {
        self::$pending = null;
        self::$loaded = false;
        self::$deadline = 0;
    }

    /** @return array<string, mixed> */
    private static function capture(Component $component): array
    {
        $state = [];
        foreach (self::stateProperties($component::class) as $property) {
            if (!$property->isInitialized($component)) {
                continue;
            }
            $value = self::encode($property->getValue($component), 0);
            if ($value !== self::UNSUPPORTED) {
                $state[$property->getName()] = $value;
            }
        }

        return $state;
    }

    private const string UNSUPPORTED = "\0pam-unsupported";

    private static function encode(mixed $value, int $depth): mixed
    {
        if ($value === null || is_bool($value) || is_int($value) || is_float($value)) {
            return $value;
        }
        if (is_string($value)) {
            // JSON needs UTF-8; binary strings are not carried over.
            return preg_match('//u', $value) === 1 ? $value : self::UNSUPPORTED;
        }
        if ($value instanceof UnitEnum) {
            return ['$pamEnum' => $value::class, 'case' => $value->name];
        }
        if (!is_array($value) || $depth >= self::MAX_DEPTH) {
            return self::UNSUPPORTED;
        }
        $encoded = [];
        foreach ($value as $key => $item) {
            $item = self::encode($item, $depth + 1);
            if ($item === self::UNSUPPORTED) {
                return self::UNSUPPORTED;
            }
            $encoded[$key] = $item;
        }
        // Lists stay lists; maps keep their keys (a leading marker survives
        // JSON's object/array ambiguity for empty and integer-keyed maps).
        return array_is_list($encoded) ? $encoded : ['$pamMap' => $encoded];
    }

    private static function decode(mixed $value, int $depth): mixed
    {
        if (!is_array($value)) {
            return $value;
        }
        if ($depth >= self::MAX_DEPTH) {
            throw new \RuntimeException('Hot reload state is too deep.');
        }
        if (isset($value['$pamEnum'], $value['case']) && is_string($value['$pamEnum']) && is_string($value['case'])) {
            $class = $value['$pamEnum'];
            if (!enum_exists($class)) {
                throw new \RuntimeException('Hot reload enum no longer exists.');
            }
            return constant($class.'::'.$value['case']);
        }
        $items = isset($value['$pamMap']) && is_array($value['$pamMap']) ? $value['$pamMap'] : $value;
        $decoded = [];
        foreach ($items as $key => $item) {
            $decoded[$key] = self::decode($item, $depth + 1);
        }

        return $decoded;
    }

    /**
     * @param class-string $class
     * @return list<ReflectionProperty>
     */
    private static function stateProperties(string $class): array
    {
        if (isset(self::$stateProperties[$class])) {
            return self::$stateProperties[$class];
        }
        $properties = [];
        $reflection = new ReflectionClass($class);
        do {
            foreach ($reflection->getProperties() as $property) {
                if (
                    $property->isStatic()
                    || $property->isReadOnly()
                    || $property->getDeclaringClass()->getName() !== $reflection->getName()
                    || $property->getAttributes(StateAttribute::class) === []
                    || !self::acceptsPlainValues($property)
                ) {
                    continue;
                }
                $properties[$property->getName()] ??= $property;
            }
            $reflection = $reflection->getParentClass();
        } while ($reflection !== false && $reflection->getName() !== Component::class);

        return self::$stateProperties[$class] = array_values($properties);
    }

    /** Object-typed state (models, services, closures) is never carried over. */
    private static function acceptsPlainValues(ReflectionProperty $property): bool
    {
        $type = $property->getType();
        if ($type === null) {
            return true;
        }
        $types = $type instanceof ReflectionUnionType ? $type->getTypes() : [$type];
        foreach ($types as $candidate) {
            if (!$candidate instanceof ReflectionNamedType) {
                return false;
            }
            if ($candidate->isBuiltin()) {
                if (in_array($candidate->getName(), ['object', 'callable', 'iterable'], true)) {
                    return false;
                }
                continue;
            }
            if (!enum_exists($candidate->getName())) {
                return false;
            }
        }

        return true;
    }

    /** @return array<string, array{0: string, 1: array<string, mixed>}>|null */
    private static function load(): ?array
    {
        $path = self::path();
        if ($path === null || !is_file($path)) {
            return null;
        }
        $contents = @file_get_contents($path, length: self::MAX_BYTES + 1);
        // Consumed once: a later cold start never sees development state.
        @unlink($path);
        if (!is_string($contents) || strlen($contents) > self::MAX_BYTES) {
            return null;
        }
        try {
            $document = json_decode($contents, true, 64, JSON_THROW_ON_ERROR);
        } catch (\JsonException) {
            return null;
        }
        if (!is_array($document) || ($document['schema'] ?? null) !== self::SCHEMA || !is_array($document['components'] ?? null)) {
            return null;
        }
        $components = [];
        foreach ($document['components'] as $key => $entry) {
            if (
                is_string($key)
                && is_array($entry)
                && is_string($entry[0] ?? null)
                && is_array($entry[1] ?? null)
            ) {
                $components[$key] = [$entry[0], $entry[1]];
            }
        }

        return $components === [] ? null : $components;
    }

    private static function path(): ?string
    {
        $directory = getenv('PAM_NATIVE_STATE_DIR');
        if (!is_string($directory) || $directory === '' || str_contains($directory, "\0")) {
            return null;
        }

        return rtrim($directory, DIRECTORY_SEPARATOR).DIRECTORY_SEPARATOR.self::FILE;
    }
}
