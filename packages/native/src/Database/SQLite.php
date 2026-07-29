<?php

declare(strict_types=1);

namespace Pam\Native\Database;

use Closure;
use InvalidArgumentException;
use JsonException;
use Pam\Native\Internal\Wire;
use Pam\Native\ModuleResultStatus;
use Pam\Native\Modules\NativeModules;
use RuntimeException;

final class SQLite
{
    private function __construct()
    {
    }

    /** @param list<string|int|float|bool|null> $arguments */
    public static function execute(
        string $database,
        string $sql,
        array $arguments = [],
        ?Closure $callback = null,
    ): int {
        return self::call('execute', $database, $sql, $arguments, $callback);
    }

    /**
     * @param list<string|int|float|bool|null> $arguments
     * @param Closure(list<array<string, string|int|float|bool|null>>): void $callback
     */
    public static function query(
        string $database,
        string $sql,
        array $arguments,
        Closure $callback,
    ): int {
        return self::call('query', $database, $sql, $arguments, $callback);
    }

    /**
     * Executes one prepared statement for every argument set inside one native transaction.
     *
     * @param list<list<string|int|float|bool|null>> $argumentSets
     */
    public static function executeMany(
        string $database,
        string $sql,
        array $argumentSets,
        ?Closure $callback = null,
    ): int {
        if ($argumentSets === [] || count($argumentSets) > 10_000) {
            throw new InvalidArgumentException(
                'SQLite executeMany requires between 1 and 10000 argument sets.',
            );
        }

        return self::call('executeMany', $database, $sql, $argumentSets, $callback);
    }

    /**
     * Executes heterogeneous prepared statements atomically in one native transaction.
     *
     * @param list<array{
     *   sql: string,
     *   arguments?: list<string|int|float|bool|null>,
     *   argumentSets?: list<list<string|int|float|bool|null>>
     * }> $statements
     */
    public static function transaction(
        string $database,
        array $statements,
        ?Closure $callback = null,
    ): int {
        self::validateName($database);
        if ($statements === [] || count($statements) > 10_000) {
            throw new InvalidArgumentException(
                'SQLite transaction requires between 1 and 10000 statements.',
            );
        }
        $normalized = [];
        foreach ($statements as $statement) {
            $sql = $statement['sql'] ?? '';
            $arguments = $statement['arguments'] ?? [];
            $argumentSets = $statement['argumentSets'] ?? null;
            if (!is_string($sql) || $sql === '' || strlen($sql) > 1_048_576) {
                throw new InvalidArgumentException(
                    'Every SQLite transaction SQL statement must contain between 1 and 1048576 bytes.',
                );
            }
            if (!is_array($arguments)) {
                throw new InvalidArgumentException(
                    'SQLite transaction arguments must be arrays.',
                );
            }
            if ($argumentSets !== null
                && (!is_array($argumentSets)
                    || $argumentSets === []
                    || count($argumentSets) > 10_000)) {
                throw new InvalidArgumentException(
                    'SQLite transaction argumentSets must contain between 1 and 10000 rows.',
                );
            }
            $normalized[] = [
                'sql' => $sql,
                'arguments' => array_values($arguments),
                ...($argumentSets === null
                    ? []
                    : ['argumentSets' => array_values($argumentSets)]),
            ];
        }
        try {
            $encoded = json_encode(
                $normalized,
                JSON_THROW_ON_ERROR | JSON_UNESCAPED_UNICODE,
            );
        } catch (JsonException $error) {
            throw new InvalidArgumentException(
                'SQLite transaction arguments must be JSON scalars.',
                0,
                $error,
            );
        }

        return NativeModules::call(
            'sqlite',
            'transaction',
            [
                'database' => $database,
                'sql' => '',
                'arguments' => $encoded,
            ],
            static function ($result) use ($callback): void {
                if ($result->status === ModuleResultStatus::Failure) {
                    throw new RuntimeException($result->payload);
                }
                $callback?->__invoke();
            },
        );
    }

    /**
     * @param list<string|int|float|bool|null>|list<list<string|int|float|bool|null>> $arguments
     */
    private static function call(
        string $method,
        string $database,
        string $sql,
        array $arguments,
        ?Closure $callback,
    ): int {
        self::validateName($database);
        if ($sql === '' || strlen($sql) > 1_048_576) {
            throw new InvalidArgumentException('SQLite SQL must contain between 1 and 1048576 bytes.');
        }

        try {
            $encodedArguments = json_encode(
                array_values($arguments),
                JSON_THROW_ON_ERROR | JSON_UNESCAPED_UNICODE,
            );
        } catch (JsonException $error) {
            throw new InvalidArgumentException('SQLite arguments must be JSON scalars.', 0, $error);
        }

        return NativeModules::call(
            'sqlite',
            $method,
            ['database' => $database, 'sql' => $sql, 'arguments' => $encodedArguments],
            static function ($result) use ($callback, $method): void {
                if ($result->status === ModuleResultStatus::Failure) {
                    throw new RuntimeException($result->payload);
                }
                if ($callback === null) {
                    return;
                }
                if ($method !== 'query') {
                    $callback();

                    return;
                }
                $values = Wire::decodeMap($result->payload);
                $rows = json_decode(
                    (string) ($values['rows'] ?? '[]'),
                    true,
                    512,
                    JSON_THROW_ON_ERROR,
                );
                $callback(is_array($rows) ? $rows : []);
            },
        );
    }

    private static function validateName(string $database): void
    {
        if (preg_match('/^[A-Za-z0-9_.-]{1,128}$/D', $database) !== 1) {
            throw new InvalidArgumentException('SQLite database name is invalid.');
        }
    }
}
