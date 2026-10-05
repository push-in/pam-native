<?php

declare(strict_types=1);

namespace Pam\Native\UI;

use InvalidArgumentException;
use Pam\Native\Element;
use Pam\Native\EventKind;
use Pam\Native\Internal\BinaryValue;
use Pam\Native\NodeKind;
use Pam\Native\PropKey;
use Pam\Native\TextBreakStrategy;
use Pam\Native\TextDataDetectorType;
use Pam\Native\TextEllipsizeMode;
use Pam\Native\TextHyphenationFrequency;

final class Text extends Element
{
    /** Style properties a nested Text/Span may override, in wire field order. */
    private const SPAN_FIELDS = [
        PropKey::FontSize->value => 2,
        PropKey::FontWeight->value => 3,
        PropKey::FontStyle->value => 4,
        PropKey::TextColor->value => 5,
        PropKey::BackgroundColor->value => 6,
        PropKey::TextDecoration->value => 7,
        PropKey::LetterSpacing->value => 8,
        PropKey::FontFamily->value => 9,
        PropKey::TextTransform->value => 11,
    ];

    private const MAX_SPANS = 4_096;

    /** @var list<string|Text>|null */
    private ?array $richParts = null;

    public static function make(string $text): self
    {
        return (new self(NodeKind::Text))->withProperty(PropKey::Text, $text);
    }

    /**
     * Inline rich text: one paragraph whose runs may change weight, size,
     * color, decoration, background or font and may be individually
     * pressable (links, mentions), like nested `<Text>` in React Native.
     * Nested runs inherit from their ancestors; the outer node keeps all
     * paragraph-level styling (line height, alignment, number of lines).
     */
    public static function rich(string|self ...$parts): self
    {
        $text = '';
        $length = 0;
        /** @var list<array{int, int, array<int|string, mixed>}|null> $spans */
        $spans = [];
        /** @var list<\Closure> $handlers */
        $handlers = [];
        $append = static function (string|self $part, array $inherited) use (&$append, &$text, &$length, &$spans, &$handlers): void {
            if (is_string($part)) {
                $text .= $part;
                $length += (int) preg_match_all('/./su', $part);
                return;
            }
            if ($part->kind() !== NodeKind::Text) {
                throw new InvalidArgumentException('Rich text can only contain strings and Text/Span runs.');
            }
            $merged = $inherited;
            foreach ($part->properties() as $key => $value) {
                if (isset(self::SPAN_FIELDS[$key]) && !$value instanceof BinaryValue) {
                    $merged[self::SPAN_FIELDS[$key]] = $value;
                }
            }
            $press = $part->events()[EventKind::Press->value] ?? null;
            if ($press !== null) {
                $merged[10] = count($handlers);
                $handlers[] = $press;
            }
            $start = $length;
            $slot = count($spans);
            $spans[] = null;
            if ($part->richParts !== null) {
                foreach ($part->richParts as $child) {
                    $append($child, $merged);
                }
            } else {
                $content = $part->properties()[PropKey::Text->value] ?? '';
                $append(is_string($content) ? $content : '', $merged);
            }
            if ($merged !== [] && $length > $start) {
                $spans[$slot] = [$start, $length, $merged];
            }
            if (count($spans) > self::MAX_SPANS) {
                throw new InvalidArgumentException('Rich text supports at most 4096 runs.');
            }
        };
        foreach ($parts as $part) {
            $append($part, []);
        }
        $records = [];
        foreach ($spans as $span) {
            if ($span === null) {
                continue;
            }
            [$start, $end, $fields] = $span;
            $row = array_fill(0, 12, '');
            $row[0] = (string) $start;
            $row[1] = (string) $end;
            foreach ($fields as $index => $value) {
                $row[$index] = self::spanField($value, (int) $index);
            }
            $records[] = rtrim(implode(',', $row), ',');
        }
        $element = self::make($text);
        $element->richParts = array_values($parts);
        if ($records !== []) {
            $element = $element->withProperty(PropKey::TextSpans, implode(';', $records));
        }
        if ($handlers !== []) {
            $element = $element->on(EventKind::SpanPress, static function (mixed $payload = null) use ($handlers): void {
                $handler = $handlers[(int) (is_scalar($payload) ? $payload : -1)] ?? null;
                $handler?->__invoke('');
            });
        }

        return $element;
    }

    private static function spanField(mixed $value, int $index): string
    {
        if ($index === 9) {
            $family = is_string($value) ? $value : '';
            return preg_match('/^[A-Za-z0-9 _.\/:-]{1,256}$/D', $family) === 1 ? $family : '';
        }
        if (is_bool($value)) {
            return $value ? '1' : '0';
        }
        if (is_int($value)) {
            return (string) $value;
        }
        if (is_float($value) && is_finite($value)) {
            $formatted = rtrim(rtrim(sprintf('%.4F', $value), '0'), '.');
            return $formatted === '-0' ? '0' : $formatted;
        }
        if (is_string($value) && is_numeric($value)) {
            return self::spanField((float) $value, $index);
        }

        return '';
    }

    public function includeFontPadding(bool $include = true): self
    {
        return $this->withProperty(PropKey::IncludeFontPadding, $include);
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
            $multiplier <= 0.0 ? 0.0 : max(1.0, $multiplier),
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
