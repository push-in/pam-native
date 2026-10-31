<?php

declare(strict_types=1);

namespace Pam\Native\Animation;

use InvalidArgumentException;
use Stringable;

/** Android IME progress: 0 hidden, 1 open; interpolation stays on the UI thread. */
final readonly class KeyboardProgress implements Stringable
{
    /** @param list<string> $tracks */
    private function __construct(private array $tracks = [])
    {
    }

    public static function make(): self
    {
        return new self();
    }

    /** Values are absolute opacity or translation dp, applied to the owning element. */
    public function drive(string $property, float $hidden, float $open): self
    {
        if (!in_array($property, ['opacity', 'translateX', 'translateY'], true)
            || count($this->tracks) >= 3) {
            throw new InvalidArgumentException('Keyboard progress supports up to three opacity/translation tracks.');
        }
        $prefix = $property.'|';
        foreach ($this->tracks as $track) {
            if (str_starts_with($track, $prefix)) {
                throw new InvalidArgumentException('Keyboard progress properties must be unique.');
            }
        }
        if ($property === 'opacity' && ($hidden < 0 || $hidden > 1 || $open < 0 || $open > 1)) {
            throw new InvalidArgumentException('Keyboard opacity must be between 0 and 1.');
        }

        return new self([...$this->tracks, $prefix.Motion::number($hidden).'|'.Motion::number($open)]);
    }

    public function encode(): string
    {
        if ($this->tracks === []) {
            throw new InvalidArgumentException('Keyboard progress requires at least one track.');
        }

        return implode("\n", $this->tracks);
    }

    public function __toString(): string
    {
        return $this->encode();
    }
}
