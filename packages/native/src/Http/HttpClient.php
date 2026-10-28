<?php

declare(strict_types=1);

namespace Pam\Native\Http;

use Closure;
use InvalidArgumentException;
use Pam\Native\Async\Promise;
use Pam\Native\Async\PromiseState;
use Pam\Native\Async\Thenable;
use Pam\Native\System\Timers;
use Throwable;

use function is_array;
use function is_object;
use function is_string;

/**
 * Axios-style client over the native `Http` transport (requests run on the
 * native networking stack; PHP only receives the settled result).
 *
 *     $api = Http::create(baseUrl: 'https://api.example.com', retry: 2)
 *         ->onRequest(fn (Request $r) => $r->withToken($session->token()))
 *         ->onError(fn (HttpError $e) => $e->status === 401 ? $session->expire() : null);
 *     $api->get('/feed', ['cursor' => $cursor])->json()->then(...);
 *
 * Interceptors run in registration order. `onRequest` returns the (new)
 * Request, null to keep it, or a Thenable of one (e.g. a token refresh).
 * `onResponse` returns the Response, null, or a Thenable. `onError` may
 * recover with a Response, resend with a Request, return a Thenable of either,
 * or return null to let the rejection continue. The client is immutable: every
 * configuration method returns a copy.
 */
final class HttpClient
{
    private const MAX_RESENDS = 3;
    private const MAX_RETRIES = 5;

    /** @var list<Closure(Request): mixed> */
    private array $requestInterceptors = [];
    /** @var list<Closure(Response): mixed> */
    private array $responseInterceptors = [];
    /** @var list<Closure(HttpError): mixed> */
    private array $errorInterceptors = [];
    /** @var (Closure(Request, Closure(HttpResponse): void): (Closure(): void)|null)|null */
    private ?Closure $transport = null;

    /** @param array<string, string> $headers */
    public function __construct(
        private string $baseUrl = '',
        private array $headers = ['Accept' => 'application/json'],
        private int $timeoutMs = 30_000,
        private int $retries = 0,
        private int $retryDelayMs = 300,
    ) {
        if ($baseUrl !== '' && preg_match('#^https?://[^/\s]+#i', $baseUrl) !== 1) {
            throw new InvalidArgumentException('HTTP client base URLs must be absolute http(s) URLs.');
        }
        if ($timeoutMs < 1 || $timeoutMs > 600_000) {
            throw new InvalidArgumentException('HTTP client timeout must be between 1 and 600000 ms.');
        }
        if ($retries < 0 || $retries > self::MAX_RETRIES || $retryDelayMs < 0 || $retryDelayMs > 60_000) {
            throw new InvalidArgumentException('HTTP client retry must be 0-5 attempts with a delay up to 60 s.');
        }
        Http::normalizeHeaders($headers);
    }

    /** @param Closure(Request): (Request|Thenable|null) $interceptor */
    public function onRequest(Closure $interceptor): self
    {
        $copy = clone $this;
        $copy->requestInterceptors[] = $interceptor;

        return $copy;
    }

    /** @param Closure(Response): (Response|Thenable|null) $interceptor */
    public function onResponse(Closure $interceptor): self
    {
        $copy = clone $this;
        $copy->responseInterceptors[] = $interceptor;

        return $copy;
    }

    /** @param Closure(HttpError): (Response|Request|Thenable|null) $interceptor */
    public function onError(Closure $interceptor): self
    {
        $copy = clone $this;
        $copy->errorInterceptors[] = $interceptor;

        return $copy;
    }

    /** @param array<string, string> $headers */
    public function withHeaders(array $headers): self
    {
        Http::normalizeHeaders($headers);
        $copy = clone $this;
        $copy->headers = [...$this->headers, ...$headers];

        return $copy;
    }

    public function withBaseUrl(string $baseUrl): self
    {
        return $this->reconfigured(baseUrl: $baseUrl);
    }

    public function withTimeout(int $milliseconds): self
    {
        return $this->reconfigured(timeoutMs: $milliseconds);
    }

    /**
     * Retries idempotent requests (GET/PUT/DELETE) after a transport failure,
     * 408, 429 or 5xx, waiting `$delayMs * 2^n` (or Retry-After, capped at 60 s).
     */
    public function withRetry(int $times, int $delayMs = 300): self
    {
        return $this->reconfigured(retries: $times, retryDelayMs: $delayMs);
    }

    /**
     * @internal Replaces the native transport (tests, local fakes). The
     * closure receives the final Request and a completion callback and may
     * return a canceller.
     *
     * @param Closure(Request, Closure(HttpResponse): void): ((Closure(): void)|null) $transport
     */
    public function withTransport(Closure $transport): self
    {
        $copy = clone $this;
        $copy->transport = $transport;

        return $copy;
    }

    /** @param array<string, string|int|float|bool|null> $query @param array<string, string> $headers */
    public function get(string $url, array $query = [], array $headers = []): PendingResponse
    {
        return $this->request('GET', $url, null, $query, $headers);
    }

    /** @param array<string, string|int|float|bool|null> $query @param array<string, string> $headers */
    public function delete(string $url, array $query = [], array $headers = []): PendingResponse
    {
        return $this->request('DELETE', $url, null, $query, $headers);
    }

    /** @param array<string, string> $headers */
    public function post(string $url, mixed $data = null, array $headers = []): PendingResponse
    {
        return $this->request('POST', $url, $data, [], $headers);
    }

    /** @param array<string, string> $headers */
    public function put(string $url, mixed $data = null, array $headers = []): PendingResponse
    {
        return $this->request('PUT', $url, $data, [], $headers);
    }

    /** @param array<string, string> $headers */
    public function patch(string $url, mixed $data = null, array $headers = []): PendingResponse
    {
        return $this->request('PATCH', $url, $data, [], $headers);
    }

    /**
     * Arrays and objects are sent as JSON, strings as-is.
     *
     * @param array<string, string|int|float|bool|null> $query
     * @param array<string, string> $headers
     */
    public function request(
        string $method,
        string $url,
        mixed $data = null,
        array $query = [],
        array $headers = [],
    ): PendingResponse {
        $request = new Request(
            method: strtoupper(trim($method)),
            url: $this->resolve($url),
            headers: [...$this->headers, ...$headers],
            query: $query,
            timeoutMs: $this->timeoutMs,
        );
        if (is_string($data)) {
            $request = $request->withBody($data);
        } elseif (is_array($data) || is_object($data)) {
            $request = $request->withJson($data);
        }

        return $this->send($request);
    }

    /**
     * multipart/form-data upload with progress; interceptors (token, headers)
     * apply to it like to any request.
     *
     * @param Closure(MultipartRequest): (MultipartRequest|null) $build
     * @param (Closure(TransferProgress): void)|null $progress
     */
    public function multipart(
        string $url,
        Closure $build,
        ?Closure $progress = null,
        string $method = 'POST',
    ): PendingResponse {
        $request = (new Request(strtoupper($method), $this->resolve($url), $this->headers, timeoutMs: max($this->timeoutMs, 120_000)))
            ->withContext('pam.multipart', $build)
            ->withContext('pam.progress', $progress);

        return $this->send($request);
    }

    /**
     * PUT a private file (e.g. to a signed storage URL) without reading it
     * into PHP, with optional byte progress.
     *
     * @param (Closure(TransferProgress): void)|null $progress
     * @param array<string, string> $headers
     */
    public function upload(string $url, string $path, array $headers = [], ?Closure $progress = null): PendingResponse
    {
        $request = (new Request('PUT', $this->resolve($url), [...$this->headers, ...$headers], timeoutMs: max($this->timeoutMs, 120_000)))
            ->withContext('pam.upload', $path)
            ->withContext('pam.progress', $progress);

        return $this->send($request);
    }

    public function send(Request $request): PendingResponse
    {
        $promise = new Promise();
        $this->dispatch($request, $promise, 0);

        return new PendingResponse($promise);
    }

    private function dispatch(Request $request, Promise $promise, int $resends): void
    {
        if ($promise->state() !== PromiseState::Pending) return;
        $this->intercept($request, 0)->then(
            function (Request $prepared) use ($request, $promise, $resends): void {
                if ($promise->state() !== PromiseState::Pending) return;
                $cancel = $this->transmit($prepared, function (HttpResponse $raw) use ($request, $prepared, $promise, $resends): void {
                    if ($promise->state() !== PromiseState::Pending) return;
                    $this->settle($raw, $request, $prepared, $promise, $resends);
                });
                if ($cancel !== null) $promise->onCancel($cancel);
            },
            function (Throwable $error) use ($promise): void {
                if ($promise->state() === PromiseState::Pending) $promise->reject($error);
            },
        );
    }

    /** Runs the onRequest chain, each step possibly asynchronous. */
    private function intercept(Request $request, int $index): Thenable
    {
        $interceptor = $this->requestInterceptors[$index] ?? null;
        if ($interceptor === null) return Promise::resolved($request);
        try {
            $result = $interceptor($request);
        } catch (Throwable $error) {
            return Promise::rejected($error);
        }

        return Promise::from($result)->then(function (mixed $next) use ($request, $index): Thenable {
            if ($next !== null && !$next instanceof Request) {
                throw new InvalidArgumentException('onRequest interceptors must return a Request, null or a Thenable of one.');
            }

            return $this->intercept($next ?? $request, $index + 1);
        });
    }

    private function settle(HttpResponse $raw, Request $original, Request $prepared, Promise $promise, int $resends): void
    {
        $response = $raw->transportFailed() ? null : Response::fromTransport($raw, $prepared);
        if ($response !== null && $response->status < 400) {
            $promise->resolve($this->respond($response, 0));
            return;
        }
        $error = $response === null
            ? new HttpError($raw->error !== '' ? $raw->error : 'Network request failed.', $prepared)
            : new HttpError("HTTP {$response->status} from {$prepared->method} {$prepared->url}.", $prepared, $response);
        if ($this->retryable($prepared, $response) && $prepared->attempt <= $this->retries) {
            $delay = $this->retryDelay($prepared->attempt, $response);
            $retry = function () use ($original, $prepared, $promise, $resends): void {
                $this->dispatch($original->withAttempt($prepared->attempt + 1), $promise, $resends);
            };
            if ($delay === 0) {
                $retry();
            } else {
                $timer = Timers::timeout($delay, $retry);
                $promise->onCancel(static fn () => Timers::cancel($timer));
            }
            return;
        }
        $this->recover($error, 0, $promise, $resends);
    }

    /** Runs the onResponse chain. */
    private function respond(Response $response, int $index): Thenable
    {
        $interceptor = $this->responseInterceptors[$index] ?? null;
        if ($interceptor === null) return Promise::resolved($response);
        try {
            $result = $interceptor($response);
        } catch (Throwable $error) {
            return Promise::rejected($error);
        }

        return Promise::from($result)->then(function (mixed $next) use ($response, $index): Thenable {
            if ($next !== null && !$next instanceof Response) {
                throw new InvalidArgumentException('onResponse interceptors must return a Response, null or a Thenable of one.');
            }

            return $this->respond($next ?? $response, $index + 1);
        });
    }

    /** Runs onError handlers until one recovers (Response) or resends (Request). */
    private function recover(HttpError $error, int $index, Promise $promise, int $resends): void
    {
        $handler = $this->errorInterceptors[$index] ?? null;
        if ($handler === null) {
            $promise->reject($error);
            return;
        }
        try {
            $result = $handler($error);
        } catch (Throwable $thrown) {
            $promise->reject($thrown);
            return;
        }
        Promise::from($result)->then(
            function (mixed $outcome) use ($error, $index, $promise, $resends): void {
                if ($promise->state() !== PromiseState::Pending) return;
                if ($outcome instanceof Response) {
                    $promise->resolve($this->respond($outcome, 0));
                    return;
                }
                if ($outcome instanceof Request) {
                    if ($resends >= self::MAX_RESENDS) {
                        $promise->reject($error);
                        return;
                    }
                    $this->dispatch($outcome->withAttempt(1), $promise, $resends + 1);
                    return;
                }
                $this->recover($error, $index + 1, $promise, $resends);
            },
            function (Throwable $thrown) use ($promise): void {
                if ($promise->state() === PromiseState::Pending) $promise->reject($thrown);
            },
        );
    }

    /** @return (Closure(): void)|null */
    private function transmit(Request $request, Closure $done): ?Closure
    {
        if ($this->transport !== null) return ($this->transport)($request, $done);
        $headers = $request->headers;
        $upload = $request->context('pam.upload');
        $build = $request->context('pam.multipart');
        $progress = $request->context('pam.progress');
        if (is_string($upload)) {
            if ($progress instanceof Closure) {
                $transfer = Http::uploadWithProgress($request->fullUrl(), $upload, $done, $progress, $headers, $request->timeoutMs);

                return static fn () => Http::cancelTransfer($transfer);
            }
            Http::upload($request->fullUrl(), $upload, $done, $headers, $request->timeoutMs);

            return null;
        }
        if ($build instanceof Closure) {
            $multipart = Http::multipart($request->fullUrl())->method($request->method)->timeout($request->timeoutMs);
            foreach ($headers as $name => $value) {
                if (strcasecmp($name, 'Content-Type') !== 0) $multipart->header($name, $value);
            }
            $multipart = $build($multipart) ?? $multipart;
            if ($progress instanceof Closure) $multipart->onProgress($progress);
            $transfer = $multipart->send($done);

            return static fn () => Http::cancelTransfer($transfer);
        }
        Http::request($request->method, $request->fullUrl(), $done, $headers, $request->body, $request->timeoutMs);

        return null;
    }

    private function retryable(Request $request, ?Response $response): bool
    {
        if ($this->retries === 0 || !$request->idempotent() || $request->context('pam.upload') !== null) return false;

        return $response === null || $response->status === 408 || $response->status === 429 || $response->status >= 500;
    }

    private function retryDelay(int $attempt, ?Response $response): int
    {
        $after = $response?->header('retry-after');
        if ($after !== null && ctype_digit($after)) return min(60_000, (int) $after * 1_000);

        return min(60_000, $this->retryDelayMs * (2 ** ($attempt - 1)));
    }

    private function resolve(string $url): string
    {
        if (preg_match('#^[a-z][a-z0-9+.-]*://#i', $url) === 1 || $this->baseUrl === '') return $url;

        return rtrim($this->baseUrl, '/').'/'.ltrim($url, '/');
    }

    private function reconfigured(
        ?string $baseUrl = null,
        ?int $timeoutMs = null,
        ?int $retries = null,
        ?int $retryDelayMs = null,
    ): self {
        $copy = new self(
            $baseUrl ?? $this->baseUrl,
            $this->headers,
            $timeoutMs ?? $this->timeoutMs,
            $retries ?? $this->retries,
            $retryDelayMs ?? $this->retryDelayMs,
        );
        $copy->requestInterceptors = $this->requestInterceptors;
        $copy->responseInterceptors = $this->responseInterceptors;
        $copy->errorInterceptors = $this->errorInterceptors;
        $copy->transport = $this->transport;

        return $copy;
    }
}
