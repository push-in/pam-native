<?php

declare(strict_types=1);

namespace Pam\Native;

enum SensorType: int
{
    case Accelerometer = 1;
    case Gyroscope = 2;
    case Magnetometer = 3;
    case DeviceMotion = 4;
}
