<?php

declare(strict_types=1);

namespace Pam\Native\Sync;

/** When OfflineMutationQueue::drainOn() calls the drain callback. */
enum DrainTrigger: int
{
    /** The device goes from no network to a connection (watched only while mutations are pending). */
    case Connectivity = 1;
    /** The app returns to the foreground. */
    case Foreground = 2;
    /** The earliest retry delay elapses (one timer, armed only while retries wait). */
    case RetryDue = 3;
    /** A mutation is enqueued. */
    case Enqueue = 4;
}
