<?php

declare(strict_types=1);

namespace Pam\Native;

final readonly class Style
{
    public function __construct(
        public ?float $width = null,
        public ?float $height = null,
        public ?float $flexGrow = null,
        public ?float $padding = null,
        public ?float $paddingHorizontal = null,
        public ?float $paddingVertical = null,
        public ?float $gap = null,
        public ?float $margin = null,
        public ?float $marginHorizontal = null,
        public ?float $marginVertical = null,
        public ?float $minWidth = null,
        public ?float $minHeight = null,
        public ?float $maxWidth = null,
        public ?float $maxHeight = null,
        public ?int $backgroundColor = null,
        public ?int $textColor = null,
        public ?float $fontSize = null,
        public ?float $borderRadius = null,
        public ?float $borderWidth = null,
        public ?int $borderColor = null,
        public ?float $opacity = null,
        public ?Align $alignItems = null,
        public ?Align $alignSelf = null,
        public ?Justify $justifyContent = null,
        public ?TextAlignment $textAlign = null,
        public ?int $fontWeight = null,
        public ?float $elevation = null,
        public ?float $translationX = null,
        public ?float $translationY = null,
        public ?float $scaleX = null,
        public ?float $scaleY = null,
        public ?float $rotation = null,
        public ?float $letterSpacing = null,
        public ?float $lineHeight = null,
        public ?int $zIndex = null,
        public ?Overflow $overflow = null,
        public ?FlexDirection $flexDirection = null,
    ) {
    }

    /** @return array<int, float|int> */
    public function properties(): array
    {
        $properties = [];

        foreach ([
            PropKey::Width->value => $this->width,
            PropKey::Height->value => $this->height,
            PropKey::FlexGrow->value => $this->flexGrow,
            PropKey::Padding->value => $this->padding,
            PropKey::PaddingHorizontal->value => $this->paddingHorizontal,
            PropKey::PaddingVertical->value => $this->paddingVertical,
            PropKey::Gap->value => $this->gap,
            PropKey::Margin->value => $this->margin,
            PropKey::MarginHorizontal->value => $this->marginHorizontal,
            PropKey::MarginVertical->value => $this->marginVertical,
            PropKey::MinWidth->value => $this->minWidth,
            PropKey::MinHeight->value => $this->minHeight,
            PropKey::MaxWidth->value => $this->maxWidth,
            PropKey::MaxHeight->value => $this->maxHeight,
            PropKey::BackgroundColor->value => $this->backgroundColor,
            PropKey::TextColor->value => $this->textColor,
            PropKey::FontSize->value => $this->fontSize,
            PropKey::BorderRadius->value => $this->borderRadius,
            PropKey::BorderWidth->value => $this->borderWidth,
            PropKey::BorderColor->value => $this->borderColor,
            PropKey::Opacity->value => $this->opacity,
            PropKey::AlignItems->value => $this->alignItems?->value,
            PropKey::AlignSelf->value => $this->alignSelf?->value,
            PropKey::JustifyContent->value => $this->justifyContent?->value,
            PropKey::TextAlign->value => $this->textAlign?->value,
            PropKey::FontWeight->value => $this->fontWeight,
            PropKey::Elevation->value => $this->elevation,
            PropKey::TranslationX->value => $this->translationX,
            PropKey::TranslationY->value => $this->translationY,
            PropKey::ScaleX->value => $this->scaleX,
            PropKey::ScaleY->value => $this->scaleY,
            PropKey::Rotation->value => $this->rotation,
            PropKey::LetterSpacing->value => $this->letterSpacing,
            PropKey::LineHeight->value => $this->lineHeight,
            PropKey::ZIndex->value => $this->zIndex,
            PropKey::Overflow->value => $this->overflow?->value,
            PropKey::FlexDirection->value => $this->flexDirection?->value,
        ] as $key => $value) {
            if ($value !== null) {
                $properties[$key] = $value;
            }
        }

        return $properties;
    }
}
