<?php

declare(strict_types=1);

namespace Pam\Native\Support;

use Closure;
use InvalidArgumentException;
use Pam\Native\System\ComponentTimer;
use Pam\Native\System\ComponentTimers;

use function count;
use function is_array;
use function is_int;
use function is_object;
use function is_string;

/**
 * Viewport-first, bounded windowing of keyed list rows. The first window
 * (and the one after admitViewport()) admits $firstFrameRows new rows; later
 * windows admit at most $batchRows new rows each. Rows join in list order,
 * mounted rows always stay (no unmount/remount churn), and deferred rows
 * join on the next frame, so no frame builds a whole page of cells.
 *
 *     $this->window ??= new ProgressiveListWindow(
 *         firstFrameRows: 12,
 *         batchRows: 8,
 *         timers: $this->timers(),           // schedules the next frame
 *         onNextFrame: fn () => $this->renderRows(),
 *     );
 *     [$this->rows, $deferred] = $this->window->apply($presentedRows);
 *
 * With timers and onNextFrame, apply() arms one component timer
 * ($frameMs, 16 by default) while rows are deferred; the component's
 * unmount cancels it. Rows are arrays with a 'key' (or objects with a
 * public $key) unless $keyOf is given.
 */
final class ProgressiveListWindow
{
    /** @var array<string, true> keys rendered by the last window */
    private array $mounted = [];
    private bool $deferred = false;
    private bool $viewport = false;
    private bool $all = false;
    private ?ComponentTimer $frame = null;
    private ?Closure $keyOf;

    /**
     * @param (Closure(mixed): (string|int))|null $keyOf
     * @param (Closure(): void)|null $onNextFrame re-renders the list on the next frame
     */
    public function __construct(
        public readonly int $firstFrameRows,
        public readonly int $batchRows = PHP_INT_MAX,
        private readonly ?ComponentTimers $timers = null,
        private readonly ?Closure $onNextFrame = null,
        public readonly int $frameMs = 16,
        ?Closure $keyOf = null,
    ) {
        if ($firstFrameRows < 1 || $batchRows < 1 || $frameMs < 0) {
            throw new InvalidArgumentException('ProgressiveListWindow needs positive row budgets and a non-negative frame delay.');
        }
        $this->keyOf = $keyOf;
    }

    /**
     * @template T
     * @param list<T> $rows
     * @return array{0: list<T>, 1: bool} visible rows and whether rows were deferred
     */
    public function apply(array $rows): array
    {
        $budget = $this->all ? PHP_INT_MAX
            : ($this->mounted === [] || $this->viewport ? $this->firstFrameRows : $this->batchRows);
        $this->viewport = false;
        $this->all = false;
        $window = [];
        $mounted = [];
        $admitting = true;
        foreach ($rows as $row) {
            $key = $this->key($row);
            if (isset($this->mounted[$key])) {
                $window[] = $row;
                $mounted[$key] = true;

                continue;
            }
            // Rows join in list order: once one waits, every later new row waits.
            if ($admitting && $budget > 0) {
                $window[] = $row;
                $mounted[$key] = true;
                --$budget;

                continue;
            }
            $admitting = false;
        }
        $this->mounted = $mounted;
        $this->deferred = count($window) < count($rows);
        if ($this->deferred) {
            $this->scheduleNextFrame();
        }

        return [$window, $this->deferred];
    }

    public function deferred(): bool
    {
        return $this->deferred;
    }

    /** A new set of rows starts (tab or filter switch): the next window admits a first-frame budget. */
    public function admitViewport(): void
    {
        $this->viewport = true;
    }

    /** The next window admits every row (a page loaded at the end, a scroll to the bottom). */
    public function admitAll(): void
    {
        $this->all = true;
    }

    /** Runs the pending next frame now, if rows are deferred. */
    public function expand(): void
    {
        $this->frame?->cancel();
        $this->frame = null;
        if ($this->deferred) {
            $this->onNextFrame?->__invoke();
        }
    }

    /** The list left the screen: its rows are gone. */
    public function reset(): void
    {
        $this->frame?->cancel();
        $this->frame = null;
        $this->mounted = [];
        $this->deferred = false;
        $this->viewport = false;
        $this->all = false;
    }

    public function mountedCount(): int
    {
        return count($this->mounted);
    }

    private function scheduleNextFrame(): void
    {
        if ($this->timers === null || $this->onNextFrame === null) {
            return;
        }
        $onNextFrame = $this->onNextFrame;
        $this->frame = $this->timers->ensure($this->frame, $this->frameMs, function () use ($onNextFrame): void {
            $this->frame = null;
            $onNextFrame();
        });
    }

    private function key(mixed $row): string
    {
        if ($this->keyOf !== null) {
            return (string) ($this->keyOf)($row);
        }
        $key = is_array($row) ? ($row['key'] ?? null) : (is_object($row) && isset($row->key) ? $row->key : null);
        if (is_string($key) || is_int($key)) {
            return (string) $key;
        }

        throw new InvalidArgumentException('ProgressiveListWindow rows need a key (or pass keyOf).');
    }
}
