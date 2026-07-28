<?php

declare(strict_types=1);

namespace Pam\Native\Scheduling;

use RuntimeException;

final class TaskCancelledException extends RuntimeException
{
    public function __construct()
    {
        parent::__construct('Scheduled task was cancelled.');
    }
}
