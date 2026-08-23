<?php

declare(strict_types=1);

namespace Pam\Native\Plugin;

enum TrustTier: int
{
    case Community = 1;
    case Verified = 2;
    case Official = 3;
}
