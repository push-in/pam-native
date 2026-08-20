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
        return self::adaptive(ThemeMode::Dark);
    }

    public static function adaptive(ThemeMode $mode): self
    {
        $palette = match ($mode) {
            ThemeMode::Light => [
                'surface' => 0xFFF8FAFC,
                'surfaceMuted' => 0xFFFFFFFF,
                'text' => 0xFF0F172A,
                'mutedText' => 0xFF475569,
                'border' => 0xFFCBD5E1,
                'accent' => 0xFF166534,
                'onAccent' => 0xFFFFFFFF,
                'danger' => 0xFFB91C1C,
                'onDanger' => 0xFFFFFFFF,
                'focus' => 0xFF2563EB,
            ],
            ThemeMode::Dark => [
                'surface' => 0xFF0F172A,
                'surfaceMuted' => 0xFF1E293B,
                'text' => 0xFFF8FAFC,
                'mutedText' => 0xFFCBD5E1,
                'border' => 0xFF475569,
                'accent' => 0xFF4ADE80,
                'onAccent' => 0xFF052E16,
                'danger' => 0xFFF87171,
                'onDanger' => 0xFF450A0A,
                'focus' => 0xFF60A5FA,
            ],
            ThemeMode::HighContrast => [
                'surface' => 0xFF000000,
                'surfaceMuted' => 0xFF111111,
                'text' => 0xFFFFFFFF,
                'mutedText' => 0xFFFFFFFF,
                'border' => 0xFFFFFFFF,
                'accent' => 0xFFFFFF00,
                'onAccent' => 0xFF000000,
                'danger' => 0xFFFF6B6B,
                'onDanger' => 0xFF000000,
                'focus' => 0xFFFFFF00,
            ],
        };
        self::assertAccessiblePalette($palette);

        return new self([
            'surface' => [
                PropKey::BackgroundColor->value => $palette['surface'],
            ],
            'surface-muted' => [
                PropKey::BackgroundColor->value => $palette['surfaceMuted'],
            ],
            'card' => [
                PropKey::BackgroundColor->value => $palette['surfaceMuted'],
                PropKey::BorderColor->value => $palette['border'],
                PropKey::BorderWidth->value => 1.0,
                PropKey::BorderRadius->value => DesignTokens::RadiusMedium,
                PropKey::Padding->value => DesignTokens::Space4,
            ],
            'text-primary' => [
                PropKey::TextColor->value => $palette['text'],
                PropKey::FontSize->value => DesignTokens::TextBody,
                PropKey::LineHeight->value => 24.0,
            ],
            'text-muted' => [
                PropKey::TextColor->value => $palette['mutedText'],
                PropKey::FontSize->value => DesignTokens::TextBody,
                PropKey::LineHeight->value => 24.0,
            ],
            'heading' => [
                PropKey::TextColor->value => $palette['text'],
                PropKey::FontWeight->value => 700,
                PropKey::FontSize->value => DesignTokens::TextHeadline,
                PropKey::LineHeight->value => 32.0,
            ],
            'accent' => [
                PropKey::BackgroundColor->value => $palette['accent'],
                PropKey::TextColor->value => $palette['onAccent'],
            ],
            'danger' => [
                PropKey::BackgroundColor->value => $palette['danger'],
                PropKey::TextColor->value => $palette['onDanger'],
            ],
            'metric' => [
                PropKey::TextColor->value => $palette['accent'],
                PropKey::FontWeight->value => 700,
                PropKey::FontSize->value => DesignTokens::TextTitle,
            ],
            'label' => [
                PropKey::TextColor->value => $palette['mutedText'],
                PropKey::FontSize->value => DesignTokens::TextLabel,
            ],
            'button-primary' => [
                PropKey::BackgroundColor->value => $palette['accent'],
                PropKey::TextColor->value => $palette['onAccent'],
                PropKey::MinHeight->value => DesignTokens::TouchTarget,
                PropKey::PaddingHorizontal->value => DesignTokens::Space4,
                PropKey::BorderRadius->value => DesignTokens::RadiusMedium,
                PropKey::AnimationDurationMs->value => DesignTokens::MotionFastMs,
            ],
            'button-secondary' => [
                PropKey::BackgroundColor->value => $palette['surfaceMuted'],
                PropKey::TextColor->value => $palette['text'],
                PropKey::BorderColor->value => $palette['border'],
                PropKey::BorderWidth->value => 1.0,
                PropKey::MinHeight->value => DesignTokens::TouchTarget,
                PropKey::PaddingHorizontal->value => DesignTokens::Space4,
                PropKey::BorderRadius->value => DesignTokens::RadiusMedium,
            ],
            'input' => [
                PropKey::BackgroundColor->value => $palette['surfaceMuted'],
                PropKey::TextColor->value => $palette['text'],
                PropKey::PlaceholderColor->value => $palette['mutedText'],
                PropKey::BorderColor->value => $palette['border'],
                PropKey::BorderWidth->value => 1.0,
                PropKey::MinHeight->value => DesignTokens::TouchTarget,
                PropKey::PaddingHorizontal->value => DesignTokens::Space3,
                PropKey::BorderRadius->value => DesignTokens::RadiusSmall,
            ],
            'focus-ring' => [
                PropKey::BorderColor->value => $palette['focus'],
                PropKey::BorderWidth->value => 3.0,
            ],
        ]);
    }

    public static function contrastRatio(int $foreground, int $background): float
    {
        $foregroundLuminance = self::relativeLuminance($foreground);
        $backgroundLuminance = self::relativeLuminance($background);
        return (max($foregroundLuminance, $backgroundLuminance) + 0.05)
            / (min($foregroundLuminance, $backgroundLuminance) + 0.05);
    }

    public function apply(): void
    {
        foreach ($this->classes as $name => $properties) {
            TemplateRegistry::style($name, $properties);
        }
    }

    /** @param array<string, int> $palette */
    private static function assertAccessiblePalette(array $palette): void
    {
        foreach ([
            [$palette['text'], $palette['surface']],
            [$palette['mutedText'], $palette['surface']],
            [$palette['mutedText'], $palette['surfaceMuted']],
            [$palette['text'], $palette['surfaceMuted']],
            [$palette['onAccent'], $palette['accent']],
            [$palette['onDanger'], $palette['danger']],
            [$palette['accent'], $palette['surface']],
        ] as [$foreground, $background]) {
            if (self::contrastRatio($foreground, $background) < 4.5) {
                throw new \LogicException('PAM Native theme contains a text pair below WCAG AA contrast.');
            }
        }
        foreach ([
            [$palette['focus'], $palette['surface']],
            [$palette['focus'], $palette['surfaceMuted']],
        ] as [$foreground, $background]) {
            if (self::contrastRatio($foreground, $background) < 3.0) {
                throw new \LogicException('PAM Native theme contains a focus indicator below WCAG non-text contrast.');
            }
        }
    }

    private static function relativeLuminance(int $color): float
    {
        $channels = [($color >> 16) & 0xFF, ($color >> 8) & 0xFF, $color & 0xFF];
        $linear = array_map(static function (int $channel): float {
            $value = $channel / 255;
            return $value <= 0.04045
                ? $value / 12.92
                : (($value + 0.055) / 1.055) ** 2.4;
        }, $channels);
        return 0.2126 * $linear[0] + 0.7152 * $linear[1] + 0.0722 * $linear[2];
    }
}
