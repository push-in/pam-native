<?php

declare(strict_types=1);

namespace Pam\Native\Events;

use Closure;
use InvalidArgumentException;
use OverflowException;
use Pam\Native\Component;
use Throwable;

use function count;
use function is_object;

/**
 * In-process publish/subscribe channel. Events are named by a string or, for
 * typed events, by the event object's class:
 *
 *     $bus->on(MessageRead::class, fn (MessageRead $e) => ..., owner: $this);
 *     $bus->emit(new MessageRead($id));
 *     $bus->on('feed.refresh', fn () => ...);
 *     $bus->emit('feed.refresh');
 *
 * Listeners run synchronously in subscription order. A listener added during
 * an emit waits for the next emit; one cancelled during an emit is skipped.
 * If listeners throw, the remaining listeners still run and the first error
 * is rethrown afterwards. Passing a component as owner cancels the
 * subscription when it unmounts, so screens never leak listeners.
 */
final class EventBus
{
    /** @var array<string, self> */
    private static array $shared = [];

    /** @var array<string, array<int, array{listener: Closure, once: bool}>> */
    private array $listeners = [];
    private int $nextId = 1;
    private int $count = 0;

    public function __construct(private readonly int $maxListeners = 1_024)
    {
        if ($maxListeners < 1) {
            throw new InvalidArgumentException('An event bus needs room for at least one listener.');
        }
    }

    /**
     * A process-wide bus by name, for app-level channels shared by unrelated
     * screens. Runtime shutdown (hot reload) empties every shared bus.
     */
    public static function shared(string $name = 'app'): self
    {
        return self::$shared[$name] ??= new self();
    }

    /** @internal Called by Runtime::shutdown(). */
    public static function resetRuntime(): void
    {
        foreach (self::$shared as $bus) {
            $bus->clear();
        }
        self::$shared = [];
    }

    /** @param Closure $listener receives the payload, or nothing when it is null */
    public function on(string $event, Closure $listener, ?Component $owner = null): Subscription
    {
        return $this->add($event, $listener, false, $owner);
    }

    /** Like on(), but the listener is removed after its first delivery. */
    public function once(string $event, Closure $listener, ?Component $owner = null): Subscription
    {
        return $this->add($event, $listener, true, $owner);
    }

    /**
     * Delivers $payload (or the event object itself) to the event's
     * listeners and returns how many ran.
     */
    public function emit(string|object $event, mixed $payload = null): int
    {
        if (is_object($event)) {
            $payload = $event;
            $event = $event::class;
        }
        $listeners = $this->listeners[$event] ?? null;
        if ($listeners === null) {
            return 0;
        }
        $delivered = 0;
        $failure = null;
        foreach ($listeners as $id => $entry) {
            if (!isset($this->listeners[$event][$id])) {
                continue;
            }
            if ($entry['once']) {
                $this->remove($event, $id);
            }
            ++$delivered;
            try {
                $payload === null ? ($entry['listener'])() : ($entry['listener'])($payload);
            } catch (Throwable $error) {
                $failure ??= $error;
            }
        }
        if ($failure !== null) {
            throw $failure;
        }

        return $delivered;
    }

    public function has(string $event): bool
    {
        return isset($this->listeners[$event]);
    }

    /** Number of listeners for $event, or for every event when null. */
    public function listeners(?string $event = null): int
    {
        return $event === null ? $this->count : count($this->listeners[$event] ?? []);
    }

    /** Removes the listeners of $event, or every listener when null. */
    public function clear(?string $event = null): void
    {
        if ($event === null) {
            $this->listeners = [];
            $this->count = 0;

            return;
        }
        $this->count -= count($this->listeners[$event] ?? []);
        unset($this->listeners[$event]);
    }

    private function add(string $event, Closure $listener, bool $once, ?Component $owner): Subscription
    {
        if ($event === '' || strlen($event) > 256) {
            throw new InvalidArgumentException('Event names must contain 1 to 256 bytes.');
        }
        if ($this->count >= $this->maxListeners) {
            throw new OverflowException(
                "Event bus exceeded {$this->maxListeners} listeners; a subscription is probably leaking.",
            );
        }
        $id = $this->nextId++;
        $this->listeners[$event][$id] = ['listener' => $listener, 'once' => $once];
        ++$this->count;
        $bus = \WeakReference::create($this);

        return Subscription::from(static function () use ($bus, $event, $id): void {
            $bus->get()?->remove($event, $id);
        }, $owner);
    }

    private function remove(string $event, int $id): void
    {
        if (!isset($this->listeners[$event][$id])) {
            return;
        }
        unset($this->listeners[$event][$id]);
        --$this->count;
        if ($this->listeners[$event] === []) {
            unset($this->listeners[$event]);
        }
    }
}
