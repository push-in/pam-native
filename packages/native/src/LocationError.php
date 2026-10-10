<?php

declare(strict_types=1);

namespace Pam\Native;

/**
 * Why a location request failed. Native failures arrive as
 * "<code>: <detail>" (for example "disabled: No enabled location provider");
 * the `$failure` callbacks keep receiving that string and fromFailure() turns
 * it into this enum. The detail is English diagnostics, not user copy: apps
 * show their own localized message per case.
 */
enum LocationError: int
{
    /** The app has no location permission (or it was revoked). */
    case Permission = 1;
    /** The system location switch is off: no enabled provider. Offer Location::requestServices(). */
    case Disabled = 2;
    /** No position could be obtained (no fix, no last known position, platform error). */
    case Unavailable = 3;
    /** No fix within `timeoutMs`. */
    case Timeout = 4;

    public static function fromFailure(string $failure): self
    {
        $separator = strpos($failure, ':');
        if ($separator !== false) {
            $code = substr($failure, 0, $separator);
            foreach (self::cases() as $case) {
                if ($case->code() === $code) {
                    return $case;
                }
            }
        }

        // Runtimes before 1.35.0 sent bare English messages.
        $message = strtolower($failure);

        return match (true) {
            str_contains($message, 'permission') => self::Permission,
            str_contains($message, 'no enabled location provider') => self::Disabled,
            str_contains($message, 'timed out'), str_contains($message, 'timeout') => self::Timeout,
            default => self::Unavailable,
        };
    }

    /** The diagnostic text after the "<code>: " prefix (the whole string when there is none). */
    public static function detail(string $failure): string
    {
        $separator = strpos($failure, ':');
        if ($separator !== false) {
            $code = substr($failure, 0, $separator);
            foreach (self::cases() as $case) {
                if ($case->code() === $code) {
                    return ltrim(substr($failure, $separator + 1));
                }
            }
        }

        return $failure;
    }

    /** The stable wire prefix sent by the Android and iOS hosts. */
    public function code(): string
    {
        return match ($this) {
            self::Permission => 'permission',
            self::Disabled => 'disabled',
            self::Unavailable => 'unavailable',
            self::Timeout => 'timeout',
        };
    }

    /** "<code>: <detail>", the failure payload the hosts send. */
    public function failure(string $detail): string
    {
        return $this->code().': '.$detail;
    }
}
