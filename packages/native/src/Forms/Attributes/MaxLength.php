<?php

declare(strict_types=1);

namespace Pam\Native\Forms\Attributes;

use Attribute;
use InvalidArgumentException;
use Pam\Native\Forms\NativeForm;
use Pam\Native\Forms\ValidationRule;

#[Attribute(Attribute::TARGET_PROPERTY)]
final readonly class MaxLength implements ValidationRule
{
    public function __construct(
        public int $length,
        public ?string $message = null,
    ) {
        if ($length < 0) {
            throw new InvalidArgumentException('Maximum length cannot be negative.');
        }
    }

    public function validate(string $field, mixed $value, NativeForm $form): ?string
    {
        unset($field, $form);
        if ($value === null) {
            return null;
        }
        $length = is_string($value)
            ? (function_exists('mb_strlen') ? mb_strlen($value) : strlen($value))
            : (is_array($value) ? count($value) : null);

        return $length !== null && $length <= $this->length
            ? null
            : ($this->message ?? "Use no more than {$this->length} characters.");
    }
}
