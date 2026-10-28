<?php

declare(strict_types=1);

namespace Pam\Native\Composables;

use Closure;
use Pam\Native\Signals\EffectScope;
use Pam\Native\Signals\Signal;

/**
 * One platform subscription shared by every composable of a kind.
 *
 * The first consumer starts it (Appearance::onChange, Keyboard::onChange,
 * DeviceStatus::watch...), the last one to be disposed stops it, so ten
 * screens calling useKeyboard() cost one listener and an app with none
 * mounted costs nothing. Values arrive by push, never by polling, and are
 * kept in a signal that templates, computed() and watch() can track.
 *
 * @template T
 * @internal
 */
final class SharedSource
{
    /** @var array<string, self<mixed>> */
    private static array $sources = [];

    /** @var Signal<T> */
    public readonly Signal $signal;
    private int $references = 0;
    private ?Closure $stop = null;

    /**
     * @param Closure(): T $read current value, read when the source starts
     * @param Closure(Closure(T): void): (Closure(): void) $start subscribes; returns the unsubscribe
     */
    private function __construct(
        public readonly string $name,
        private readonly Closure $read,
        private readonly Closure $start,
    ) {
        $this->signal = new Signal($read());
    }

    /**
     * @template V
     * @param Closure(): V $read
     * @param Closure(Closure(V): void): (Closure(): void) $start
     * @return self<V>
     */
    public static function named(string $name, Closure $read, Closure $start): self
    {
        /** @var self<V> */
        return self::$sources[$name] ??= new self($name, $read, $start);
    }

    /**
     * Counts one consumer for the current effect scope: released with the
     * scope, or kept for the application's lifetime when there is none.
     */
    public function acquireForScope(): void
    {
        $this->acquire();
        $released = false;
        EffectScope::current()?->onDispose(function () use (&$released): void {
            if (!$released) {
                $released = true;
                $this->release();
            }
        });
    }

    public function acquire(): void
    {
        if ($this->references++ > 0) {
            return;
        }
        $this->signal->set(($this->read)());
        $signal = $this->signal;
        $this->stop = ($this->start)(static function (mixed $value) use ($signal): void {
            $signal->set($value);
        });
    }

    public function release(): void
    {
        if ($this->references === 0) {
            return;
        }
        if (--$this->references > 0) {
            return;
        }
        $stop = $this->stop;
        $this->stop = null;
        $stop?->__invoke();
    }

    public function references(): int
    {
        return $this->references;
    }

    /** @return array<string, int> consumers per running source, for leak diagnostics and tests */
    public static function active(): array
    {
        $active = [];
        foreach (self::$sources as $name => $source) {
            if ($source->references > 0) {
                $active[$name] = $source->references;
            }
        }

        return $active;
    }

    /** @internal Stops every source (tests and runtime resets). */
    public static function reset(): void
    {
        foreach (self::$sources as $source) {
            $stop = $source->stop;
            $source->stop = null;
            $source->references = 0;
            $stop?->__invoke();
        }
        self::$sources = [];
    }
}
