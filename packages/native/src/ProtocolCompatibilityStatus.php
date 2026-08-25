<?php

declare(strict_types=1);

namespace Pam\Native;

enum ProtocolCompatibilityStatus: int
{
    case Compatible = 1;
    case AbiMismatch = 2;
    case ProtocolMismatch = 3;
    case MissingCapability = 4;
}
