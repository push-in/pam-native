<?php

declare(strict_types=1);

namespace Pam\Native;

final readonly class AudioRecordingProgress
{
    public function __construct(
        public int $durationMs,
        public float $amplitude,
    ) {
    }
}
