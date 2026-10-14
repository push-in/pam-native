<?php

declare(strict_types=1);

namespace Pam\Native\Events;

use Closure;
use Pam\Native\Component;
use Pam\Native\Signals\Signal;

/**
 * A value that notifies listeners when it changes (compared with ===).
 * Reading get() inside render() also re-renders the component on change,
 * because the value is backed by a Signal.
 *
 *     $unread = new Observable(0);
 *     $unread->on(fn (int $now, int $before) => $this->badge = $now, owner: $this);
 *     $unread->update(fn (int $n) => $n + 1);
 *
 * @template T
 */
final class Observable
{
    /** @var Signal<T> */
    private Signal $signal;
    private EventBus $bus;

    /** @param T $value */
    public function __construct(private mixed $value, int $maxListeners = 1_024)
    {
        $this->signal = new Signal($value);
        $this->bus = new EventBus($maxListeners);
    }

    /** @return T tracked by the rendering component */
    public function get(): mixed
    {
        return $this->signal->get();
    }

    /** @return T without registering a render dependency */
    public function peek(): mixed
    {
        return $this->value;
    }

    /** @param T $value */
    public function set(mixed $value): void
    {
        $previous = $this->value;
        if ($value === $previous) {
            return;
        }
        $this->value = $value;
        $this->signal->set($value);
        $this->bus->emit('change', [$value, $previous]);
    }

    /** @param Closure(T): T $update */
    public function update(Closure $update): void
    {
        $this->set($update($this->value));
    }

    /**
     * @param Closure(T, T): void $listener receives the new and previous value
     * @param bool $immediate also call the listener now with the current value
     */
    public function on(Closure $listener, ?Component $owner = null, bool $immediate = false): Subscription
    {
        $subscription = $this->bus->on(
            'change',
            static function (array $change) use ($listener): void {
                $listener($change[0], $change[1]);
            },
            $owner,
        );
        if ($immediate) {
            $current = $this->value;
            $listener($current, $current);
        }

        return $subscription;
    }

    public function listeners(): int
    {
        return $this->bus->listeners();
    }
}
