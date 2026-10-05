<?php

declare(strict_types=1);

namespace Pam\Native\System;

use Closure;
use InvalidArgumentException;
use Pam\Native\Internal\Wire;
use Pam\Native\ModuleResultStatus;
use Pam\Native\Modules\NativeModules;

/** Screen-reader integration (TalkBack / VoiceOver). */
final class Accessibility
{
    private function __construct()
    {
    }

    /**
     * Speaks a polite announcement through the active screen reader, e.g. after
     * a message was sent or an upload finished. No-op when none is running.
     *
     * @param null|Closure(bool): void $callback Receives whether a screen reader received it.
     */
    public static function announce(string $text, ?Closure $callback = null): int
    {
        $text = trim($text);
        if ($text === '' || mb_strlen($text) > 4_096) {
            throw new InvalidArgumentException('Announcements must contain between 1 and 4096 characters.');
        }

        return NativeModules::call('accessibility', 'announce', ['text' => $text], static function ($result) use ($callback): void {
            if ($result->status === ModuleResultStatus::Failure) {
                $callback?->__invoke(false);

                return;
            }
            $callback?->__invoke((bool) (Wire::decodeMap($result->payload)['delivered'] ?? false));
        });
    }

    /** @param Closure(bool): void $callback Receives whether a screen reader is active. */
    public static function screenReaderEnabled(Closure $callback): int
    {
        return NativeModules::call('accessibility', 'status', [], static function ($result) use ($callback): void {
            if ($result->status === ModuleResultStatus::Failure) {
                $callback(false);

                return;
            }
            $values = Wire::decodeMap($result->payload);
            $callback((bool) ($values['touchExploration'] ?? $values['enabled'] ?? false));
        });
    }
}
