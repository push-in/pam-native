<?php

declare(strict_types=1);

namespace Pam\Native\UI;

final class TextInput
{
    private function __construct()
    {
    }

    public static function make(string $value = ''): Input
    {
        return Input::make($value);
    }
}
