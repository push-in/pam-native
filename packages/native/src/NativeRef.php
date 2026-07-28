<?php

declare(strict_types=1);

namespace Pam\Native;

use Closure;
use LogicException;

final class NativeRef
{
    /** @var array<string, Closure>|null */
    private ?array $operations = null;

    /** @param array<string, Closure> $operations */
    public function attach(array $operations): void
    {
        foreach ($operations as $name => $operation) {
            if (preg_match('/^[A-Za-z][A-Za-z0-9_]{0,63}$/D', $name) !== 1) {
                throw new LogicException('Native ref operation names must be safe identifiers.');
            }
        }
        $this->operations = $operations;
    }

    public function detach(): void
    {
        $this->operations = null;
    }

    public function attached(): bool
    {
        return $this->operations !== null;
    }

    public function focus(): void
    {
        $this->invoke('focus');
    }

    public function blur(): void
    {
        $this->invoke('blur');
    }

    /** @return array{x: float, y: float, width: float, height: float} */
    public function measure(): array
    {
        $result = $this->invoke('measure');
        if (!is_array($result)) {
            throw new LogicException('Native ref measure operation returned an invalid result.');
        }

        return $result;
    }

    public function scrollIntoView(): void
    {
        $this->invoke('scrollIntoView');
    }

    public function call(string $operation, mixed ...$arguments): mixed
    {
        return $this->invoke($operation, $arguments);
    }

    /** @param list<mixed> $arguments */
    private function invoke(string $operation, array $arguments = []): mixed
    {
        $callback = $this->operations[$operation] ?? null;
        if ($callback === null) {
            throw new LogicException("Native ref operation {$operation} is unavailable or detached.");
        }

        return $callback(...$arguments);
    }
}
