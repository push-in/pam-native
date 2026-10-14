<?php

declare(strict_types=1);

namespace Pam\Native\Scheduling;

use Closure;
use InvalidArgumentException;
use Pam\Native\Disposable;
use Pam\Native\System\Timers;

/**
 * Runs an action once calls stop for $delayMs, with the arguments of the
 * last call. Typical use: search-as-you-type, draft autosave, resize.
 *
 *     $search = new Debouncer(300, fn (string $q) => $this->search($q));
 *     $search->call($text);   // only the last text within 300 ms is searched
 *
 * $maxWaitMs bounds how long a continuous stream of calls may postpone the
 * action. Components should create it with `$this->timers()->debounce()`
 * so it is cancelled on unmount.
 */
final class Debouncer implements Disposable
{
    /** @var (Closure(): void)|null cancels the armed timer */
    private ?Closure $timer = null;
    /** @var Closure(int, Closure(): void): (Closure(): void) */
    private Closure $schedule;
    /** @var list<mixed>|null */
    private ?array $arguments = null;
    private ?float $firstCallMs = null;
    private bool $disposed = false;

    /**
     * @param Closure(mixed...): void $action
     * @param (Closure(int, Closure(): void): (Closure(): void))|null $schedule
     *        arms a timer and returns its canceller; defaults to native Timers (test seam)
     */
    public function __construct(
        public readonly int $delayMs,
        private readonly Closure $action,
        public readonly ?int $maxWaitMs = null,
        ?Closure $schedule = null,
    ) {
        if ($delayMs < 0 || ($maxWaitMs !== null && $maxWaitMs < $delayMs)) {
            throw new InvalidArgumentException('Debounce delay must be >= 0 and maxWait must not be shorter than it.');
        }
        $this->schedule = $schedule ?? self::nativeSchedule(...);
    }

    /**
     * @param Closure(): void $fire
     * @return Closure(): void
     */
    public static function nativeSchedule(int $milliseconds, Closure $fire): Closure
    {
        $handle = Timers::timeout($milliseconds, $fire);

        return static fn () => $handle->cancel();
    }

    public function call(mixed ...$arguments): void
    {
        if ($this->disposed) {
            return;
        }
        $this->arguments = array_values($arguments);
        $now = hrtime(true) / 1_000_000;
        $this->firstCallMs ??= $now;
        if ($this->maxWaitMs !== null && $now - $this->firstCallMs >= $this->maxWaitMs) {
            $this->flush();

            return;
        }
        $cancel = $this->timer;
        $this->timer = null;
        $cancel?->__invoke();
        $this->timer = ($this->schedule)($this->delayMs, function (): void {
            $this->timer = null;
            $this->flush();
        });
    }

    /** Runs a pending call now. Returns false when nothing was pending. */
    public function flush(): bool
    {
        $arguments = $this->arguments;
        $this->reset();
        if ($arguments === null || $this->disposed) {
            return false;
        }
        ($this->action)(...$arguments);

        return true;
    }

    /** Drops a pending call. */
    public function cancel(): void
    {
        $this->reset();
    }

    public function pending(): bool
    {
        return $this->arguments !== null;
    }

    public function dispose(): void
    {
        $this->reset();
        $this->disposed = true;
    }

    public function disposed(): bool
    {
        return $this->disposed;
    }

    private function reset(): void
    {
        $cancel = $this->timer;
        $this->timer = null;
        $cancel?->__invoke();
        $this->arguments = null;
        $this->firstCallMs = null;
    }
}
