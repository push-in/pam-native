<?php

declare(strict_types=1);

namespace Pam\Native\System;

use InvalidArgumentException;

/** One button of `Alert::choose()`. */
final readonly class AlertButton
{
    public function __construct(
        public string $label,
        public AlertButtonStyle $style = AlertButtonStyle::Default,
    ) {
        if (trim($label) === '') {
            throw new InvalidArgumentException('Alert button labels cannot be empty.');
        }
    }

    public static function make(string $label): self
    {
        return new self($label);
    }

    public static function cancel(string $label): self
    {
        return new self($label, AlertButtonStyle::Cancel);
    }

    public static function destructive(string $label): self
    {
        return new self($label, AlertButtonStyle::Destructive);
    }
}
