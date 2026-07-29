<?php

declare(strict_types=1);

namespace Pam\Native;

final readonly class LocationPosition
{
    public function __construct(
        public float $latitude,
        public float $longitude,
        public float $accuracy,
        public float $altitude,
        public float $speed,
        public float $bearing,
        public int $timestamp,
    ) {
    }
}
