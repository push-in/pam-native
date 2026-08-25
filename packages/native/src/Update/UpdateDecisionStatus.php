<?php

declare(strict_types=1);

namespace Pam\Native\Update;

enum UpdateDecisionStatus: int
{
    case Approved = 1;
    case InvalidSignature = 2;
    case Incompatible = 3;
    case OutsideRollout = 4;
    case AlreadyCurrent = 5;
    case InvalidManifest = 6;
}
