<?php

declare(strict_types=1);

namespace Pam\Native\UI;

use Pam\Native\Element;
use Pam\Native\ModalPresentation;
use Pam\Native\NodeKind;
use Pam\Native\PropKey;
use Pam\Native\Renderable;

final class Modal extends Element
{
    public static function make(
        Renderable $content,
        bool $visible = true,
        ModalPresentation $presentation = ModalPresentation::Dialog,
    ): self {
        return (new self(NodeKind::Modal))
            ->withChildren([$content])
            ->withProperty(PropKey::Visible, $visible)
            ->withProperty(PropKey::ModalPresentation, $presentation->value);
    }
}
