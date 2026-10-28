<?php

declare(strict_types=1);

namespace Pam\Native\Routing;

enum RedirectMode: int
{
    /** Go back to the route when it is in history, otherwise push it. */
    case Navigate = 1;
    /** Always push a new entry. */
    case Push = 2;
    /** Replace the current entry. */
    case Replace = 3;
    /** Clear the history and start from the route. */
    case Reset = 4;

    /** Accepts the enum or its lower-case name: 'navigate', 'push', 'replace', 'reset'. */
    public static function parse(self|string $mode): self
    {
        if ($mode instanceof self) return $mode;

        return match (strtolower($mode)) {
            'navigate' => self::Navigate,
            'push' => self::Push,
            'replace' => self::Replace,
            'reset' => self::Reset,
            default => throw new \InvalidArgumentException("Navigation mode must be 'navigate', 'push', 'replace' or 'reset', '{$mode}' given."),
        };
    }
}
