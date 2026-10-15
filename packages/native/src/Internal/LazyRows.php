<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

use Closure;
use InvalidArgumentException;
use Pam\Native\Component;
use Pam\Native\Element;
use Pam\Native\NodeKind;
use Pam\Native\PropKey;
use Pam\Native\Renderable;
use Pam\Native\UI\View;
use WeakReference;

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

    /** @var array<int, list<Component>> components each built row rendered */
    private array $components = [];

    /** @var array<int, true> rows asked for since the last settle() */
    private array $touched = [];

    /** @var WeakReference<Component>|null the component whose render created these rows */
    private ?WeakReference $owner;

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
        ?Component $owner = null,
    ) {
        $owner ??= ComponentLifecycle::current();
        $this->owner = $owner === null ? null : WeakReference::create($owner);
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
            $this->owner?->get(),
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
        $this->touched[$index] = true;
        $built = $this->built[$index] ?? null;
        if ($built !== null) {
            return $built === false ? null : $built;
        }
        // Rendered as part of the owner's render: its components mount as
        // the owner's children and state reads subscribe the owner.
        $render = $this->render;
        $item = $this->items[$index];
        [$element, $components] = ComponentLifecycle::renderFor(
            $this->owner?->get(),
            static function () use ($render, $item, $index): ?Element {
                $rendered = $render($item, $index);
                if ($rendered === null || $rendered === false) {
                    return null;
                }
                if (!$rendered instanceof Renderable) {
                    throw new InvalidArgumentException('A list row must be renderable by Pam Native.');
                }

                return $rendered->toElement();
            },
        );
        if ($components !== []) {
            $this->components[$index] = $components;
        }
        if ($element === null) {
            $this->built[$index] = false;

            return null;
        }
        if ($element->elementKey !== $this->keys[$index]) {
            $element = $element->key($this->keys[$index]);
        }
        if ($this->transform !== null) {
            $element = ($this->transform)($element);
        }

        return $this->built[$index] = $element;
    }

    /**
     * Called by the encoders after they encoded the list: rows built before
     * but not asked for this time left the window, so they are dropped and
     * their components unmount (their instances stay keyed with the owner
     * while it is reused, so state survives a return to the window).
     */
    public function settle(): void
    {
        if (count($this->touched) !== count($this->built)) {
            $owner = $this->owner?->get();
            foreach ($this->built as $index => $_) {
                if (isset($this->touched[$index])) {
                    continue;
                }
                unset($this->built[$index]);
                $components = $this->components[$index] ?? null;
                if ($components !== null) {
                    unset($this->components[$index]);
                    ComponentLifecycle::release($owner, $components);
                }
            }
        }
        $this->touched = [];
    }
}
