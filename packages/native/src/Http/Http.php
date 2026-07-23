<?php

declare(strict_types=1);

namespace Pam\Native\Http;

use Closure;
use Pam\Native\Internal\Runtime;
use Pam\Native\Internal\Wire;
use Pam\Native\ModuleResultStatus;
use Pam\Native\NativeOperation;
use RuntimeException;

final class Http
{
    private function __construct()
    {
    }

    /** @param Closure(HttpResponse): void $callback */
    public static function get(string $url, Closure $callback): int
    {
        return Runtime::callNative(
            operation: NativeOperation::HttpGet,
            payload: Wire::map(['url' => $url]),
            callback: static function (ModuleResultStatus $status, string $payload) use ($callback): void {
                if ($status === ModuleResultStatus::Failure) {
                    throw new RuntimeException($payload);
                }

                $values = Wire::decodeMap($payload);
                $callback(new HttpResponse(
                    statusCode: (int) ($values['statusCode'] ?? 0),
                    body: (string) ($values['body'] ?? ''),
                ));
            },
        );
    }
}
