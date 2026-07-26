<?php

declare(strict_types=1);

namespace Pam\Native\Forms;

use Closure;
use InvalidArgumentException;
use Pam\Native\State;
use ReflectionClass;
use ReflectionProperty;

abstract class NativeForm
{
    /** @var array<string, list<string>> */
    private array $errors = [];

    /** @var array<string, true> */
    private array $dirty = [];

    /** @var array<string, true> */
    private array $touched = [];

    private FormStatus $status = FormStatus::Idle;

    final public function status(): FormStatus
    {
        return $this->status;
    }

    final public function beginSubmit(): bool
    {
        $this->status = FormStatus::Validating;
        if (!$this->validate()) {
            $this->status = FormStatus::Failure;
            return false;
        }
        $this->status = FormStatus::Submitting;

        return true;
    }

    final public function succeed(): void
    {
        $this->errors = [];
        $this->dirty = [];
        $this->status = FormStatus::Success;
    }

    final public function fail(array $errors = []): void
    {
        $this->setServerErrors($errors);
        $this->status = FormStatus::Failure;
    }

    final public function set(string $field, mixed $value, bool $touch = true): void
    {
        $property = $this->property($field);
        if ($property->isReadOnly()) {
            throw new InvalidArgumentException("Form field {$field} is read-only.");
        }
        $property->setValue($this, $value);
        $this->dirty[$field] = true;
        if ($touch) {
            $this->touched[$field] = true;
        }
        $this->status = FormStatus::Editing;
        unset($this->errors[$field]);
    }

    final public function value(string $field): mixed
    {
        return $this->property($field)->getValue($this);
    }

    /** @param array<string, mixed> $values */
    final public function fill(array $values, bool $touch = false): void
    {
        foreach ($values as $field => $value) {
            if (is_string($field)) {
                $this->set($field, $value, $touch);
            }
        }
    }

    final public function validate(?string $only = null): bool
    {
        $properties = $only === null
            ? $this->fields()
            : [$this->property($only)];

        foreach ($properties as $property) {
            $field = $property->getName();
            unset($this->errors[$field]);
            foreach ($property->getAttributes(ValidationRule::class, \ReflectionAttribute::IS_INSTANCEOF) as $attribute) {
                $message = $attribute->newInstance()->validate(
                    $field,
                    $property->getValue($this),
                    $this,
                );
                if ($message !== null) {
                    $this->errors[$field][] = $message;
                    break;
                }
            }
        }

        return $this->errors === [];
    }

    /** @param array<string, string|list<string>> $errors */
    final public function setServerErrors(array $errors): void
    {
        foreach ($errors as $field => $messages) {
            if (!is_string($field)) {
                continue;
            }
            $this->property($field);
            $normalized = is_string($messages) ? [$messages] : $messages;
            $this->errors[$field] = array_values(array_filter(
                $normalized,
                static fn (mixed $message): bool => is_string($message) && $message !== '',
            ));
            $this->touched[$field] = true;
        }
    }

    /** @return array<string, list<string>> */
    final public function errors(): array
    {
        return $this->errors;
    }

    final public function error(string $field): ?string
    {
        return $this->errors[$field][0] ?? null;
    }

    final public function firstErrorField(): ?string
    {
        return array_key_first($this->errors);
    }

    final public function validateWith(Closure $validator): bool
    {
        $result = $validator($this->values(), $this);
        if ($result === null || $result === []) {
            return true;
        }
        if (!is_array($result)) {
            throw new InvalidArgumentException(
                'Custom form validators must return an error map, an empty array, or null.',
            );
        }
        $this->setServerErrors($result);

        return false;
    }

    final public function isDirty(?string $field = null): bool
    {
        return $field === null ? $this->dirty !== [] : isset($this->dirty[$field]);
    }

    final public function isTouched(string $field): bool
    {
        return isset($this->touched[$field]);
    }

    final public function resetInteraction(): void
    {
        $this->errors = [];
        $this->dirty = [];
        $this->touched = [];
        $this->status = FormStatus::Idle;
    }

    /** @return array<string, mixed> */
    final public function values(): array
    {
        $values = [];
        foreach ($this->fields() as $property) {
            $values[$property->getName()] = $property->getValue($this);
        }

        return $values;
    }

    final public function saveDraft(string $key): void
    {
        State::set('form-draft.'.$key, $this->values());
    }

    final public function restoreDraft(string $key): bool
    {
        $draft = State::get('form-draft.'.$key);
        if (!is_array($draft)) {
            return false;
        }
        $this->fill($draft);
        $this->dirty = [];
        $this->touched = [];
        $this->status = FormStatus::Idle;

        return true;
    }

    final public function forgetDraft(string $key): void
    {
        State::forget('form-draft.'.$key);
    }

    private function property(string $field): ReflectionProperty
    {
        if (
            preg_match('/^[A-Za-z][A-Za-z0-9_]{0,63}$/D', $field) !== 1
            || !(new ReflectionClass($this))->hasProperty($field)
        ) {
            throw new InvalidArgumentException("Unknown form field {$field}.");
        }
        $property = new ReflectionProperty($this, $field);
        if (!$property->isPublic() || $property->isStatic()) {
            throw new InvalidArgumentException("Form field {$field} must be public.");
        }

        return $property;
    }

    /** @return list<ReflectionProperty> */
    private function fields(): array
    {
        return array_values(array_filter(
            (new ReflectionClass($this))->getProperties(ReflectionProperty::IS_PUBLIC),
            static fn (ReflectionProperty $property): bool => !$property->isStatic(),
        ));
    }
}
