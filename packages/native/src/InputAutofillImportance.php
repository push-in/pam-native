<?php

declare(strict_types=1);

namespace Pam\Native;

enum InputAutofillImportance: int
{
    case Auto = 1;
    case No = 2;
    case NoExcludeDescendants = 3;
    case Yes = 4;
    case YesExcludeDescendants = 5;
}
