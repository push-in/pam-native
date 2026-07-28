<?php

declare(strict_types=1);

namespace Pam\Native;

use Closure;

final class Watch
{
    private function __construct()
    {
    }

    /** @param Closure(): mixed $value @param Closure(mixed, mixed): void $changed */
    public static function value(Closure $value, Closure $changed): Effect
    {
        $initialized = false;
        $previous = null;

        return Effect::watch(
            $value,
            static function (mixed $current) use (&$initialized, &$previous, $changed): void {
                if ($initialized) {
                    $changed($current, $previous);
                }
                $previous = $current;
                $initialized = true;
            },
        );
    }
}
