<?php

declare(strict_types=1);

namespace Pam\Native;

use LogicException;
use Pam\Native\Attributes\Expose;
use ReflectionMethod;
use WeakReference;

final class ComponentRef
{
    private ?WeakReference $target = null;

    public function attach(Component $component): void
    {
        $this->target = WeakReference::create($component);
    }

    public function detach(): void
    {
        $this->target = null;
    }

    public function call(string $method, mixed ...$arguments): mixed
    {
        $component = $this->target?->get();
        if (!$component instanceof Component) {
            throw new LogicException('Component ref is not attached.');
        }
        if (!method_exists($component, $method)) {
            throw new LogicException("Unknown component method {$method}.");
        }
        $reflection = new ReflectionMethod($component, $method);
        if (!$reflection->isPublic() || $reflection->getAttributes(Expose::class) === []) {
            throw new LogicException("Component method {$method} is not exposed.");
        }

        return $reflection->invokeArgs($component, $arguments);
    }
}
