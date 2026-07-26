<?php

declare(strict_types=1);

namespace Pam\Native\Forms\Attributes;

use Attribute;
use Pam\Native\Forms\NativeForm;
use Pam\Native\Forms\ValidationRule;

#[Attribute(Attribute::TARGET_PROPERTY)]
final readonly class Matches implements ValidationRule
{
    public function __construct(
        public string $other,
        public string $message = 'The values do not match.',
    ) {
    }

    public function validate(string $field, mixed $value, NativeForm $form): ?string
    {
        unset($field);

        return $form->value($this->other) === $value ? null : $this->message;
    }
}
