<?php

declare(strict_types=1);

namespace Pam\Native\Dom;

use Closure;
use LogicException;
use Pam\Native\AnimationEasing;
use Pam\Native\AnimationPlayState;
use Pam\Native\Element as NativeElement;
use Pam\Native\EventKind;
use Pam\Native\Internal\BinaryValue;
use Pam\Native\MotionPreset;
use Pam\Native\PropKey;
use Pam\Native\Renderable;
use Pam\Native\System\Keyboard;

final class Element
{
    public function __construct(
        private readonly Document $document,
        private readonly string $identity,
    ) {
    }

    public function identity(): string
    {
        return $this->identity;
    }

    public function connected(): bool
    {
        return $this->document->native($this->identity) !== null;
    }

    public function id(): ?string
    {
        return $this->required()->domId();
    }

    /** @return list<string> */
    public function classes(): array
    {
        return $this->required()->domClasses();
    }

    /** @return array<string, string> */
    public function dataset(): array
    {
        return $this->required()->domDataset();
    }

    public function parent(): ?self
    {
        $identity = $this->document->parentIdentity($this->identity);

        return $identity === null ? null : new self($this->document, $identity);
    }

    public function children(): ElementCollection
    {
        return new ElementCollection($this->document, $this->document->childIdentities($this->identity));
    }

    public function firstChild(): ?self
    {
        return $this->children()->first();
    }

    public function lastChild(): ?self
    {
        return $this->children()->last();
    }

    public function nextSibling(): ?self
    {
        return $this->document->sibling($this->identity, 1);
    }

    public function previousSibling(): ?self
    {
        return $this->document->sibling($this->identity, -1);
    }

    public function contains(self $candidate): bool
    {
        return $this->document === $candidate->document
            && $this->document->contains($this->identity, $candidate->identity);
    }

    public function matches(string $selector): bool
    {
        return $this->document->matches($this->identity, $selector);
    }

    public function closest(string $selector): ?self
    {
        $cursor = $this;
        while ($cursor !== null) {
            if ($cursor->matches($selector)) {
                return $cursor;
            }
            $cursor = $cursor->parent();
        }

        return null;
    }

    public function classList(): ClassList
    {
        return new ClassList($this);
    }

    public function style(): StyleDeclaration
    {
        return new StyleDeclaration($this);
    }

    public function prop(
        PropKey $key,
        string|int|float|bool|BinaryValue $value,
    ): self {
        $this->document->replace($this->identity, static fn (NativeElement $element): NativeElement => $element->domWithProperty($key, $value));

        return $this;
    }

    public function property(PropKey $key): string|int|float|bool|BinaryValue|null
    {
        return $this->required()->properties()[$key->value] ?? null;
    }

    public function removeProp(PropKey $key): self
    {
        $this->document->replace($this->identity, static fn (NativeElement $element): NativeElement => $element->domWithoutProperty($key));

        return $this;
    }

    public function text(string $text): self
    {
        return $this->prop(PropKey::Text, $text);
    }

    public function data(string $name, string $value): self
    {
        $this->document->replace($this->identity, static fn (NativeElement $element): NativeElement => $element->data($name, $value));

        return $this;
    }

    public function removeData(string $name): self
    {
        $this->document->replace($this->identity, static fn (NativeElement $element): NativeElement => $element->domWithoutData($name));

        return $this;
    }

    /** @param list<string> $classes */
    public function setClasses(array $classes): self
    {
        $tokens = [];
        foreach ($classes as $class) {
            foreach (preg_split('/\s+/', trim($class), -1, PREG_SPLIT_NO_EMPTY) ?: [] as $token) {
                if (!in_array($token, $tokens, true)) {
                    $tokens[] = $token;
                }
            }
        }
        $this->document->replace($this->identity, static fn (NativeElement $element): NativeElement => $element->domWithClasses($tokens));

        return $this;
    }

    public function on(EventKind $event, Closure $handler): self
    {
        $this->document->replace($this->identity, static fn (NativeElement $element): NativeElement => $element->domWithEvent($event, $handler));

        return $this;
    }

    public function append(Renderable ...$children): self
    {
        $this->document->insert($this->identity, $children, null);

        return $this;
    }

    public function prepend(Renderable ...$children): self
    {
        $this->document->insert($this->identity, $children, 0);

        return $this;
    }

    public function replaceChildren(Renderable ...$children): self
    {
        $this->document->replaceChildren($this->identity, $children);

        return $this;
    }

    public function before(Renderable ...$siblings): self
    {
        $this->document->insertSibling($this->identity, $siblings, false);

        return $this;
    }

    public function after(Renderable ...$siblings): self
    {
        $this->document->insertSibling($this->identity, $siblings, true);

        return $this;
    }

    public function replaceWith(Renderable $replacement): void
    {
        $this->document->replaceWith($this->identity, $replacement);
    }

    public function remove(): void
    {
        $this->document->remove($this->identity);
    }

    public function animate(
        MotionPreset $preset,
        int $durationMs = 240,
        AnimationEasing $easing = AnimationEasing::EaseOut,
    ): self {
        $this->document->replace($this->identity, static fn (NativeElement $element): NativeElement => $element->motion($preset, $durationMs, $easing));

        return $this;
    }

    public function pauseAnimation(): self
    {
        return $this->prop(PropKey::AnimationPlayState, AnimationPlayState::Paused->value);
    }

    public function resumeAnimation(): self
    {
        return $this->prop(PropKey::AnimationPlayState, AnimationPlayState::Running->value);
    }

    public function cancelAnimation(): self
    {
        return $this->prop(PropKey::AnimationPlayState, AnimationPlayState::Stopped->value);
    }

    public function focus(): self
    {
        return $this->prop(PropKey::AutoFocus, true);
    }

    public function blur(): self
    {
        Keyboard::dismiss();

        return $this;
    }

    /**
     * Returns the last UI-thread resize measurement. Call observeResize() first;
     * no synchronous bridge round-trip is performed.
     *
     * @return array{x: float, y: float, width: float, height: float}|null
     */
    public function measure(): ?array
    {
        return $this->document->measurement($this->identity);
    }

    /** @param Closure(array{x: float, y: float, width: float, height: float}): void $handler */
    public function observeResize(Closure $handler): self
    {
        $identity = $this->identity;
        $document = $this->document;

        return $this->on(EventKind::Resize, static function (mixed $payload) use ($identity, $document, $handler): void {
            if (!is_array($payload)) {
                return;
            }
            $measurement = [
                'x' => (float) ($payload['x'] ?? 0.0),
                'y' => (float) ($payload['y'] ?? 0.0),
                'width' => (float) ($payload['width'] ?? 0.0),
                'height' => (float) ($payload['height'] ?? 0.0),
            ];
            $document->recordMeasurement($identity, $measurement);
            $handler($measurement);
        });
    }

    /** @param Closure(mixed): void $handler */
    public function observeIntersection(Closure $handler): self
    {
        return $this->on(EventKind::Intersect, $handler);
    }

    private function required(): NativeElement
    {
        return $this->document->native($this->identity)
            ?? throw new LogicException("DOM node {$this->identity} is detached.");
    }
}
