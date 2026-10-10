<?php

declare(strict_types=1);

namespace Pam\Native;

/** Outcome of Location::requestServices() (the system location switch, not the permission). */
enum LocationServicesResult: int
{
    /** Location services are on (already, or the user accepted the system dialog). */
    case Enabled = 1;
    /** The user dismissed the system "turn on location" dialog. Do not ask again right away. */
    case Denied = 2;
    /** No dialog is possible (no Play Services, no activity, iOS): offer Location::openSettings(). */
    case Unavailable = 3;
}
