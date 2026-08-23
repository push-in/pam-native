<?php

declare(strict_types=1);

namespace Pam\Native\InternalHttp;

final class Router
{
    /** @var array<string, \Closure(Request): Response> */
    private array $routes = [];
    /** @var list<\Closure(Request, \Closure(Request): Response): Response> */
    private array $middleware = [];

    /** @param \Closure(Request): Response $handler */
    public function route(string $method, string $path, \Closure $handler): self
    {
        $request = new Request(strtoupper($method), $path);
        $this->routes["{$request->method} {$request->path}"] = $handler;
        return $this;
    }

    /** @param \Closure(Request, \Closure(Request): Response): Response $middleware */
    public function middleware(\Closure $middleware): self
    {
        $this->middleware[] = $middleware;
        return $this;
    }

    public function dispatch(Request $request): Response
    {
        $handler = $this->routes["{$request->method} {$request->path}"] ?? null;
        if ($handler === null) {
            return Response::json(['error' => 'not_found'], 404);
        }
        $next = $handler;
        foreach (array_reverse($this->middleware) as $middleware) {
            $downstream = $next;
            $next = static fn (Request $current): Response => $middleware($current, $downstream);
        }
        return $next($request);
    }
}
