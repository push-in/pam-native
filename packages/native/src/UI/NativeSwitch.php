<?php

declare(strict_types=1);

namespace Pam\Native\UI;

final class NativeSwitch
{
    private function __construct()
    {
    }

    public static function make(bool $checked = false): Toggle
    {
        return Toggle::make($checked);
    }
}
