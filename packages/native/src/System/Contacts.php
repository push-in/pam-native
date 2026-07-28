<?php

declare(strict_types=1);

namespace Pam\Native\System;

use Closure;
use JsonException;
use Pam\Native\Contact;
use Pam\Native\Internal\Wire;
use Pam\Native\ModuleResultStatus;
use Pam\Native\Modules\NativeModules;
use RuntimeException;

final class Contacts
{
    private const PAGE_SIZE = 250;

    private function __construct()
    {
    }

    /** @param Closure(list<Contact>): void $callback */
    public static function all(Closure $callback): int
    {
        $contacts = [];
        $lastRequest = 0;
        $readPage = null;
        $readPage = static function (int $offset) use (&$readPage, &$contacts, &$lastRequest, $callback): void {
            $lastRequest = NativeModules::call(
                'contacts',
                'list',
                ['offset' => $offset, 'limit' => self::PAGE_SIZE],
                static function ($result) use (&$readPage, &$contacts, &$lastRequest, $offset, $callback): void {
                    if ($result->status === ModuleResultStatus::Failure) {
                        throw new RuntimeException($result->payload);
                    }

                    $values = Wire::decodeMap($result->payload);
                    $items = self::decodeItems((string) ($values['items'] ?? '[]'));
                    array_push($contacts, ...$items);
                    $hasMore = (bool) ($values['hasMore'] ?? false);
                    if ($hasMore) {
                        $next = $readPage;
                        if (!$next instanceof Closure) {
                            throw new RuntimeException('Contacts reader stopped unexpectedly.');
                        }
                        $next($offset + count($items));
                        return;
                    }
                    $readPage = null;
                    $callback($contacts);
                },
            );
        };
        $readPage(0);
        return $lastRequest;
    }

    /** @return list<Contact> */
    private static function decodeItems(string $payload): array
    {
        try {
            $items = json_decode($payload, true, flags: JSON_THROW_ON_ERROR);
        } catch (JsonException $error) {
            throw new RuntimeException('Native contacts payload is invalid.', previous: $error);
        }
        if (!is_array($items) || !array_is_list($items)) {
            throw new RuntimeException('Native contacts payload is invalid.');
        }

        return array_map(static function (mixed $item): Contact {
            if (!is_array($item)) {
                throw new RuntimeException('Native contact entry is invalid.');
            }
            return new Contact(
                id: (string) ($item['id'] ?? ''),
                displayName: (string) ($item['displayName'] ?? ''),
                givenName: (string) ($item['givenName'] ?? ''),
                familyName: (string) ($item['familyName'] ?? ''),
                phoneNumbers: self::stringList($item['phoneNumbers'] ?? []),
                emailAddresses: self::stringList($item['emailAddresses'] ?? []),
            );
        }, $items);
    }

    /** @return list<string> */
    private static function stringList(mixed $values): array
    {
        if (!is_array($values)) {
            return [];
        }
        return array_values(array_map('strval', array_filter(
            $values,
            static fn (mixed $value): bool => is_string($value) || is_numeric($value),
        )));
    }
}
