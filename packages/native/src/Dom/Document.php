<?php

declare(strict_types=1);

namespace Pam\Native\Dom;

use Closure;
use InvalidArgumentException;
use LogicException;
use Pam\Native\Element as NativeElement;
use Pam\Native\Internal\Runtime;
use Pam\Native\Renderable;
use Throwable;

final class Document implements Renderable
{
    private NativeElement $root;
    private int $nextIdentity = 1;
    private int $transactionDepth = 0;
    private bool $dirty = false;
    private int $mutationVersion = 0;
    private int $nextObserver = 1;
    /** @var array<string, true> */
    private array $touched = [];
    /** @var array<int, array{selector: ?string, callback: Closure}> */
    private array $observers = [];
    /** @var array<string, array{x: float, y: float, width: float, height: float}> */
    private array $measurements = [];

    /** @var array<string, NativeElement> */
    private array $nodes = [];
    /** @var array<string, ?string> */
    private array $parents = [];
    /** @var array<string, list<string>> */
    private array $children = [];
    /** @var array<string, string> */
    private array $ids = [];
    /** @var array<string, list<string>> */
    private array $classes = [];
    /** @var array<string, list<string>> */
    private array $kinds = [];
    /** @var array<string, array<string, list<string>>> */
    private array $dataValues = [];
    /** @var array<string, Selector> */
    private array $selectorCache = [];

    private function __construct(Renderable $root)
    {
        // A Document owns its identity namespace. Importing a tree from another
        // document must never preserve handles that could collide on insertion.
        $this->root = $this->normalize($root->toElement(), true);
        $this->reindex();
    }

    public static function from(Renderable $root): self
    {
        return new self($root);
    }

    public function toElement(): NativeElement
    {
        return $this->root;
    }

    public function root(): Element
    {
        return new Element($this, $this->root->domIdentity() ?? throw new LogicException('DOM root has no identity.'));
    }

    public function getElementById(string $id): ?Element
    {
        $identity = $this->ids[$id] ?? null;

        return $identity === null ? null : new Element($this, $identity);
    }

    public function id(string $id): Element
    {
        return $this->getElementById($id) ?? throw new LogicException("DOM element #{$id} was not found.");
    }

    public function querySelector(string $selector): ?Element
    {
        return $this->querySelectorAll($selector)->first();
    }

    public function querySelectorAll(string $selector): ElementCollection
    {
        $compiled = $this->selector($selector);
        $candidates = $this->candidateIdentities($selector);
        $matches = [];
        foreach ($candidates as $identity) {
            $element = $this->nodes[$identity] ?? null;
            if ($element !== null && $compiled->matches($element, $this->parentElement(...))) {
                $matches[] = $identity;
            }
        }

        return new ElementCollection($this, $matches);
    }

    public function all(string $selector): ElementCollection
    {
        return $this->querySelectorAll($selector);
    }

    public function snapshot(): DocumentSnapshot
    {
        return new DocumentSnapshot(
            rootIdentity: $this->root->domIdentity() ?? throw new LogicException('DOM root has no identity.'),
            nodeCount: count($this->nodes),
            idCount: count($this->ids),
            classCount: count($this->classes),
            cachedSelectorCount: count($this->selectorCache),
            mutationVersion: $this->mutationVersion,
            transactionDepth: $this->transactionDepth,
        );
    }

    public function transaction(Closure $operation): mixed
    {
        $snapshot = $this->root;
        $wasDirty = $this->dirty;
        $previousTouched = $this->touched;
        $this->transactionDepth++;
        try {
            return $operation($this);
        } catch (Throwable $error) {
            $this->root = $snapshot;
            $this->dirty = $wasDirty;
            $this->touched = $previousTouched;
            $this->reindex();

            throw $error;
        } finally {
            $this->transactionDepth--;
            if ($this->transactionDepth === 0 && $this->dirty) {
                $this->commitChanges();
            }
        }
    }

    public function update(Closure $operation): mixed
    {
        return $this->transaction($operation);
    }

    /** @param Closure(MutationRecord): void $callback */
    public function observe(Closure $callback, ?string $selector = null): MutationObserver
    {
        if ($selector !== null) {
            $this->selector($selector);
        }
        $id = $this->nextObserver++;
        $this->observers[$id] = ['selector' => $selector, 'callback' => $callback];

        return new MutationObserver($this, $id);
    }

    /** @internal */
    public function disconnectObserver(int $id): void
    {
        unset($this->observers[$id]);
    }

    /** @param array{x: float, y: float, width: float, height: float} $measurement */
    public function recordMeasurement(string $identity, array $measurement): void
    {
        $this->measurements[$identity] = $measurement;
    }

    /** @return array{x: float, y: float, width: float, height: float}|null */
    public function measurement(string $identity): ?array
    {
        return $this->measurements[$identity] ?? null;
    }

    public function native(string $identity): ?NativeElement
    {
        return $this->nodes[$identity] ?? null;
    }

    public function parentIdentity(string $identity): ?string
    {
        return $this->parents[$identity] ?? null;
    }

    /** @return list<string> */
    public function childIdentities(string $identity): array
    {
        return $this->children[$identity] ?? [];
    }

    public function sibling(string $identity, int $offset): ?Element
    {
        $parent = $this->parentIdentity($identity);
        if ($parent === null) {
            return null;
        }
        $siblings = $this->childIdentities($parent);
        $index = array_search($identity, $siblings, true);
        $candidate = $index === false ? null : ($siblings[$index + $offset] ?? null);

        return $candidate === null ? null : new Element($this, $candidate);
    }

    public function contains(string $ancestor, string $candidate): bool
    {
        $cursor = $candidate;
        while (($cursor = $this->parentIdentity($cursor)) !== null) {
            if ($cursor === $ancestor) {
                return true;
            }
        }

        return false;
    }

    public function matches(string $identity, string $selector): bool
    {
        $element = $this->nodes[$identity] ?? null;

        return $element !== null && $this->selector($selector)->matches($element, $this->parentElement(...));
    }

    /** @param Closure(NativeElement): NativeElement $operation */
    public function replace(string $identity, Closure $operation): void
    {
        if (!isset($this->nodes[$identity])) {
            throw new LogicException("DOM node {$identity} is detached.");
        }
        $this->root = $this->transform($this->root, $identity, $operation);
        $this->changed($identity);
    }

    /** @param list<string> $identities @param Closure(NativeElement): NativeElement $operation */
    public function replaceMany(array $identities, Closure $operation): void
    {
        $targets = array_fill_keys($identities, true);
        if ($targets === []) {
            return;
        }
        $this->root = $this->transformMany($this->root, $targets, $operation);
        $this->reindex();
        $this->dirty = true;
        foreach ($targets as $identity => $_) {
            $this->touched[$identity] = true;
        }
        if ($this->transactionDepth === 0) {
            $this->commitChanges();
        }
    }

    /** @param list<Renderable> $children */
    public function insert(string $parent, array $children, ?int $index): void
    {
        $normalized = array_map(fn (Renderable $child): NativeElement => $this->normalize($child->toElement(), true), $children);
        $this->replace($parent, static function (NativeElement $element) use ($normalized, $index): NativeElement {
            $existing = $element->children();
            $position = $index === null ? count($existing) : max(0, min(count($existing), $index));
            array_splice($existing, $position, 0, $normalized);

            return $element->domWithChildren($existing);
        });
    }

    /** @param list<Renderable> $children */
    public function replaceChildren(string $parent, array $children): void
    {
        $normalized = array_map(fn (Renderable $child): NativeElement => $this->normalize($child->toElement(), true), $children);
        $this->replace($parent, static fn (NativeElement $element): NativeElement => $element->domWithChildren($normalized));
    }

    /** @param list<Renderable> $siblings */
    public function insertSibling(string $identity, array $siblings, bool $after): void
    {
        $parent = $this->parentIdentity($identity) ?? throw new LogicException('The DOM root cannot have siblings.');
        $children = $this->childIdentities($parent);
        $index = array_search($identity, $children, true);
        if ($index === false) {
            throw new LogicException("DOM node {$identity} is detached.");
        }
        $this->insert($parent, $siblings, $index + ($after ? 1 : 0));
    }

    public function replaceWith(string $identity, Renderable $replacement): void
    {
        $parent = $this->parentIdentity($identity) ?? throw new LogicException('The DOM root cannot be replaced through replaceWith().');
        $siblings = $this->childIdentities($parent);
        $index = array_search($identity, $siblings, true);
        $this->transaction(function () use ($identity, $parent, $replacement, $index): void {
            $this->remove($identity);
            $this->insert($parent, [$replacement], is_int($index) ? $index : null);
        });
    }

    public function remove(string $identity): void
    {
        $parent = $this->parentIdentity($identity) ?? throw new LogicException('The DOM root cannot be removed.');
        $this->replace($parent, static fn (NativeElement $element): NativeElement => $element->domWithChildren(array_values(array_filter(
            $element->children(),
            static fn (NativeElement $child): bool => $child->domIdentity() !== $identity,
        ))));
    }

    private function changed(string $identity): void
    {
        $this->reindex();
        $this->dirty = true;
        $this->touched[$identity] = true;
        if ($this->transactionDepth === 0) {
            $this->commitChanges();
        }
    }

    private function commitChanges(): void
    {
        $this->dirty = false;
        $identities = array_keys($this->touched);
        $this->touched = [];
        $record = new MutationRecord(++$this->mutationVersion, $identities);
        foreach ($this->observers as $observer) {
            $selector = $observer['selector'];
            if ($selector !== null && !array_any(
                $identities,
                fn (string $identity): bool => isset($this->nodes[$identity]) && $this->matches($identity, $selector),
            )) {
                continue;
            }
            try {
                ($observer['callback'])($record);
            } catch (Throwable $error) {
                Runtime::reportError($error);
            }
        }
        Runtime::requestRender();
    }

    private function normalize(NativeElement $element, bool $fresh): NativeElement
    {
        $identity = !$fresh && $element->domIdentity() !== null
            ? $element->domIdentity()
            : 'n'.$this->nextIdentity++;
        $children = array_map(fn (NativeElement $child): NativeElement => $this->normalize($child, $fresh), $element->children());

        return $element->domWithIdentity($identity)->domWithChildren($children);
    }

    /** @param Closure(NativeElement): NativeElement $operation */
    private function transform(NativeElement $element, string $identity, Closure $operation): NativeElement
    {
        if ($element->domIdentity() === $identity) {
            return $operation($element);
        }
        $changed = false;
        $children = [];
        foreach ($element->children() as $child) {
            $next = $this->transform($child, $identity, $operation);
            $changed = $changed || $next !== $child;
            $children[] = $next;
        }

        return $changed ? $element->domWithChildren($children) : $element;
    }

    /** @param array<string, true> $targets @param Closure(NativeElement): NativeElement $operation */
    private function transformMany(NativeElement $element, array $targets, Closure $operation): NativeElement
    {
        $current = isset($targets[$element->domIdentity() ?? '']) ? $operation($element) : $element;
        $changed = $current !== $element;
        $children = [];
        foreach ($current->children() as $child) {
            $next = $this->transformMany($child, $targets, $operation);
            $changed = $changed || $next !== $child;
            $children[] = $next;
        }

        return $changed ? $current->domWithChildren($children) : $current;
    }

    private function reindex(): void
    {
        $this->nodes = $this->parents = $this->children = $this->ids = $this->classes = $this->kinds = $this->dataValues = [];
        $this->indexNode($this->root, null);
    }

    private function indexNode(NativeElement $element, ?string $parent): void
    {
        $identity = $element->domIdentity() ?? throw new LogicException('DOM node is missing its identity.');
        if (isset($this->nodes[$identity])) {
            throw new LogicException("Duplicate DOM identity {$identity}.");
        }
        $this->nodes[$identity] = $element;
        $this->parents[$identity] = $parent;
        $this->kinds[strtolower($element->kind()->name)][] = $identity;
        if (($id = $element->domId()) !== null) {
            if (isset($this->ids[$id])) {
                throw new LogicException("Duplicate DOM id {$id}.");
            }
            $this->ids[$id] = $identity;
        }
        foreach ($element->domClasses() as $class) {
            $this->classes[$class][] = $identity;
        }
        foreach ($element->domDataset() as $name => $value) {
            $this->dataValues[$name][$value][] = $identity;
        }
        foreach ($element->children() as $child) {
            $childIdentity = $child->domIdentity() ?? throw new LogicException('DOM child is missing its identity.');
            $this->children[$identity][] = $childIdentity;
            $this->indexNode($child, $identity);
        }
        $this->children[$identity] ??= [];
    }

    private function selector(string $source): Selector
    {
        if (count($this->selectorCache) >= 256 && !isset($this->selectorCache[$source])) {
            array_shift($this->selectorCache);
        }

        return $this->selectorCache[$source] ??= Selector::compile($source);
    }

    /** @return list<string> */
    private function candidateIdentities(string $selector): array
    {
        // Only index the right-most compound.  An id/class/type on an ancestor
        // constrains the relationship, not the nodes that can be returned.
        preg_match('/(?:^|[ >])([^ >]+)\s*$/', trim($selector), $rightmost);
        $compound = $rightmost[1] ?? '';
        if (preg_match('/\[data-([a-z][a-z0-9-]{0,63})=(?:"([^"]*)"|\'([^\']*)\'|([^\]]+))\]/', $compound, $data) === 1) {
            $value = $data[2] !== '' ? $data[2] : ($data[3] !== '' ? $data[3] : trim($data[4] ?? ''));
            return $this->dataValues[$data[1]][$value] ?? [];
        }
        if (preg_match('/#([A-Za-z][A-Za-z0-9_.:-]{0,127})(?![A-Za-z0-9_.:-])/', $compound, $id) === 1) {
            return isset($this->ids[$id[1]]) ? [$this->ids[$id[1]]] : [];
        }
        if (preg_match('/\.([A-Za-z_][A-Za-z0-9_-]{0,127})(?![A-Za-z0-9_-])/', $compound, $class) === 1) {
            return $this->classes[$class[1]] ?? [];
        }
        if (preg_match('/^([A-Za-z][A-Za-z0-9-]*)(?=[.#\[]|$)/', $compound, $kind) === 1) {
            return $this->kinds[strtolower($kind[1])] ?? [];
        }

        return array_keys($this->nodes);
    }

    private function parentElement(string $identity): ?NativeElement
    {
        $parent = $this->parents[$identity] ?? null;

        return $parent === null ? null : ($this->nodes[$parent] ?? null);
    }
}
