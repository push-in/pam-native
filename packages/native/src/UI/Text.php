<?php

declare(strict_types=1);

namespace Pam\Native\UI;

use Pam\Native\Element;
use Pam\Native\NodeKind;
use Pam\Native\PropKey;
use Pam\Native\TextBreakStrategy;
use Pam\Native\TextDataDetectorType;
use Pam\Native\TextEllipsizeMode;
use Pam\Native\TextHyphenationFrequency;

final class Text extends Element
{
    public static function make(string $text): self
    {
        return (new self(NodeKind::Text))->withProperty(PropKey::Text, $text);
    }

    public function numberOfLines(int $lines): self
    {
        return $this->withProperty(PropKey::NumberOfLines, max(0, $lines));
    }

    public function selectable(bool $selectable = true): self
    {
        return $this->withProperty(PropKey::TextSelectable, $selectable);
    }

    public function selectionColor(int $color): self
    {
        return $this->withProperty(PropKey::SelectionColor, $color);
    }

    public function ellipsize(TextEllipsizeMode $mode): self
    {
        return $this->withProperty(PropKey::TextEllipsizeMode, $mode->value);
    }

    public function allowFontScaling(bool $allow = true): self
    {
        return $this->withProperty(PropKey::TextAllowFontScaling, $allow);
    }

    public function maxFontSizeMultiplier(float $multiplier): self
    {
        return $this->withProperty(
            PropKey::TextMaxFontSizeMultiplier,
            max(0.0, $multiplier),
        );
    }

    public function adjustsFontSizeToFit(
        bool $adjust = true,
        float $minimumScale = 0.01,
    ): self {
        return $this
            ->withProperty(PropKey::TextAdjustsFontSizeToFit, $adjust)
            ->withProperty(
                PropKey::TextMinimumFontScale,
                min(1.0, max(0.01, $minimumScale)),
            );
    }

    public function breakStrategy(TextBreakStrategy $strategy): self
    {
        return $this->withProperty(PropKey::TextBreakStrategy, $strategy->value);
    }

    public function hyphenation(TextHyphenationFrequency $frequency): self
    {
        return $this->withProperty(
            PropKey::TextHyphenationFrequency,
            $frequency->value,
        );
    }

    public function dataDetector(TextDataDetectorType $type): self
    {
        return $this->withProperty(PropKey::TextDataDetectorType, $type->value);
    }
}
