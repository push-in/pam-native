<?php

declare(strict_types=1);

namespace Pam\Native\Signals;

/** @internal */
interface ReactiveSource
{
    public function attach(ReactiveObserver $observer): void;

    public function detach(ReactiveObserver $observer): void;
}
