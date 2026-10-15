<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

use Closure;
use InvalidArgumentException;
use Pam\Native\Element;
use Pam\Native\NodeKind;
use Pam\Native\PropKey;
use Pam\Native\Renderable;
use Pam\Native\UI\View;

/**
 * @internal The rows of a data-driven list (VirtualizedList::of()): the
 * items, their keys, and the row builder, called only for the rows the
 * encoders materialize (the window). A built row is kept for the life of
 * this object, so re-encoding the same list never rebuilds it.
 */
final class LazyRows
{
    /** @var array<int, Element|false> */
    private array $built = [];

    /**
     * @param list<mixed> $items
     * @param list<string> $keys one unique key per item
     * @param Closure(mixed, int): (Renderable|false|null) $render
     * @param (Closure(Element): Element)|null $transform applied to each built row
     */
    public function __construct(
        public readonly array $items,
        public readonly array $keys,
        private readonly Closure $render,
        private readonly ?Closure $transform = null,
    ) {
    }

    /**
     * The same rows with $transform applied to each one when it is built
     * (theme defaults reach rows that do not exist yet).
     *
     * @param Closure(Element): Element $transform
     */
    public function mapped(Closure $transform): self
    {
        $previous = $this->transform;

        return new self(
            $this->items,
            $this->keys,
            $this->render,
            $previous === null ? $transform : static fn (Element $row): Element => $transform($previous($row)),
        );
    }

    public function count(): int
    {
        return count($this->keys);
    }

    /** @var array<int, Element> */
    private array $placeholders = [];

    /**
     * The stand-in of a row that is not built: a childless View keyed like
     * the row and flagged ListPlaceholder (sized by the list estimate).
     */
    public function placeholder(int $index): Element
    {
        return $this->placeholders[$index] ??= View::__pamCreate(
            NodeKind::View,
            [],
            [PropKey::ListPlaceholder->value => true],
            null,
            $this->keys[$index],
        );
    }

    /** The row at $index (keyed with its item key), or null for a hole. */
    public function row(int $index): ?Element
    {
        $built = $this->built[$index] ?? null;
        if ($built !== null) {
            return $built === false ? null : $built;
        }
        $rendered = ($this->render)($this->items[$index], $index);
        if ($rendered === null || $rendered === false) {
            $this->built[$index] = false;

            return null;
        }
        if (!$rendered instanceof Renderable) {
            throw new InvalidArgumentException('A list row must be renderable by Pam Native.');
        }
        $element = $rendered->toElement();
        if ($element->elementKey !== $this->keys[$index]) {
            $element = $element->key($this->keys[$index]);
        }
        if ($this->transform !== null) {
            $element = ($this->transform)($element);
        }

        return $this->built[$index] = $element;
    }
}
