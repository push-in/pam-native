<?php

declare(strict_types=1);

namespace Pam\Native;

enum ModalAnimationType: int
{
    case None = 1;
    case Slide = 2;
    case Fade = 3;
}
