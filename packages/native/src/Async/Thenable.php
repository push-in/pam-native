<?php

declare(strict_types=1);

namespace Pam\Native\Async;

use Closure;

/**
 * A value that settles later, like a JavaScript promise. HTTP requests return
 * one; `useResource()` loaders may return one and the resource settles when it
 * does. `cancel()` drops the pending work (its callbacks never run) and is how
 * owners such as `useResource()` release a request when their scope unmounts.
 */
interface Thenable
{
    /**
     * @param (Closure(mixed): mixed)|null $onFulfilled
     * @param (Closure(\Throwable): mixed)|null $onRejected
     */
    public function then(?Closure $onFulfilled = null, ?Closure $onRejected = null): Thenable;

    /** @param Closure(\Throwable): mixed $onRejected */
    public function catch(Closure $onRejected): Thenable;

    /** @param Closure(): void $onSettled */
    public function finally(Closure $onSettled): Thenable;

    public function cancel(): void;

    public function state(): PromiseState;
}
