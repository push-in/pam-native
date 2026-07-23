<?php

declare(strict_types=1);

namespace Pam\Native;

use Closure;
use LogicException;

final readonly class FunctionalComponent implements Renderable
{
    /** @param Closure(): Renderable $render */
    public function __construct(private Closure $render)
    {
    }

    /** @param Closure(): Renderable $render */
    public static function make(Closure $render): self
    {
        return new self($render);
    }

    public function toElement(): Element
    {
        $rendered = ($this->render)();

        if ($rendered === $this) {
            throw new LogicException('A functional component cannot render itself.');
        }

        return $rendered->toElement();
    }
}
