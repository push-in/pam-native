<?php

declare(strict_types=1);

namespace Pam\Native\Animation;

use InvalidArgumentException;
use Stringable;

use function count;
use function is_int;
use function is_string;

/**
 * Immutable, declarative UI-thread animation (Reanimated `withTiming`,
 * `withSpring`, `withSequence`, `withDelay`, `withRepeat`).
 *
 * An animation is a list of phases. Inside a phase every property runs its
 * own step list in parallel; `then()` starts the next phase after every
 * track of the previous one settled. Attach it with `:motion="$animation"`
 * (any element) or `<Animated :animation="$animation">`. The native runtime
 * replays it only when its identity changes: the encoded program plus the
 * optional `key()`, so re-rendering an unchanged animation never restarts it.
 *
 * ```php
 * $this->likePop = Animation::spring(['scale' => 1.2], stiffness: 400, damping: 8)
 *     ->then(Animation::spring(['scale' => 1]))
 *     ->key($this->likeCount);
 * ```
 */
final readonly class Animation implements Stringable
{
    private const int MAX_PHASES = 32;
    private const int MAX_STEPS = 64;

    /**
     * @param list<array<string, list<string>>|int> $phases property tracks, or an int wait phase
     */
    private function __construct(
        private array $phases,
        private int $iterations = 1,
        private string $key = '',
    ) {
        if ($phases === [] || count($phases) > self::MAX_PHASES) {
            throw new InvalidArgumentException('Animations require between 1 and 32 phases.');
        }
    }

    /**
     * Animates every property in [to] with a duration and easing.
     *
     * @param array<string, float|int|string> $to property => dp, degrees, factor or "<n>%"
     */
    public static function timing(
        array $to,
        int $duration = 300,
        Easing|string $easing = Easing::EaseInOut,
        int $delay = 0,
    ): self {
        $token = Easing::token($easing);
        Motion::milliseconds($duration, 'Animation duration');
        Motion::milliseconds($delay, 'Animation delay');

        return self::single($to, static fn (string $value): string => "timing({$value},{$duration},{$token},{$delay})");
    }

    /**
     * Physical spring towards every property in [to].
     *
     * @param array<string, float|int|string> $to
     */
    public static function spring(
        array $to,
        float $stiffness = 100.0,
        float $damping = 10.0,
        float $mass = 1.0,
        float $velocity = 0.0,
        int $delay = 0,
    ): self {
        $spring = new Spring($stiffness, $damping, $mass);
        Motion::milliseconds($delay, 'Animation delay');
        $tail = Motion::number($spring->stiffness).','.Motion::number($spring->damping).','
            .Motion::number($spring->mass).','.Motion::number($velocity).','.$delay;

        return self::single($to, static fn (string $value): string => "spring({$value},{$tail})");
    }

    /**
     * Jumps instantly (Reanimated `withTiming(v, {duration: 0})`).
     *
     * @param array<string, float|int|string> $values
     */
    public static function set(array $values): self
    {
        return self::single($values, static fn (string $value): string => "set({$value})");
    }

    /** A pause between phases (inside `sequence()` it pauses every listed track). */
    public static function wait(int $milliseconds): self
    {
        return new self([Motion::milliseconds($milliseconds, 'Animation wait')]);
    }

    /**
     * Chains steps per property without a barrier, like wrapping each shared
     * value in `withSequence`. Every part must be a single phase.
     */
    public static function sequence(self ...$parts): self
    {
        if ($parts === []) {
            throw new InvalidArgumentException('Animation sequences require at least one part.');
        }
        $tracks = [];
        $pendingWait = 0;
        foreach ($parts as $part) {
            if (count($part->phases) !== 1) {
                throw new InvalidArgumentException('Animation::sequence() parts must be single-phase animations.');
            }
            $phase = $part->phases[0];
            if (is_int($phase)) {
                foreach ($tracks as $property => $steps) {
                    $tracks[$property][] = "wait({$phase})";
                }
                $pendingWait += $phase;
                continue;
            }
            foreach ($phase as $property => $steps) {
                if (!isset($tracks[$property]) && $pendingWait > 0) {
                    $tracks[$property] = ["wait({$pendingWait})"];
                }
                $tracks[$property] = [...($tracks[$property] ?? []), ...$steps];
            }
        }
        if ($tracks === []) {
            return new self([$pendingWait]);
        }

        return new self([self::bounded($tracks)]);
    }

    /** Runs [next] after every track of this animation settled. */
    public function then(self $next): self
    {
        return new self([...$this->phases, ...$next->phases], $this->iterations, $this->key);
    }

    /** Runs [other] in parallel, phase by phase (property steps are appended). */
    public function with(self $other): self
    {
        $phases = [];
        $count = max(count($this->phases), count($other->phases));
        for ($index = 0; $index < $count; $index++) {
            $left = $this->phases[$index] ?? null;
            $right = $other->phases[$index] ?? null;
            if ($left === null || $right === null) {
                $phases[] = $left ?? $right;
                continue;
            }
            if (is_int($left) || is_int($right)) {
                $phases[] = is_int($left) && is_int($right) ? max($left, $right) : (is_int($left) ? $right : $left);
                continue;
            }
            $merged = $left;
            foreach ($right as $property => $steps) {
                $merged[$property] = [...($merged[$property] ?? []), ...$steps];
            }
            $phases[] = self::bounded($merged);
        }

        return new self($phases, $this->iterations, $this->key);
    }

    /** Delays the first phase (`withDelay`). */
    public function delay(int $milliseconds): self
    {
        Motion::milliseconds($milliseconds, 'Animation delay');
        if ($milliseconds === 0) {
            return $this;
        }

        return new self([$milliseconds, ...$this->phases], $this->iterations, $this->key);
    }

    /** `withRepeat`: -1 repeats forever. */
    public function repeat(int $times = -1): self
    {
        if ($times < -1 || $times === 0 || $times > 10_000) {
            throw new InvalidArgumentException('Animation repeat must be -1 or between 1 and 10000.');
        }

        return new self($this->phases, $times, $this->key);
    }

    /** Replay identity: change it (for example a counter) to play the same animation again. */
    public function key(int|string $key): self
    {
        return new self($this->phases, $this->iterations, (string) $key);
    }

    public function iterations(): int
    {
        return $this->iterations;
    }

    /** Stable program identity; the native runtime replays when it changes. */
    public function id(): int
    {
        return crc32($this->body().'#'.$this->key) & 0x7fffffff;
    }

    /** The `pam-motion` text program consumed by the native runtime. */
    public function encode(): string
    {
        return 'pam-motion 1 id='.$this->id().' iterations='.$this->iterations."\n".$this->body();
    }

    public function __toString(): string
    {
        return $this->encode();
    }

    private function body(): string
    {
        $lines = [];
        $phaseIndex = 0;
        $pendingWait = 0;
        $last = null;
        foreach ($this->phases as $phase) {
            if (is_int($phase)) {
                $pendingWait += $phase;
                continue;
            }
            foreach ($phase as $property => $steps) {
                if ($pendingWait > 0) {
                    array_unshift($steps, "wait({$pendingWait})");
                }
                $lines[] = $phaseIndex.' '.$property.' '.implode(' ', $steps);
            }
            $last = $phase;
            $pendingWait = 0;
            $phaseIndex++;
        }
        if ($last === null) {
            // A pure wait still needs a track to measure time against.
            $lines[] = '0 opacity wait('.$pendingWait.')';
        } elseif ($pendingWait > 0) {
            $property = array_key_first($last);
            $lines[] = $phaseIndex.' '.$property.' wait('.$pendingWait.')';
        }

        return implode("\n", $lines);
    }

    /**
     * @param array<string, float|int|string> $values
     * @param callable(string): string $step
     */
    private static function single(array $values, callable $step): self
    {
        if ($values === []) {
            throw new InvalidArgumentException('Animations require at least one property.');
        }
        $tracks = [];
        foreach ($values as $property => $value) {
            if (!is_string($property)) {
                throw new InvalidArgumentException('Animated properties must be keyed by name.');
            }
            $tracks[Motion::property($property)] = [$step(Motion::value($value))];
        }

        return new self([$tracks]);
    }

    /**
     * @param array<string, list<string>> $tracks
     * @return array<string, list<string>>
     */
    private static function bounded(array $tracks): array
    {
        foreach ($tracks as $steps) {
            if (count($steps) > self::MAX_STEPS) {
                throw new InvalidArgumentException('Animation tracks are limited to 64 steps.');
            }
        }

        return $tracks;
    }
}
