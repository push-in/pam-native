<?php

declare(strict_types=1);

namespace Pam\Native\Testing;

use LogicException;
use Pam\Native\Component;
use Pam\Native\Element;
use Pam\Native\NodeKind;
use Pam\Native\PropKey;
use Pam\Native\Renderable;

/**
 * A component mounted by Testing\mount(): its lifecycle runs like on a
 * device. `render()` runs another render pass, `focus()`/`blur()` act like
 * the navigator, `unmount()` releases what setup() created.
 *
 * @template T of Renderable
 */
final class Mounted
{
    private ?Element $element = null;

    /** @param T $instance */
    public function __construct(public readonly Renderable $instance)
    {
    }

    /** @internal Renders this root inside the harness pass. */
    public function renderElement(): Element
    {
        return $this->element = $this->instance->toElement();
    }

    /** Runs a render pass (signals changed, props changed...) and returns this root's element. */
    public function render(): Element
    {
        $this->assertMounted();
        Harness::render();

        return $this->element();
    }

    /** The element of the last render pass. */
    public function element(): Element
    {
        return $this->element ?? throw new LogicException('The component has not rendered yet.');
    }

    /** Every Text of the last render, joined by a space (quick content assertions). */
    public function text(): string
    {
        return self::textOf($this->element());
    }

    /** @internal Every Text under $element, joined by a space. */
    public static function textOf(Element $element): string
    {
        $texts = [];
        self::collectText($element, $texts);

        return implode(' ', $texts);
    }

    /** The screen became visible (onFocus hooks), as the navigator reports it. */
    public function focus(): static
    {
        $this->navigationFocus(true);

        return $this;
    }

    /** The screen was covered or left (onBlur hooks). */
    public function blur(): static
    {
        $this->navigationFocus(false);

        return $this;
    }

    /** Unmounts: unmount(), onUnmounted() hooks, then setup() scopes, own() resources and timers are released. */
    public function unmount(): void
    {
        Harness::unmount($this);
    }

    public function mounted(): bool
    {
        return Harness::mounted($this);
    }

    private function navigationFocus(bool $focused): void
    {
        $this->assertMounted();
        if (!$this->instance instanceof Component) {
            throw new LogicException('focus()/blur() need a Component root.');
        }
        $this->instance->__pamNavigationFocus($focused);
        Harness::render();
    }

    private function assertMounted(): void
    {
        if (!$this->mounted()) throw new LogicException('The component is not mounted.');
    }

    /** @param list<string> $texts */
    private static function collectText(Element $element, array &$texts): void
    {
        if ($element->kind === NodeKind::Text) {
            $text = $element->properties[PropKey::Text->value] ?? null;
            if (is_string($text) && $text !== '') $texts[] = $text;
        }
        foreach ($element->children as $child) {
            if ($child instanceof Element) self::collectText($child, $texts);
        }
    }
}
