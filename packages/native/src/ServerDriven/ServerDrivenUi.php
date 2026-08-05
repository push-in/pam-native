<?php

declare(strict_types=1);

namespace Pam\Native\ServerDriven;

use Closure;
use InvalidArgumentException;
use JsonException;
use Pam\Native\Renderable;
use Pam\Native\Style;
use Pam\Native\UI\Button;
use Pam\Native\UI\Column;
use Pam\Native\UI\Image;
use Pam\Native\UI\Row;
use Pam\Native\UI\Scroll;
use Pam\Native\UI\Spacer;
use Pam\Native\UI\Text;
use Pam\Native\UI\View;

final class ServerDrivenUi
{
    private const MAX_BYTES = 1_048_576;
    private const MAX_NODES = 10_000;
    private const MAX_DEPTH = 64;
    private const STYLE_KEYS = [
        'width' => true,
        'height' => true,
        'flexGrow' => true,
        'padding' => true,
        'paddingHorizontal' => true,
        'paddingVertical' => true,
        'gap' => true,
        'backgroundColor' => true,
        'textColor' => true,
        'fontSize' => true,
        'fontWeight' => true,
        'borderRadius' => true,
        'borderWidth' => true,
        'borderColor' => true,
        'borderStyle' => true,
        'opacity' => true,
    ];

    private int $nodes = 0;

    /** @param Closure(string): (Closure(): void)|null $actions */
    private function __construct(private readonly Closure $actions)
    {
    }

    /** @param Closure(string): (Closure(): void)|null $actions */
    public static function render(string $json, Closure $actions): Renderable
    {
        if ($json === '' || strlen($json) > self::MAX_BYTES) {
            throw new InvalidArgumentException('Server-driven UI document must be between 1 byte and 1 MiB.');
        }
        try {
            $document = json_decode($json, true, self::MAX_DEPTH, JSON_THROW_ON_ERROR);
        } catch (JsonException $error) {
            throw new InvalidArgumentException('Server-driven UI document is invalid JSON.', previous: $error);
        }
        if (!is_array($document) || ($document['version'] ?? null) !== 1 || !is_array($document['root'] ?? null)) {
            throw new InvalidArgumentException('Server-driven UI document contract is invalid.');
        }
        return (new self($actions))->node($document['root'], 1);
    }

    /** @param array<array-key, mixed> $node */
    private function node(array $node, int $depth): Renderable
    {
        if (++$this->nodes > self::MAX_NODES || $depth > self::MAX_DEPTH) {
            throw new InvalidArgumentException('Server-driven UI tree exceeds its node or depth limit.');
        }
        $kind = is_int($node['kind'] ?? null) ? ServerNodeKind::tryFrom($node['kind']) : null;
        if ($kind === null) {
            throw new InvalidArgumentException('Server-driven UI node kind is unknown.');
        }
        $props = is_array($node['props'] ?? null) ? $node['props'] : [];
        $children = [];
        if (isset($node['children'])) {
            if (!is_array($node['children']) || count($node['children']) > self::MAX_NODES) {
                throw new InvalidArgumentException('Server-driven UI children must be a bounded list.');
            }
            foreach ($node['children'] as $child) {
                if (!is_array($child)) {
                    throw new InvalidArgumentException('Server-driven UI child must be an object.');
                }
                $children[] = $this->node($child, $depth + 1);
            }
        }

        $renderable = match ($kind) {
            ServerNodeKind::View => View::make(...$children),
            ServerNodeKind::Column => Column::make(...$children),
            ServerNodeKind::Row => Row::make(...$children),
            ServerNodeKind::Text => Text::make(self::text($props, 'text')),
            ServerNodeKind::Button => $this->button($props),
            ServerNodeKind::Image => Image::make(self::text($props, 'source')),
            ServerNodeKind::Scroll => Scroll::make(
                count($children) === 1 ? $children[0] : Column::make(...$children),
            ),
            ServerNodeKind::Spacer => Spacer::make(self::number($props, 'size', 8)),
        };

        $style = $props['style'] ?? null;
        if ($style !== null) {
            if (!is_array($style)) {
                throw new InvalidArgumentException('Server-driven UI style must be an object.');
            }
            $validated = [];
            foreach ($style as $name => $value) {
                if (!is_string($name) || !isset(self::STYLE_KEYS[$name]) || (!is_int($value) && !is_float($value))) {
                    throw new InvalidArgumentException('Server-driven UI style contains an unsupported property.');
                }
                $validated[$name] = $value;
            }
            $renderable = $renderable->style(new Style(...$validated));
        }
        return $renderable;
    }

    /** @param array<array-key, mixed> $props */
    private function button(array $props): Button
    {
        $button = Button::make(self::text($props, 'label'));
        if (isset($props['action'])) {
            $action = self::text($props, 'action');
            if (preg_match('/^[A-Za-z][A-Za-z0-9_.:-]{0,127}$/', $action) !== 1) {
                throw new InvalidArgumentException('Server-driven UI action name is invalid.');
            }
            $handler = ($this->actions)($action);
            if (!$handler instanceof Closure) {
                throw new InvalidArgumentException("Server-driven UI action {$action} is not allowed.");
            }
            $button = $button->onPress($handler);
        }
        return $button;
    }

    /** @param array<array-key, mixed> $props */
    private static function text(array $props, string $name): string
    {
        $value = $props[$name] ?? null;
        if (!is_string($value) || strlen($value) > 16_384) {
            throw new InvalidArgumentException("Server-driven UI {$name} must be bounded text.");
        }
        return $value;
    }

    /** @param array<array-key, mixed> $props */
    private static function number(array $props, string $name, float $default): float
    {
        $value = $props[$name] ?? $default;
        if ((!is_int($value) && !is_float($value)) || !is_finite((float) $value)) {
            throw new InvalidArgumentException("Server-driven UI {$name} must be finite.");
        }
        return (float) $value;
    }
}
