<?php

declare(strict_types=1);

namespace Pam\Native\System;

/** Native alert button roles (Android dialog slot / iOS UIAlertAction.Style). */
enum AlertButtonStyle: int
{
    case Default = 1;
    case Cancel = 2;
    case Destructive = 3;
}
