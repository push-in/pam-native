<?php

declare(strict_types=1);

namespace Pam\Native;

enum ModalOrientation: int
{
    case Portrait = 1;
    case Landscape = 2;

    public static function fromPayload(string $payload): self
    {
        return self::tryFrom((int) $payload) ?? self::Portrait;
    }
}
