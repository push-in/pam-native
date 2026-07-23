<?php

declare(strict_types=1);

namespace Pam\Native;

use InvalidArgumentException;
use RuntimeException;

final class State
{
    private const MAX_BYTES = 1_048_576;

    /** @var array<string, mixed>|null */
    private static ?array $values = null;

    private function __construct()
    {
    }

    /**
     * @param string|int|float|bool|array<array-key, mixed>|null $default
     * @return string|int|float|bool|array<array-key, mixed>|null
     */
    public static function get(
        string $key,
        string|int|float|bool|array|null $default = null,
    ): string|int|float|bool|array|null {
        self::assertKey($key);

        $value = self::values()[$key] ?? $default;

        if (
            is_string($value)
            || is_int($value)
            || is_float($value)
            || is_bool($value)
            || is_array($value)
            || $value === null
        ) {
            return $value;
        }

        throw new RuntimeException('Pam Native persisted state contains an unsupported value.');
    }

    /** @param string|int|float|bool|array<array-key, mixed>|null $value */
    public static function set(string $key, string|int|float|bool|array|null $value): void
    {
        self::assertKey($key);
        self::assertValue($value);
        $values = self::values();
        $values[$key] = $value;
        self::store($values);
    }

    public static function forget(string $key): void
    {
        self::assertKey($key);
        $values = self::values();
        unset($values[$key]);
        self::store($values);
    }

    /** @return array<string, mixed> */
    public static function all(): array
    {
        return self::values();
    }

    public static function resetCache(): void
    {
        self::$values = null;
    }

    /** @return array<string, mixed> */
    private static function values(): array
    {
        if (self::$values !== null) {
            return self::$values;
        }
        $path = self::path();
        if (!is_file($path)) {
            return self::$values = [];
        }
        $contents = file_get_contents($path);
        if ($contents === false || strlen($contents) > self::MAX_BYTES) {
            throw new RuntimeException('Cannot read Pam Native persisted state.');
        }
        $decoded = json_decode($contents, true, 64, JSON_THROW_ON_ERROR);
        if (!is_array($decoded)) {
            throw new RuntimeException('Pam Native persisted state is invalid.');
        }

        $values = [];

        foreach ($decoded as $key => $value) {
            if (!is_string($key) || !self::isValue($value)) {
                throw new RuntimeException('Pam Native persisted state is invalid.');
            }

            $values[$key] = $value;
        }

        return self::$values = $values;
    }

    /** @param array<string, mixed> $values */
    private static function store(array $values): void
    {
        $encoded = json_encode($values, JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES);
        if (strlen($encoded) > self::MAX_BYTES) {
            throw new RuntimeException('Pam Native persisted state exceeds one MiB.');
        }
        $path = self::path();
        $directory = dirname($path);
        if (!is_dir($directory) && !mkdir($directory, 0o700, true) && !is_dir($directory)) {
            throw new RuntimeException('Cannot create the Pam Native state directory.');
        }
        $temporary = tempnam($directory, 'state-');
        if ($temporary === false || file_put_contents($temporary, $encoded, LOCK_EX) === false) {
            throw new RuntimeException('Cannot write Pam Native persisted state.');
        }
        if (!rename($temporary, $path)) {
            @unlink($temporary);
            throw new RuntimeException('Cannot activate Pam Native persisted state.');
        }
        @chmod($path, 0o600);
        self::$values = $values;
    }

    private static function path(): string
    {
        $directory = getenv('PAM_NATIVE_STATE_DIR');
        if (!is_string($directory) || $directory === '' || str_contains($directory, "\0")) {
            $directory = sys_get_temp_dir().'/pam-native-state';
        }

        return rtrim($directory, DIRECTORY_SEPARATOR).'/state.json';
    }

    private static function assertKey(string $key): void
    {
        if (preg_match('/^[A-Za-z][A-Za-z0-9_.-]{0,127}$/', $key) !== 1) {
            throw new InvalidArgumentException('State keys must be safe identifiers.');
        }
    }

    /** @param string|int|float|bool|array<array-key, mixed>|null $value */
    private static function assertValue(string|int|float|bool|array|null $value): void
    {
        if (!self::isValue($value)) {
            throw new InvalidArgumentException('State values must contain only JSON scalar or array values.');
        }

        json_encode($value, JSON_THROW_ON_ERROR);
    }

    private static function isValue(mixed $value): bool
    {
        if (
            is_string($value)
            || is_int($value)
            || is_float($value)
            || is_bool($value)
            || $value === null
        ) {
            return true;
        }
        if (!is_array($value)) {
            return false;
        }

        foreach ($value as $nested) {
            if (!self::isValue($nested)) {
                return false;
            }
        }

        return true;
    }
}
