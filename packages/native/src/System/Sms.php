<?php

declare(strict_types=1);

namespace Pam\Native\System;

use Closure;
use InvalidArgumentException;
use Pam\Native\Internal\Wire;
use Pam\Native\ModuleResultStatus;
use Pam\Native\Modules\NativeModules;
use RuntimeException;

final class Sms
{
    private function __construct()
    {
    }

    /** @param Closure(bool): void $callback */
    public static function isAvailable(Closure $callback, ?Closure $failed = null): int
    {
        return NativeModules::call(
            'sms',
            'isAvailable',
            [],
            static function ($result) use ($callback, $failed): void {
                if ($result->status === ModuleResultStatus::Failure) {
                    if ($failed !== null) {
                        $failed($result->payload);

                        return;
                    }
                    throw new RuntimeException($result->payload);
                }
                $values = Wire::decodeMap($result->payload);
                $callback((bool) ($values['available'] ?? false));
            },
        );
    }

    /**
     * Opens the platform SMS composer without sending a message automatically.
     *
     * @param list<string> $recipients
     */
    public static function compose(
        array $recipients,
        string $body = '',
        ?Closure $opened = null,
        ?Closure $failed = null,
    ): int {
        $normalized = [];
        foreach ($recipients as $recipient) {
            $recipient = trim($recipient);
            if ($recipient === '') {
                continue;
            }
            if (str_contains($recipient, "\n") || str_contains($recipient, "\r")) {
                throw new InvalidArgumentException('SMS recipients cannot contain line breaks.');
            }
            if (strlen($recipient) > 128) {
                throw new InvalidArgumentException('SMS recipient exceeds 128 bytes.');
            }
            $normalized[] = $recipient;
        }
        $normalized = array_values(array_unique($normalized));
        if ($normalized === [] || count($normalized) > 50) {
            throw new InvalidArgumentException('SMS requires between 1 and 50 recipients.');
        }
        if (strlen($body) > 10_000) {
            throw new InvalidArgumentException('SMS body exceeds 10000 bytes.');
        }

        return NativeModules::call(
            'sms',
            'compose',
            ['recipients' => implode("\n", $normalized), 'body' => $body],
            static function ($result) use ($opened, $failed): void {
                if ($result->status === ModuleResultStatus::Failure) {
                    if ($failed !== null) {
                        $failed($result->payload);

                        return;
                    }
                    throw new RuntimeException($result->payload);
                }
                $opened?->__invoke();
            },
        );
    }
}
