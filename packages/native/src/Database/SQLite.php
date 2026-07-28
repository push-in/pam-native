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

    /** @param list<string|int|float|bool|null> $arguments */
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
                $values = Wire::decodeMap($result->payload);
                if ($method === 'query') {
                    $rows = json_decode(
                        (string) ($values['rows'] ?? '[]'),
                        true,
                        512,
                        JSON_THROW_ON_ERROR,
                    );
                    $callback(is_array($rows) ? $rows : []);
                    return;
                }
                $callback();
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
