<?php

declare(strict_types=1);

namespace Pam\Native\Forms\Attributes;

use Attribute;
use Pam\Native\Forms\NativeForm;
use Pam\Native\Forms\ValidationRule;

#[Attribute(Attribute::TARGET_PROPERTY)]
final readonly class Email implements ValidationRule
{
    public function __construct(
        public string $message = 'Enter a valid email address.',
    ) {
    }

    public function validate(string $field, mixed $value, NativeForm $form): ?string
    {
        unset($field, $form);

        if ($value === null || $value === '') {
            return null;
        }

        return is_string($value) && filter_var($value, FILTER_VALIDATE_EMAIL) !== false
            ? null
            : $this->message;
    }
}
