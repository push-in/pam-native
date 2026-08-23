<?php

declare(strict_types=1);

namespace Pam\Native\Bridge\Attributes;

use Attribute;
use Pam\Native\Bridge\NativeCallKind;

#[Attribute(Attribute::TARGET_METHOD)]
final readonly class NativeMethod
{
    public function __construct(
        public int $id,
        public NativeCallKind $kind = NativeCallKind::Request,
        public int $timeoutMs = 30_000,
    ) {
        if ($id < 1 || $timeoutMs < 1 || $timeoutMs > 300_000) {
            throw new \InvalidArgumentException('Native method id or timeout is outside the supported range.');
        }
    }
}
