<?php

declare(strict_types=1);

namespace Pam\Native\System;

use Closure;
use Pam\Native\CaptureType;
use Pam\Native\FileReference;
use Pam\Native\Internal\Wire;
use Pam\Native\ModuleResultStatus;
use Pam\Native\Modules\NativeModules;
use RuntimeException;

final class MediaCapture
{
    private function __construct()
    {
    }

    /** @param Closure(FileReference): void $callback */
    public static function capture(CaptureType $type, Closure $callback): int
    {
        return NativeModules::call(
            'files',
            'capture',
            ['type' => $type->value],
            static function ($result) use ($callback): void {
                if ($result->status === ModuleResultStatus::Failure) {
                    throw new RuntimeException($result->payload);
                }
                $values = Wire::decodeMap($result->payload);
                $callback(new FileReference(
                    path: (string) ($values['path'] ?? ''),
                    name: (string) ($values['name'] ?? ''),
                    mimeType: (string) ($values['mimeType'] ?? 'application/octet-stream'),
                    size: (int) ($values['size'] ?? 0),
                ));
            },
        );
    }
}
