<?php

declare(strict_types=1);

namespace Pam\Native;

/** The application's colour-scheme preference, persisted by the native host. */
enum AppearanceMode: int
{
    /** Follow the operating system's light or dark setting. */
    case System = 1;
    case Light = 2;
    case Dark = 3;
}
