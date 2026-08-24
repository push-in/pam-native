<?php

declare(strict_types=1);

namespace Pam\Native\Signals;

/** @internal */
interface ReactiveObserver
{
    public function dependencyChanged(): void;

    public function dependOn(ReactiveSource $source): void;
}
