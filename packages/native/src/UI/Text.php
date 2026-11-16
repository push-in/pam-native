<?php

declare(strict_types=1);

namespace Pam\Native\UI;

use InvalidArgumentException;
use Pam\Native\ClockFormat;
use Pam\Native\Element;
use Pam\Native\EventKind;
use Pam\Native\Internal\BinaryValue;
use Pam\Native\NodeKind;
use Pam\Native\PropKey;
use Pam\Native\TextBreakStrategy;
use Pam\Native\TextDataDetectorType;
use Pam\Native\TextEllipsizeMode;
use Pam\Native\TextHyphenationFrequency;

use function count;
use function is_bool;
use function is_float;
use function is_int;
use function is_scalar;
use function is_string;
use function sprintf;

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
     * Shows the position of the player whose `nativeRef` is [ref], updated
     * natively while it plays (no PHP tick, nothing while paused or covered).
     * The text's own content is the placeholder until the player is ready;
     * give it a stable width (e.g. tabular digits) so updates never relayout.
     */
    public function mediaClock(
        string $ref,
        \Pam\Native\MediaClockFormat $format = \Pam\Native\MediaClockFormat::Elapsed,
    ): self {
        return $this
            ->withProperty(PropKey::MediaClock, \Pam\Native\Animation\Drag::ref($ref))
            ->withProperty(PropKey::MediaClockFormat, $format->value);
    }

    /**
     * A clock counting up from [startedAt] (epoch ms), drawn natively: the
     * renderer updates the text once per second while it is on screen, with
     * no PHP render, and does nothing while it is hidden, covered or in the
     * background. [offsetMs] is time already counted before [startedAt] (a
     * resumed stopwatch); with [running] false, or no [startedAt], the clock
     * stands at [offsetMs]. PHP stays the source of truth: change any value
     * and the clock follows. The text's own content is the placeholder the
     * layout measures; give it a stable width (tabular digits).
     */
    public function clock(
        ?int $startedAt,
        ClockFormat $format = ClockFormat::MinutesSeconds,
        int $offsetMs = 0,
        bool $running = true,
    ): self {
        return $this
            ->withoutProperty(PropKey::ClockEndsAt)
            ->withoutProperty(PropKey::ClockDurationMs)
            ->withClockSource($startedAt, $offsetMs, $running, $format);
    }

    /**
     * A countdown of [durationMs] counted from [startedAt] (epoch ms), drawn
     * natively like {@see clock()}: it shows the time left (rounded up) and
     * stops at zero. [offsetMs] and [running] pause it the same way; with no
     * [startedAt] it stands at the full duration.
     */
    public function countdown(
        int $durationMs,
        ?int $startedAt,
        ClockFormat $format = ClockFormat::Seconds,
        int $offsetMs = 0,
        bool $running = true,
    ): self {
        return $this
            ->withoutProperty(PropKey::ClockEndsAt)
            ->withProperty(PropKey::ClockDurationMs, self::nonNegative($durationMs, 'durationMs'))
            ->withClockSource($startedAt, $offsetMs, $running, $format);
    }

    /** A native countdown to the instant [endsAt] (epoch ms), stopping at zero. */
    public function countdownTo(int $endsAt, ClockFormat $format = ClockFormat::Seconds): self
    {
        return $this
            ->withoutProperty(PropKey::ClockStartedAt)
            ->withoutProperty(PropKey::ClockDurationMs)
            ->withoutProperty(PropKey::ClockOffsetMs)
            ->withoutProperty(PropKey::ClockRunning)
            ->withProperty(PropKey::ClockEndsAt, self::nonNegative($endsAt, 'endsAt'))
            ->withProperty(PropKey::ClockFormat, $format->value);
    }

    /**
     * Typewriter: the renderer reveals the text one character (grapheme)
     * every [msPerChar], keeping the full text's layout (hidden characters
     * hold their place). Changing [key] starts it over; changing the text
     * does not. Runs only while on screen; with reduced motion the whole
     * text shows at once. Screen readers always read the whole text.
     */
    public function reveal(int $msPerChar = 50, string|int|null $key = null): self
    {
        if ($msPerChar < 1) {
            throw new InvalidArgumentException('msPerChar must be at least 1.');
        }

        return $this
            ->withoutProperty(PropKey::RevealDurationMs)
            ->withProperty(PropKey::RevealCharMs, $msPerChar)
            ->withRevealKey($key);
    }

    /** Like {@see reveal()}, spreading the whole text over [durationMs]. */
    public function revealOver(int $durationMs, string|int|null $key = null): self
    {
        if ($durationMs < 1) {
            throw new InvalidArgumentException('durationMs must be at least 1.');
        }

        return $this
            ->withoutProperty(PropKey::RevealCharMs)
            ->withProperty(PropKey::RevealDurationMs, $durationMs)
            ->withRevealKey($key);
    }

    private function withClockSource(?int $startedAt, int $offsetMs, bool $running, ClockFormat $format): self
    {
        $element = $startedAt === null
            ? $this->withoutProperty(PropKey::ClockStartedAt)
            : $this->withProperty(PropKey::ClockStartedAt, self::nonNegative($startedAt, 'startedAt'));
        // The offset is always sent: it is also what turns a clock with no
        // start yet (standing at zero) on.
        $element = $element->withProperty(PropKey::ClockOffsetMs, self::nonNegative($offsetMs, 'offsetMs'));
        $element = $running && $startedAt !== null
            ? $element->withoutProperty(PropKey::ClockRunning)
            : $element->withProperty(PropKey::ClockRunning, $running && $startedAt !== null);

        return $element->withProperty(PropKey::ClockFormat, $format->value);
    }

    private function withRevealKey(string|int|null $key): self
    {
        $key = \Pam\Native\Internal\NativeTime::key($key);

        return $key === null
            ? $this->withoutProperty(PropKey::RevealKey)
            : $this->withProperty(PropKey::RevealKey, $key);
    }

    private static function nonNegative(int $value, string $label): int
    {
        if ($value < 0) {
            throw new InvalidArgumentException("{$label} cannot be negative.");
        }

        return $value;
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
        foreach ($parts as $part) {
            self::appendRun($part, [], $text, $length, $spans, $handlers);
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

    /**
     * Appends one rich text run (a named method instead of a recursive
     * closure, which would be a reference cycle left to the cycle collector).
     *
     * @param array<int|string, mixed> $inherited
     * @param array<int, array{int, int, array<int|string, mixed>}|null> $spans
     * @param list<\Closure> $handlers
     */
    private static function appendRun(
        string|self $part,
        array $inherited,
        string &$text,
        int &$length,
        array &$spans,
        array &$handlers,
    ): void {
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
                self::appendRun($child, $merged, $text, $length, $spans, $handlers);
            }
        } else {
            $content = $part->properties()[PropKey::Text->value] ?? '';
            self::appendRun(is_string($content) ? $content : '', $merged, $text, $length, $spans, $handlers);
        }
        if ($merged !== [] && $length > $start) {
            $spans[$slot] = [$start, $length, $merged];
        }
        if (count($spans) > self::MAX_SPANS) {
            throw new InvalidArgumentException('Rich text supports at most 4096 runs.');
        }
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
