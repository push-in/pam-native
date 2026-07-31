<?php

declare(strict_types=1);

namespace Pam\Native\UI;

use InvalidArgumentException;
use Pam\Native\Canvas\CanvasCommandKind;
use Pam\Native\Element;
use Pam\Native\NodeKind;
use Pam\Native\PropKey;

final class Canvas extends Element
{
    private const MAX_COMMANDS = 10_000;
    /** @var list<array<string, int|float>> */
    private array $commands = [];

    public static function make(): self
    {
        return (new self(NodeKind::Canvas))->sync();
    }

    public function rectangle(float $x, float $y, float $width, float $height, int $color): self
    {
        return $this->command(CanvasCommandKind::Rectangle, compact('x', 'y', 'width', 'height', 'color'));
    }

    public function roundedRectangle(
        float $x,
        float $y,
        float $width,
        float $height,
        float $radius,
        int $color,
    ): self {
        return $this->command(
            CanvasCommandKind::RoundedRectangle,
            compact('x', 'y', 'width', 'height', 'radius', 'color'),
        );
    }

    public function circle(float $centerX, float $centerY, float $radius, int $color): self
    {
        return $this->command(CanvasCommandKind::Circle, compact('centerX', 'centerY', 'radius', 'color'));
    }

    public function line(
        float $startX,
        float $startY,
        float $endX,
        float $endY,
        float $width,
        int $color,
    ): self {
        return $this->command(CanvasCommandKind::Line, compact('startX', 'startY', 'endX', 'endY', 'width', 'color'));
    }

    /** @param array<string, int|float> $values */
    private function command(CanvasCommandKind $kind, array $values): self
    {
        if (count($this->commands) >= self::MAX_COMMANDS) {
            throw new InvalidArgumentException('Canvas cannot exceed 10,000 commands.');
        }
        foreach ($values as $name => $value) {
            if (is_float($value) && !is_finite($value)) {
                throw new InvalidArgumentException("Canvas {$name} must be finite.");
            }
        }
        $copy = clone $this;
        $copy->commands[] = ['kind' => $kind->value, ...$values];
        return $copy->sync();
    }

    private function sync(): self
    {
        $json = json_encode($this->commands, JSON_THROW_ON_ERROR);
        if (strlen($json) > 1_000_000) {
            throw new InvalidArgumentException('Canvas command payload exceeds 1 MiB.');
        }
        return $this->withProperty(PropKey::CanvasCommands, $json);
    }
}
