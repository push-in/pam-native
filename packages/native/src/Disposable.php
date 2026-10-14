<?php

declare(strict_types=1);

namespace Pam\Native;

/**
 * A resource that stops its work when disposed: an event subscription, a
 * component timer, a debouncer. Components release every resource they own
 * on unmount (see Component::own()). Disposing twice is a no-op.
 */
interface Disposable
{
    public function dispose(): void;

    public function disposed(): bool;
}
