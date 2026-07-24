<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

use Pam\Native\Component;
use Pam\Native\Element;
use Pam\Native\Renderable;

final readonly class CompiledComponentView implements Renderable
{
    public function __construct(
        private Component $component,
        private CompiledTemplateNode $template,
    ) {
    }

    public function toElement(): Element
    {
        $slots = $this->component->__pamSlots();
        $props = PamPhpRegistry::publicProps($this->component);

        return TemplateRenderer::render(
            $this->template,
            $this->component,
            [
                ...$slots,
                'props' => $props,
            ],
        );
    }
}
