<?php

declare(strict_types=1);

namespace App\Native;

use Pam\Native\Bridge\Attributes\NativeMethod;
use Pam\Native\Bridge\Attributes\NativeModule;
use Pam\Native\Bridge\Attributes\NativePermission;
use Pam\Native\Bridge\NativeCallKind;

#[NativeModule(id: 1, name: 'ShowcaseDevice')]
interface ShowcaseDeviceContract
{
    /** @return array<string, mixed> */
    #[NativeMethod(id: 1)]
    #[NativePermission('device.info')]
    public function information(): array;

    /** @return array<string, mixed> */
    #[NativeMethod(id: 2, kind: NativeCallKind::Stream, timeoutMs: 60_000)]
    public function lifecycle(): array;
}
