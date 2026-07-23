<?php

declare(strict_types=1);

namespace Pam\Native;

enum ModalPresentation: int
{
    case FullScreen = 1;
    case Dialog = 2;
    case Sheet = 3;
}
