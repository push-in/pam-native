<?php

declare(strict_types=1);

namespace Pam\Native\Http;

use Closure;
use Pam\Native\Async\Promise;
use Pam\Native\Async\PromiseState;
use Pam\Native\Async\Thenable;

/**
 * In-flight `HttpClient` request. It is a Thenable of `Response`, plus
 * shortcuts that chain the common decoding steps:
 *
 *     $client->get('/feed')->json()->then(fn (array $feed) => ...);
 */
final class PendingResponse implements Thenable
{
    /** @internal */
    public function __construct(private readonly Promise $promise)
    {
    }

    public function then(?Closure $onFulfilled = null, ?Closure $onRejected = null): Thenable
    {
        return $this->promise->then($onFulfilled, $onRejected);
    }

    public function catch(Closure $onRejected): Thenable
    {
        return $this->promise->catch($onRejected);
    }

    public function finally(Closure $onSettled): Thenable
    {
        return $this->promise->finally($onSettled);
    }

    /** Thenable of the decoded JSON body (or of one dot-path key of it). */
    public function json(?string $key = null, mixed $default = null): Thenable
    {
        return $this->promise->then(static fn (Response $response): mixed => $response->json($key, $default));
    }

    /** Thenable of the raw body. */
    public function text(): Thenable
    {
        return $this->promise->then(static fn (Response $response): string => $response->body);
    }

    /** Stops waiting: no callback runs and an upload transfer is aborted. */
    public function cancel(): void
    {
        $this->promise->cancel();
    }

    public function state(): PromiseState
    {
        return $this->promise->state();
    }

    /** The settled Response; throws the HttpError, or LogicException while pending. */
    public function response(): Response
    {
        return $this->promise->value();
    }
}
