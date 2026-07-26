<?php

declare(strict_types=1);

namespace Pam\Native\Navigation;

use Closure;
use InvalidArgumentException;
use Pam\Native\Renderable;

final class TabRouter
{
    /** @var list<NavigationTab> */
    private array $tabs = [];
    private TabPresentation $presentation = TabPresentation::Adaptive;
    private string $persistenceKey = 'tabs';
    private int $barBackground = 0xFFFFFFFF;
    private int $activeColor = 0xFF0F172A;
    private int $inactiveColor = 0xFF64748B;
    private int $dividerColor = 0xFFE2E8F0;

    public function __construct(private readonly string $initialTab)
    {
        if ($initialTab === '') {
            throw new InvalidArgumentException('The initial tab cannot be empty.');
        }
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

    public function presentation(TabPresentation $presentation): self
    {
        $copy = clone $this;
        $copy->presentation = $presentation;

        return $copy;
    }

    public function persistence(string $key): self
    {
        $copy = clone $this;
        $copy->persistenceKey = $key;

        return $copy;
    }

    public function appearance(
        int $barBackground,
        int $activeColor,
        int $inactiveColor,
        int $dividerColor,
    ): self {
        $copy = clone $this;
        $copy->barBackground = $barBackground;
        $copy->activeColor = $activeColor;
        $copy->inactiveColor = $inactiveColor;
        $copy->dividerColor = $dividerColor;

        return $copy;
    }

    public function build(): TabNavigator
    {
        return new TabNavigator(
            initialTab: $this->initialTab,
            tabs: $this->tabs,
            presentation: $this->presentation,
            persistenceKey: $this->persistenceKey,
            barBackground: $this->barBackground,
            activeColor: $this->activeColor,
            inactiveColor: $this->inactiveColor,
            dividerColor: $this->dividerColor,
        );
    }
}
