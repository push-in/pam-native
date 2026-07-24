<?php

declare(strict_types=1);

namespace Pam\Native;

enum TextHyphenationFrequency: int
{
    case None = 1;
    case Normal = 2;
    case Full = 3;
}
