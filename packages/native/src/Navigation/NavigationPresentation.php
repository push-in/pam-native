<?php

declare(strict_types=1);

namespace Pam\Native\Navigation;

enum NavigationPresentation: int
{
    case Card = 1;
    case Modal = 2;
    case ContainedModal = 3;
    case FullScreenModal = 4;
    case TransparentModal = 5;
    case ContainedTransparentModal = 6;
    case FormSheet = 7;
}
