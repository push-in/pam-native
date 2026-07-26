<?php

declare(strict_types=1);

namespace Pam\Native\Forms;

enum FormStatus: int
{
    case Idle = 1;
    case Editing = 2;
    case Validating = 3;
    case Submitting = 4;
    case Success = 5;
    case Failure = 6;
}
