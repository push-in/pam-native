<?php

declare(strict_types=1);

namespace Pam\Native\Animation;

use Stringable;

/**
 * Native tap effect: when the [trigger] gesture is recognised, [animation]
 * plays with no PHP round-trip before the first frame: on the first child of
 * the `nativeRef` anchor (moved to the touch point or the centre), or on the
 * anchor itself in place (`TapEffectOrigin::Anchor`).
 *
 * - `make()`: double tap, anchor centred on the touch point and tilted by a
 *   random angle up to [tilt] degrees (Reels/feed heart burst).
 * - `pop()`: single tap, anchor in place (like button squash + spring, RN
 *   `withSequence(withTiming(0.78), withSpring(1))` on press).
 *
 * `armed(false)` keeps the effect configured but silent. `unlessSelected()`
 * does the same from the Pressable's `accessibilitySelected` (RN
 * `accessibilityState.selected`), so a like toggle pops only while it is not
 * liked and the effect itself never changes between renders (one flag patch).
 */
final readonly class TapEffect implements Stringable
{
    private function __construct(
        private string $ref,
        private Animation $animation,
        private float $tilt,
        private TapEffectOrigin $origin = TapEffectOrigin::Touch,
        private TapEffectTrigger $trigger = TapEffectTrigger::DoubleTap,
        private bool $armed = true,
        private bool $unlessSelected = false,
    ) {
    }

    public static function make(string $nativeRef, ?Animation $animation = null, float $tilt = 15.0): self
    {
        return new self(
            Drag::ref($nativeRef),
            $animation ?? AnimationPreset::heartBurst(),
            max(0.0, min(180.0, $tilt)),
        );
    }

    /** Single-tap pop of the anchor in place (defaults to {@see AnimationPreset::likeBounce()}). */
    public static function pop(string $nativeRef, ?Animation $animation = null): self
    {
        return new self(
            Drag::ref($nativeRef),
            $animation ?? AnimationPreset::likeBounce(),
            0.0,
            TapEffectOrigin::Anchor,
            TapEffectTrigger::Tap,
        );
    }

    public function origin(TapEffectOrigin $origin): self
    {
        return new self($this->ref, $this->animation, $this->tilt, $origin, $this->trigger, $this->armed, $this->unlessSelected);
    }

    public function trigger(TapEffectTrigger $trigger): self
    {
        return new self($this->ref, $this->animation, $this->tilt, $this->origin, $trigger, $this->armed, $this->unlessSelected);
    }

    public function armed(bool $armed = true): self
    {
        return new self($this->ref, $this->animation, $this->tilt, $this->origin, $this->trigger, $armed, $this->unlessSelected);
    }

    /** Silent while the Pressable is `accessibilitySelected` (a toggle that is on). */
    public function unlessSelected(bool $enabled = true): self
    {
        return new self($this->ref, $this->animation, $this->tilt, $this->origin, $this->trigger, $this->armed, $enabled);
    }

    public function encode(): string
    {
        $header = 'ref='.$this->ref.';tilt='.Motion::number($this->tilt);
        if ($this->origin !== TapEffectOrigin::Touch) $header .= ';origin='.$this->origin->token();
        if ($this->trigger !== TapEffectTrigger::DoubleTap) $header .= ';trigger='.$this->trigger->token();
        if (!$this->armed) $header .= ';armed=0';
        if ($this->unlessSelected) $header .= ';when=unselected';

        return $header."\n".$this->animation->encode();
    }

    public function __toString(): string
    {
        return $this->encode();
    }
}
