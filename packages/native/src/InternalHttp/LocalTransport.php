<?php

declare(strict_types=1);

namespace Pam\Native\InternalHttp;

final readonly class LocalTransport
{
    public function __construct(private Router $router)
    {
    }

    public function send(Request $request): Response
    {
        return $this->router->dispatch($request);
    }

    public function opensNetworkSocket(): bool
    {
        return false;
    }
}
