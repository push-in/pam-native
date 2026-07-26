<?php

declare(strict_types=1);

namespace Pam\Native\Navigation;

use Closure;
use InvalidArgumentException;
use Pam\Native\Renderable;

final readonly class NavigationTab
{
    public function __construct(
        public string $name,
        public string $label,
        public Renderable|Closure $content,
        public ?Renderable $icon = null,
        public ?string $badge = null,
    ) {
        if (
            preg_match('/^[A-Za-z][A-Za-z0-9_.-]{0,63}$/D', $name) !== 1
            || trim($label) === ''
            || strlen($label) > 64
            || $badge !== null && strlen($badge) > 12
        ) {
            throw new InvalidArgumentException('Navigation tabs require safe names and bounded labels.');
        }
    }

    public function render(): Renderable
    {
        $content = $this->content;

        return $content instanceof Closure ? $content() : $content;
    }
}
