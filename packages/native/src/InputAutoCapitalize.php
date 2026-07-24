<?php

declare(strict_types=1);

namespace Pam\Native;

enum InputAutoCapitalize: int
{
    case None = 1;
    case Sentences = 2;
    case Words = 3;
    case Characters = 4;
}
