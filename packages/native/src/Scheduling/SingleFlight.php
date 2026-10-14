<?php

declare(strict_types=1);

namespace Pam\Native\Scheduling;

use Closure;
use InvalidArgumentException;
use OverflowException;
use Throwable;

use function array_key_exists;
use function count;

/**
 * Collapses concurrent requests for the same key into one in-flight call.
 * Callers that arrive while it runs join it and receive the same result.
 *
 *     $flights->run("profile:{$id}", function (Closure $done) use ($id): void {
 *         Http::get("/users/{$id}", fn (HttpResponse $r) => $done($r));
 *     }, fn (?HttpResponse $r, ?Throwable $error) => $this->show($r));
 *
 * $work receives `$done(mixed $result = null, ?Throwable $error = null)` and
 * must call it exactly once; later calls are ignored. With $again, a request
 * made while the key is in flight schedules one more run after it, and the
 * callers that joined late receive that fresher result (refresh-on-demand).
 */
final class SingleFlight
{
    /** @var array<string, array{waiters: list<Closure>, again: bool, later: list<Closure>, work: Closure, token?: object}> */
    private array $flights = [];

    public function __construct(private readonly int $maxKeys = 1_024)
    {
        if ($maxKeys < 1) {
            throw new InvalidArgumentException('SingleFlight needs room for at least one key.');
        }
    }

    /**
     * @param Closure(Closure(mixed=, ?Throwable=): void): void $work
     * @param (Closure(mixed, ?Throwable): void)|null $onDone
     * @return bool true when this call started the work, false when it joined
     */
    public function run(string $key, Closure $work, ?Closure $onDone = null, bool $again = false): bool
    {
        $flight = $this->flights[$key] ?? null;
        if ($flight !== null) {
            if ($again) {
                $this->flights[$key]['again'] = true;
                $this->flights[$key]['work'] = $work;
                if ($onDone !== null) {
                    $this->flights[$key]['later'][] = $onDone;
                }
            } elseif ($onDone !== null) {
                $this->flights[$key]['waiters'][] = $onDone;
            }

            return false;
        }
        if (count($this->flights) >= $this->maxKeys) {
            throw new OverflowException("SingleFlight exceeded {$this->maxKeys} concurrent keys.");
        }
        $this->flights[$key] = ['waiters' => $onDone === null ? [] : [$onDone], 'again' => false, 'later' => [], 'work' => $work];
        $this->start($key, $work);

        return true;
    }

    public function inFlight(string $key): bool
    {
        return isset($this->flights[$key]);
    }

    public function count(): int
    {
        return count($this->flights);
    }

    /** Forgets $key: its waiters are never called and a late result is ignored. */
    public function forget(string $key): void
    {
        unset($this->flights[$key]);
    }

    public function clear(): void
    {
        $this->flights = [];
    }

    private function start(string $key, Closure $work): void
    {
        $settled = false;
        $token = new \stdClass();
        $this->flights[$key]['token'] = $token;
        $done = function (mixed $result = null, ?Throwable $error = null) use ($key, $token, &$settled): void {
            if ($settled) {
                return;
            }
            $settled = true;
            $this->finish($key, $token, $result, $error);
        };
        try {
            $work($done);
        } catch (Throwable $error) {
            $done(null, $error);
        }
    }

    private function finish(string $key, object $token, mixed $result, ?Throwable $error): void
    {
        $flight = $this->flights[$key] ?? null;
        if ($flight === null || ($flight['token'] ?? null) !== $token) {
            return;
        }
        if ($flight['again']) {
            $this->flights[$key] = ['waiters' => $flight['later'], 'again' => false, 'later' => [], 'work' => $flight['work']];
        } else {
            unset($this->flights[$key]);
        }
        $failure = null;
        foreach ($flight['waiters'] as $waiter) {
            try {
                $waiter($result, $error);
            } catch (Throwable $thrown) {
                $failure ??= $thrown;
            }
        }
        if ($flight['again'] && array_key_exists($key, $this->flights)) {
            $this->start($key, $flight['work']);
        }
        if ($failure !== null) {
            throw $failure;
        }
    }
}
