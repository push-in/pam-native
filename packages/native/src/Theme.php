<?php

declare(strict_types=1);

namespace Pam\Native;

final readonly class Theme
{
    /** @param array<string, array<int, string|int|float|bool>> $classes */
    public function __construct(
        public array $classes,
    ) {
    }

    public static function pamLab(): self
    {
        return new self([
            'surface' => [
                PropKey::BackgroundColor->value => 0xFF0F172A,
            ],
            'surface-muted' => [
                PropKey::BackgroundColor->value => 0xFF1E293B,
            ],
            'card' => [
                PropKey::BackgroundColor->value => 0xFF1E293B,
                PropKey::BorderColor->value => 0xFF475569,
                PropKey::BorderWidth->value => 1.0,
                PropKey::BorderRadius->value => 12.0,
                PropKey::Padding->value => 16.0,
            ],
            'text-primary' => [
                PropKey::TextColor->value => 0xFFF8FAFC,
            ],
            'text-muted' => [
                PropKey::TextColor->value => 0xFFCBD5E1,
            ],
            'accent' => [
                PropKey::BackgroundColor->value => 0xFF22C55E,
                PropKey::TextColor->value => 0xFF052E16,
            ],
            'danger' => [
                PropKey::BackgroundColor->value => 0xFFEF4444,
                PropKey::TextColor->value => 0xFFFFFFFF,
            ],
            'metric' => [
                PropKey::TextColor->value => 0xFF86EFAC,
                PropKey::FontWeight->value => 700,
                PropKey::FontSize->value => 20.0,
            ],
            'label' => [
                PropKey::TextColor->value => 0xFFCBD5E1,
                PropKey::FontSize->value => 14.0,
            ],
        ]);
    }

    public function apply(): void
    {
        foreach ($this->classes as $name => $properties) {
            TemplateRegistry::style($name, $properties);
        }
    }
}
