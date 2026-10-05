<?php

declare(strict_types=1);

namespace Pam\Native\Animation;

use Stringable;

/**
 * Native double-tap effect: on the second tap the `nativeRef` anchor is
 * centred on the touch point (optionally tilted by a random angle up to
 * [tilt] degrees) and [animation] plays on its first child, with no PHP
 * round-trip before the first frame (Reels heart burst).
 */
final readonly class TapEffect implements Stringable
{
    private function __construct(
        private string $ref,
        private Animation $animation,
        private float $tilt,
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

    public function encode(): string
    {
        return 'ref='.$this->ref.';tilt='.Motion::number($this->tilt)."\n".$this->animation->encode();
    }

    public function __toString(): string
    {
        return $this->encode();
    }
}
