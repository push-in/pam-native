<?php

declare(strict_types=1);

namespace Pam\Native\Navigation;

use Closure;
use InvalidArgumentException;
use Pam\Native\Renderable;

final class TopTabRouter
{
    /** @var list<NavigationTab> */
    private array $tabs = [];
    private string $persistenceKey = 'top-tabs';
    private bool $swipeEnabled = true;
    private bool $scrollEnabled = false;
    private bool $lazy = true;
    private int $barBackground = 0xFFFFFFFF;
    private int $activeColor = 0xFF2563EB;
    private int $inactiveColor = 0xFF64748B;
    private int $indicatorColor = 0xFF2563EB;

    public function __construct(private readonly string $initialTab)
    {
        if ($initialTab === '') throw new InvalidArgumentException('The initial top tab cannot be empty.');
    }

    public function tab(
        string $name,
        string $label,
        Renderable|Closure $content,
        ?Renderable $icon = null,
        ?string $badge = null,
    ): self {
        $copy = clone $this;
        $copy->tabs[] = new NavigationTab($name, $label, $content, $icon, $badge);
        return $copy;
    }

    public function behavior(bool $swipeEnabled = true, bool $scrollEnabled = false, bool $lazy = true): self
    {
        $copy = clone $this;
        $copy->swipeEnabled = $swipeEnabled;
        $copy->scrollEnabled = $scrollEnabled;
        $copy->lazy = $lazy;
        return $copy;
    }

    public function persistence(string $key): self
    {
        $copy = clone $this;
        $copy->persistenceKey = $key;
        return $copy;
    }

    public function appearance(int $background, int $active, int $inactive, int $indicator): self
    {
        $copy = clone $this;
        $copy->barBackground = $background;
        $copy->activeColor = $active;
        $copy->inactiveColor = $inactive;
        $copy->indicatorColor = $indicator;
        return $copy;
    }

    public function build(): TopTabNavigator
    {
        return new TopTabNavigator(
            $this->initialTab,
            $this->tabs,
            $this->persistenceKey,
            $this->swipeEnabled,
            $this->scrollEnabled,
            $this->lazy,
            $this->barBackground,
            $this->activeColor,
            $this->inactiveColor,
            $this->indicatorColor,
        );
    }
}
