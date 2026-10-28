<?php

declare(strict_types=1);

namespace Pam\Native\Async;

use Closure;
use LogicException;
use Throwable;

/**
 * Minimal single-threaded promise. Callbacks run when the promise settles (the
 * native result callback for HTTP) or immediately when it already settled.
 * A callback returning another Thenable is adopted, so chains stay flat.
 */
final class Promise implements Thenable
{
    private PromiseState $state = PromiseState::Pending;
    private mixed $value = null;
    private ?Throwable $reason = null;
    /** @var list<Closure(): void> */
    private array $listeners = [];
    /** @var list<Closure(): void> */
    private array $cancellers = [];

    /** @param (Closure(Closure(mixed): void, Closure(Throwable): void): void)|null $executor */
    public function __construct(?Closure $executor = null)
    {
        if ($executor === null) return;
        try {
            $executor($this->resolve(...), $this->reject(...));
        } catch (Throwable $error) {
            $this->reject($error);
        }
    }

    public static function resolved(mixed $value = null): self
    {
        $promise = new self();
        $promise->resolve($value);

        return $promise;
    }

    public static function rejected(Throwable $reason): self
    {
        $promise = new self();
        $promise->reject($reason);

        return $promise;
    }

    /**
     * Settles when every input settles; rejects with the first rejection.
     *
     * @param array<array-key, mixed> $values Thenables or plain values.
     */
    public static function all(array $values): self
    {
        $result = new self();
        $pending = count($values);
        $collected = [];
        if ($pending === 0) {
            $result->resolve([]);
            return $result;
        }
        foreach ($values as $key => $value) {
            $collected[$key] = null;
            self::from($value)->then(
                static function (mixed $settled) use (&$collected, &$pending, $key, $result): void {
                    $collected[$key] = $settled;
                    if (--$pending === 0 && $result->state === PromiseState::Pending) $result->resolve($collected);
                },
                static function (Throwable $reason) use ($result): void {
                    if ($result->state === PromiseState::Pending) $result->reject($reason);
                },
            );
        }

        return $result;
    }

    public static function from(mixed $value): Thenable
    {
        return $value instanceof Thenable ? $value : self::resolved($value);
    }

    public function resolve(mixed $value = null): void
    {
        if ($this->state !== PromiseState::Pending) {
            if ($this->state === PromiseState::Cancelled) return;
            throw new LogicException('A promise can only be settled once.');
        }
        if ($value === $this) {
            $this->reject(new LogicException('A promise cannot resolve to itself.'));
            return;
        }
        if ($value instanceof Thenable) {
            $this->cancellers[] = $value->cancel(...);
            $value->then(
                function (mixed $inner): void { $this->resolve($inner); },
                function (Throwable $reason): void { $this->reject($reason); },
            );
            return;
        }
        $this->settle(PromiseState::Fulfilled, $value, null);
    }

    public function reject(Throwable $reason): void
    {
        if ($this->state !== PromiseState::Pending) {
            if ($this->state === PromiseState::Cancelled) return;
            throw new LogicException('A promise can only be settled once.');
        }
        $this->settle(PromiseState::Rejected, null, $reason);
    }

    public function then(?Closure $onFulfilled = null, ?Closure $onRejected = null): Thenable
    {
        $next = new self();
        $next->cancellers[] = $this->cancel(...);
        $this->listen(function () use ($next, $onFulfilled, $onRejected): void {
            if ($this->state === PromiseState::Cancelled) {
                $next->cancel();
                return;
            }
            try {
                if ($this->state === PromiseState::Fulfilled) {
                    $next->resolve($onFulfilled === null ? $this->value : $onFulfilled($this->value));
                    return;
                }
                $reason = $this->reason ?? new LogicException('Rejected promise without a reason.');
                if ($onRejected === null) {
                    $next->reject($reason);
                    return;
                }
                $next->resolve($onRejected($reason));
            } catch (Throwable $error) {
                if ($next->state === PromiseState::Pending) $next->reject($error);
            }
        });

        return $next;
    }

    public function catch(Closure $onRejected): Thenable
    {
        return $this->then(null, $onRejected);
    }

    public function finally(Closure $onSettled): Thenable
    {
        return $this->then(
            static function (mixed $value) use ($onSettled): mixed {
                $onSettled();
                return $value;
            },
            static function (Throwable $reason) use ($onSettled): never {
                $onSettled();
                throw $reason;
            },
        );
    }

    public function cancel(): void
    {
        if ($this->state !== PromiseState::Pending) return;
        $this->state = PromiseState::Cancelled;
        $cancellers = $this->cancellers;
        $this->cancellers = [];
        foreach ($cancellers as $cancel) $cancel();
        $this->flush();
    }

    /** @internal Registers work to stop when this promise is cancelled. */
    public function onCancel(Closure $cancel): void
    {
        if ($this->state === PromiseState::Cancelled) {
            $cancel();
            return;
        }
        if ($this->state === PromiseState::Pending) $this->cancellers[] = $cancel;
    }

    public function state(): PromiseState
    {
        return $this->state;
    }

    /** The fulfilled value; throws the reason when rejected or when still pending. */
    public function value(): mixed
    {
        return match ($this->state) {
            PromiseState::Fulfilled => $this->value,
            PromiseState::Rejected => throw $this->reason ?? new LogicException('Rejected promise without a reason.'),
            PromiseState::Pending => throw new LogicException('The promise has not settled yet; use then().'),
            PromiseState::Cancelled => throw new LogicException('The promise was cancelled.'),
        };
    }

    private function settle(PromiseState $state, mixed $value, ?Throwable $reason): void
    {
        $this->state = $state;
        $this->value = $value;
        $this->reason = $reason;
        $this->cancellers = [];
        $this->flush();
    }

    private function listen(Closure $listener): void
    {
        if ($this->state === PromiseState::Pending) {
            $this->listeners[] = $listener;
            return;
        }
        $listener();
    }

    private function flush(): void
    {
        $listeners = $this->listeners;
        $this->listeners = [];
        foreach ($listeners as $listener) $listener();
    }
}
