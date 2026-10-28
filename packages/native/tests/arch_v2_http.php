<?php

declare(strict_types=1);

use Pam\Native\Async\Promise;
use Pam\Native\Async\PromiseState;
use Pam\Native\Async\Thenable;
use Pam\Native\Http\Http;
use Pam\Native\Http\HttpClient;
use Pam\Native\Http\HttpError;
use Pam\Native\Http\HttpResponse;
use Pam\Native\Http\PendingResponse;
use Pam\Native\Http\Request;
use Pam\Native\Http\Response;
use Pam\Native\Internal\Runtime;
use Pam\Native\Internal\Wire;
use Pam\Native\ModuleResultStatus;

$assert = require __DIR__.'/arch_v2_bootstrap.php';

// --- Promise
$log = [];
$promise = new Promise();
$chained = $promise->then(static fn (int $value): int => $value * 2)->then(static function (int $value) use (&$log): void { $log[] = $value; });
$promise->resolve(21);
$assert($log === [42] && $chained->state() === PromiseState::Fulfilled, 'then() chains run when the promise resolves.');
$caught = null;
Promise::rejected(new RuntimeException('boom'))->then(static fn () => 'skipped')->catch(static function (Throwable $e) use (&$caught): string { $caught = $e->getMessage(); return 'recovered'; });
$assert($caught === 'boom', 'catch() receives rejections that skipped then().');
$adopted = null;
Promise::resolved(1)->then(static fn () => Promise::resolved('inner'))->then(static function ($v) use (&$adopted): void { $adopted = $v; });
$assert($adopted === 'inner', 'A returned Thenable is adopted.');
$all = null;
Promise::all(['a' => Promise::resolved(1), 'b' => 2])->then(static function (array $v) use (&$all): void { $all = $v; });
$assert($all === ['a' => 1, 'b' => 2], 'Promise::all() collects values by key.');
$cancelled = new Promise();
$ran = false;
$cancelled->then(static function () use (&$ran): void { $ran = true; });
$cancelled->cancel();
$cancelled->resolve('late');
$assert(!$ran && $cancelled->state() === PromiseState::Cancelled, 'Cancelled promises ignore late results.');
$finally = 0;
Promise::resolved(1)->finally(static function () use (&$finally): void { $finally++; });
$assert($finally === 1, 'finally() runs on settle.');

// --- Request is immutable
$request = new Request('GET', 'https://api.example.test/feed', ['Accept' => 'application/json']);
$tokened = $request->withToken('abc')->withHeader('X-Trace', '1')->withQuery(['cursor' => 'c1', 'skip' => null, 'flag' => true]);
$assert(
    $request->header('authorization') === null && $tokened->header('Authorization') === 'Bearer abc'
        && $tokened->fullUrl() === 'https://api.example.test/feed?cursor=c1&flag=1'
        && $tokened->withoutHeader('x-trace')->header('X-Trace') === null
        && $tokened->withJson(['a' => 1])->body === '{"a":1}' && $tokened->withJson([])->header('content-type') === 'application/json',
    'Request with*() must return copies.',
);

// --- Client with a fake transport
$sent = [];
$finish = [];
$transport = static function (Request $r, Closure $done) use (&$sent, &$finish): ?Closure {
    $sent[] = $r;
    $finish[] = $done;

    return null;
};
$token = 'first';
$errors = [];
$client = Http::create('https://api.example.test/v1/', headers: ['X-App' => 'pam'], timeout: 5_000, retry: 2, retryDelay: 0)
    ->withTransport($transport)
    ->onRequest(static function (Request $r) use (&$token): Request { return $r->withToken($token); })
    ->onRequest(static fn (Request $r) => null)
    ->onResponse(static fn (Response $r) => $r->status === 200 ? $r->withBody(str_replace('old', 'new', $r->body)) : null)
    ->onError(static function (HttpError $e) use (&$errors, &$token) {
        $errors[] = $e->status;
        if ($e->status === 401 && $e->request->context('retried') === null) {
            $token = 'refreshed';
            return $e->request->withContext('retried', true);
        }
        return null;
    });
$assert($client instanceof HttpClient, 'Http::create() returns an HttpClient.');
$feed = null;
$pending = $client->get('/feed', ['cursor' => 'x']);
$assert($pending instanceof PendingResponse && $pending instanceof Thenable, 'Requests return a Thenable PendingResponse.');
$pending->json('items')->then(static function ($items) use (&$feed): void { $feed = $items; });
$assert(
    $sent[0]->fullUrl() === 'https://api.example.test/v1/feed?cursor=x' && $sent[0]->header('authorization') === 'Bearer first'
        && $sent[0]->header('x-app') === 'pam' && $sent[0]->header('accept') === 'application/json' && $sent[0]->timeoutMs === 5_000,
    'Base URL, query, default headers, timeout and onRequest must apply.',
);
$finish[0](new HttpResponse(401, '{"message":"expired"}'));
$assert(count($sent) === 2 && $sent[1]->header('authorization') === 'Bearer refreshed' && $errors === [401], 'onError returning a Request resends it through onRequest.');
$finish[1](new HttpResponse(200, '{"items":["old"]}', headers: ['Content-Type' => 'application/json']));
$assert($feed === ['new'] && $pending->response()->status === 200 && $pending->response()->header('content-type') === 'application/json', 'onResponse runs before json().');

// Retry: idempotent requests retry on 5xx and transport failures, POST does not.
$sent = $finish = [];
$result = null;
$client->get('/flaky')->then(static function (Response $r) use (&$result): void { $result = $r->json(); });
$finish[0](new HttpResponse(503, ''));
$finish[1](new HttpResponse(0, '', 'timeout'));
$finish[2](new HttpResponse(200, '{"ok":true}'));
$assert(count($sent) === 3 && $sent[2]->attempt === 3 && $result === ['ok' => true], 'GET retries up to retry times.');
$sent = $finish = [];
$failure = null;
$client->post('/messages', ['text' => 'oi'])->catch(static function (HttpError $e) use (&$failure): void { $failure = $e; });
$assert($sent[0]->method === 'POST' && $sent[0]->body === '{"text":"oi"}' && $sent[0]->header('content-type') === 'application/json', 'post() sends JSON.');
$finish[0](new HttpResponse(500, '{"message":"down"}'));
$assert(count($sent) === 1 && $failure instanceof HttpError && $failure->isServerError() && $failure->json('message') === 'down', 'POST does not retry; HttpError carries the response.');
$sent = $finish = [];
$network = null;
$client->delete('/x')->catch(static function (HttpError $e) use (&$network): void { $network = $e; });
foreach ([0, 1, 2] as $attempt) $finish[$attempt](new HttpResponse(0, '', 'offline'));
$assert($network instanceof HttpError && $network->isNetworkError() && $network->response === null && count($sent) === 3, 'Transport failures reject with a network HttpError after the retries.');

// Recovery with a Response, and cancel().
$recovered = null;
$sent = $finish = [];
Http::create('https://api.example.test')->withTransport($transport)
    ->onError(static fn (HttpError $e) => new Response(200, [], '{"cached":true}', $e->request))
    ->get('/offline')->json()->then(static function ($v) use (&$recovered): void { $recovered = $v; });
$finish[0](new HttpResponse(0, '', 'offline'));
$assert($recovered === ['cached' => true], 'onError may recover with a Response.');
$sent = $finish = [];
$late = false;
$cancellable = Http::create()->withTransport($transport)->get('https://api.example.test/slow');
$cancellable->then(static function () use (&$late): void { $late = true; });
$cancellable->cancel();
$finish[0](new HttpResponse(200, '{}'));
$assert(!$late && $cancellable->state() === PromiseState::Cancelled, 'cancel() drops the response (useResource cancels on unmount).');

// --- Real transport: the native Http module, multipart and upload with progress
$GLOBALS['pamArchV2NativeCalls'] = [];
// Standalone the bootstrap records native calls; under run.php TestDiagnostics does.
$lastNativeCall = static function (): array {
    if (class_exists('TestDiagnostics', false) && TestDiagnostics::$moduleCall !== null) {
        $call = TestDiagnostics::$moduleCall;
        return [$call['requestId'], $call['module'], $call['method'], $call['payload']];
    }
    return end($GLOBALS['pamArchV2NativeCalls']);
};
$native = Http::create('https://api.example.test', timeout: 9_000)->onRequest(static fn (Request $r) => $r->withToken('t'));
$body = null;
$native->patch('/me', ['name' => 'Ana'])->json()->then(static function ($v) use (&$body): void { $body = $v; });
[$requestId, $module, $method, $payload] = $lastNativeCall();
$decoded = Wire::decodeMap($payload);
$headers = json_decode((string) $decoded['headers'], true);
$assert($module === 'http' && $method === 'request' && $decoded['method'] === 'PATCH' && $decoded['url'] === 'https://api.example.test/me'
    && $decoded['timeoutMs'] === 9_000 && $headers['Authorization'] === 'Bearer t' && $decoded['body'] === '{"name":"Ana"}', 'The client uses the native http module.');
Runtime::dispatchModuleResult($requestId, ModuleResultStatus::Success->value, Wire::map(['statusCode' => 200, 'body' => '{"name":"Ana"}']));
$assert($body === ['name' => 'Ana'], 'Native results settle the PendingResponse.');
$progress = [];
$native->multipart('/upload', static fn ($form) => $form->field('caption', 'oi')->file('media', 'uploads/a.jpg', 'image/jpeg'), static function ($p) use (&$progress): void { $progress[] = $p; });
[, $module, $method, $payload] = $lastNativeCall();
$decoded = Wire::decodeMap($payload);
$assert($module === 'http' && $method === 'transferStart' && $decoded['kind'] === 1 && $decoded['method'] === 'POST'
    && json_decode((string) $decoded['headers'], true)['Authorization'] === 'Bearer t'
    && count(json_decode((string) $decoded['parts'], true)) === 2, 'multipart() keeps the native transfer with interceptors applied.');
$native->upload('https://storage.example.test/signed', 'uploads/a.jpg', ['Content-Type' => 'image/jpeg'], static function (): void {});
[, , $method, $payload] = $lastNativeCall();
$decoded = Wire::decodeMap($payload);
$assert($method === 'transferStart' && $decoded['kind'] === 2 && $decoded['method'] === 'PUT', 'upload() with progress uses the native transfer.');

echo "Architecture v2 HTTP tests passed.\n";
