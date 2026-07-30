<?php

declare(strict_types=1);

namespace Pam\Native\UI;

use Closure;
use Pam\Native\DrawingMode;
use Pam\Native\Element;
use Pam\Native\EventKind;
use Pam\Native\NodeKind;
use Pam\Native\PropKey;

final class DrawingCanvas extends Element
{
    public static function make(string $source, string $drawing = ''): self
    {
        return (new self(NodeKind::DrawingCanvas))
            ->withProperty(PropKey::Source, $source)
            ->withProperty(PropKey::Value, $drawing)
            ->withProperty(PropKey::ImageFit, 2)
            ->withProperty(PropKey::DrawingColor, 0xFFFFFFFF)
            ->withProperty(PropKey::DrawingWidth, 6.0)
            ->withProperty(PropKey::DrawingMode, DrawingMode::Brush->value)
            ->withProperty(PropKey::Enabled, true);
    }

    public function drawing(string $drawing): self
    {
        return $this->withProperty(PropKey::Value, $drawing);
    }

    public function brush(
        int $color,
        float $width = 6.0,
        DrawingMode $mode = DrawingMode::Brush,
    ): self {
        return $this
            ->withProperty(PropKey::DrawingColor, $color)
            ->withProperty(PropKey::DrawingWidth, max(1.0, min(64.0, $width)))
            ->withProperty(PropKey::DrawingMode, $mode->value);
    }

    public function clearRequest(int $request): self
    {
        return $this->withProperty(PropKey::DrawingClearRequest, max(0, $request));
    }

    public function undoRequest(int $request): self
    {
        return $this->withProperty(PropKey::DrawingUndoRequest, max(0, $request));
    }

    /** @param Closure(string): void $handler */
    public function onChange(Closure $handler): self
    {
        return $this->withEvent(EventKind::Change, $handler);
    }
}
