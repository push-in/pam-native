<?php

declare(strict_types=1);

namespace Pam\Native\Dom;

final class ClassList
{
    public function __construct(private readonly Element $element)
    {
    }

    public function add(string ...$classes): Element
    {
        return $this->element->setClasses([...$this->element->classes(), ...$classes]);
    }

    public function remove(string ...$classes): Element
    {
        return $this->element->setClasses(array_values(array_filter(
            $this->element->classes(),
            static fn (string $class): bool => !in_array($class, $classes, true),
        )));
    }

    public function toggle(string $class, ?bool $force = null): Element
    {
        $contains = $this->contains($class);
        $enabled = $force ?? !$contains;

        return $enabled ? $this->add($class) : $this->remove($class);
    }

    public function replace(string $old, string $new): Element
    {
        return $this->remove($old)->classList()->add($new);
    }

    public function contains(string $class): bool
    {
        return in_array($class, $this->element->classes(), true);
    }
}
