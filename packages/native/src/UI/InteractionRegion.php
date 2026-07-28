<?php

declare(strict_types=1);

namespace Pam\Native\UI;

use Closure;
use JsonException;
use Pam\Native\Element;
use Pam\Native\EventKind;
use Pam\Native\Internal\BinaryValue;
use Pam\Native\Internal\Wire;
use Pam\Native\NativeMenuItem;
use Pam\Native\NodeKind;
use Pam\Native\PropKey;
use Pam\Native\Renderable;

final class InteractionRegion extends Element
{
    public static function make(Renderable ...$children): self
    {
        return (new self(NodeKind::Pressable))
            ->withChildren(array_map(
                static fn (Renderable $child): Element => $child->toElement(),
                $children,
            ))
            ->withProperty(PropKey::Draggable, false)
            ->withProperty(PropKey::DropEnabled, false);
    }

    public function draggable(string $data, bool $enabled = true): self
    {
        return $this
            ->withProperty(PropKey::Draggable, $enabled)
            ->withProperty(PropKey::DragData, $data);
    }

    public function acceptsDrop(bool $enabled = true): self
    {
        return $this->withProperty(PropKey::DropEnabled, $enabled);
    }

    /** @param list<NativeMenuItem> $items */
    public function contextMenu(array $items): self
    {
        try {
            $encoded = json_encode(
                array_map(
                    static fn (NativeMenuItem $item): array => [
                        'id' => $item->id,
                        'title' => $item->title,
                        'destructive' => $item->destructive,
                        'disabled' => $item->disabled,
                    ],
                    $items,
                ),
                JSON_THROW_ON_ERROR | JSON_UNESCAPED_UNICODE,
            );
        } catch (JsonException $error) {
            throw new \InvalidArgumentException('Native menu items cannot be encoded.', 0, $error);
        }

        return $this->withProperty(
            PropKey::ContextMenuItems,
            new BinaryValue($encoded),
        );
    }

    public function onDragStart(Closure $handler): self
    {
        return $this->withEvent(EventKind::DragStart, $handler);
    }

    public function onDragEnd(Closure $handler): self
    {
        return $this->withEvent(EventKind::DragEnd, $handler);
    }

    /** @param Closure(string): void $handler */
    public function onDrop(Closure $handler): self
    {
        return $this->withEvent(
            EventKind::Drop,
            static fn (string $payload): mixed => $handler(
                (string) (Wire::decodeMap($payload)['data'] ?? ''),
            ),
        );
    }

    /** @param Closure(string): void $handler */
    public function onMenuAction(Closure $handler): self
    {
        return $this->withEvent(
            EventKind::MenuAction,
            static fn (string $payload): mixed => $handler(
                (string) (Wire::decodeMap($payload)['id'] ?? ''),
            ),
        );
    }
}
