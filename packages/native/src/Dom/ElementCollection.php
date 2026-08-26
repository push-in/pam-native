<?php

declare(strict_types=1);

namespace Pam\Native\Dom;

use Closure;
use Countable;
use IteratorAggregate;
use Pam\Native\AnimationEasing;
use Pam\Native\EventKind;
use Pam\Native\Internal\BinaryValue;
use Pam\Native\MotionPreset;
use Pam\Native\PropKey;
use Pam\Native\Element as NativeElement;
use Traversable;

/** @implements IteratorAggregate<int, Element> */
final readonly class ElementCollection implements Countable, IteratorAggregate
{
    /** @param list<string> $identities */
    public function __construct(
        private Document $document,
        private array $identities,
    ) {
    }

    public function count(): int
    {
        return count($this->identities);
    }

    public function first(): ?Element
    {
        return isset($this->identities[0]) ? new Element($this->document, $this->identities[0]) : null;
    }

    public function last(): ?Element
    {
        $identity = $this->identities[array_key_last($this->identities)] ?? null;

        return $identity === null ? null : new Element($this->document, $identity);
    }

    public function getIterator(): Traversable
    {
        foreach ($this->identities as $identity) {
            yield new Element($this->document, $identity);
        }
    }

    /** @return list<Element> */
    public function toArray(): array
    {
        return iterator_to_array($this->getIterator(), false);
    }

    /** @param Closure(Element, int): mixed $operation */
    public function each(Closure $operation): self
    {
        foreach ($this as $index => $element) {
            $operation($element, $index);
        }

        return $this;
    }

    /** @param Closure(Element, int): bool $predicate */
    public function filter(Closure $predicate): self
    {
        $identities = [];
        foreach ($this as $index => $element) {
            if ($predicate($element, $index)) {
                $identities[] = $element->identity();
            }
        }

        return new self($this->document, $identities);
    }

    public function addClass(string ...$classes): self
    {
        $this->document->replaceMany($this->identities, static fn (NativeElement $element): NativeElement => $element->class(...$classes));

        return $this;
    }

    public function removeClass(string ...$classes): self
    {
        $this->document->replaceMany($this->identities, static fn (NativeElement $element): NativeElement => $element->domWithClasses(array_values(array_filter(
            $element->domClasses(),
            static fn (string $class): bool => !in_array($class, $classes, true),
        ))));

        return $this;
    }

    public function style(string|PropKey $property, string|int|float|bool|BinaryValue $value): self
    {
        $key = StyleDeclaration::resolve($property);
        $this->document->replaceMany($this->identities, static fn (NativeElement $element): NativeElement => $element->domWithProperty($key, $value));

        return $this;
    }

    public function on(EventKind $event, Closure $handler): self
    {
        $this->document->replaceMany($this->identities, static fn (NativeElement $element): NativeElement => $element->domWithEvent($event, $handler));

        return $this;
    }

    public function animate(MotionPreset $preset, int $durationMs = 240, AnimationEasing $easing = AnimationEasing::EaseOut): self
    {
        $this->document->replaceMany($this->identities, static fn (NativeElement $element): NativeElement => $element->motion($preset, $durationMs, $easing));

        return $this;
    }

    public function remove(): void
    {
        $this->document->transaction(function (): void {
            foreach (array_reverse($this->identities) as $identity) {
                if ($this->document->native($identity) !== null) {
                    (new Element($this->document, $identity))->remove();
                }
            }
        });
    }

    /** @param Closure(Element): mixed $operation */
    private function batch(Closure $operation): self
    {
        $this->document->transaction(function () use ($operation): void {
            foreach ($this as $element) {
                $operation($element);
            }
        });

        return $this;
    }
}
