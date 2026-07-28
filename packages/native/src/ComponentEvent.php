<?php

declare(strict_types=1);

namespace Pam\Native;

interface ComponentEvent
{
    public function name(): string;

    public function payload(): mixed;
}
