<?php

declare(strict_types=1);

namespace Pam\Native\Dom;

use InvalidArgumentException;
use Pam\Native\Internal\BinaryValue;
use Pam\Native\PropKey;

final class StyleDeclaration
{
    /** @var array<string, PropKey> */
    private const PROPERTIES = [
        'width' => PropKey::Width,
        'height' => PropKey::Height,
        'opacity' => PropKey::Opacity,
        'background-color' => PropKey::BackgroundColor,
        'color' => PropKey::TextColor,
        'font-size' => PropKey::FontSize,
        'font-weight' => PropKey::FontWeight,
        'border-radius' => PropKey::BorderRadius,
        'border-width' => PropKey::BorderWidth,
        'border-color' => PropKey::BorderColor,
        'padding' => PropKey::Padding,
        'margin' => PropKey::Margin,
        'gap' => PropKey::Gap,
        'translate-x' => PropKey::TranslationX,
        'translate-y' => PropKey::TranslationY,
        'scale-x' => PropKey::ScaleX,
        'scale-y' => PropKey::ScaleY,
        'rotation' => PropKey::Rotation,
        'z-index' => PropKey::ZIndex,
    ];

    public function __construct(private readonly Element $element)
    {
    }

    public function set(
        string|PropKey $property,
        string|int|float|bool|BinaryValue $value,
    ): Element {
        $key = self::resolve($property);
        $this->validate($key, $value);

        return $this->element->prop($key, $value);
    }

    public function remove(string|PropKey $property): Element
    {
        return $this->element->removeProp(self::resolve($property));
    }

    public function get(string|PropKey $property): string|int|float|bool|BinaryValue|null
    {
        return $this->element->property(self::resolve($property));
    }

    public static function resolve(string|PropKey $property): PropKey
    {
        if ($property instanceof PropKey) {
            return $property;
        }
        $key = self::PROPERTIES[strtolower(trim($property))] ?? null;
        if ($key === null) {
            throw new InvalidArgumentException("Unknown or non-styleable DOM property {$property}; use a typed PropKey for advanced properties.");
        }

        return $key;
    }

    private function validate(PropKey $key, string|int|float|bool|BinaryValue $value): void
    {
        if (in_array($key, [PropKey::Opacity, PropKey::ScaleX, PropKey::ScaleY], true)
            && is_float($value) && !is_finite($value)) {
            throw new InvalidArgumentException('DOM style values must be finite.');
        }
        if ($key === PropKey::Opacity && is_numeric($value) && ((float) $value < 0.0 || (float) $value > 1.0)) {
            throw new InvalidArgumentException('DOM opacity must be between zero and one.');
        }
    }
}
