<?php

declare(strict_types=1);

namespace Pam\Native\Navigation;

use InvalidArgumentException;
use Pam\Native\AccessibilityRole;
use Pam\Native\HapticFeedback;
use Pam\Native\Overflow;
use Pam\Native\PropKey;
use Pam\Native\Renderable;
use Pam\Native\Restorable;
use Pam\Native\State;
use Pam\Native\Style;
use Pam\Native\System\Haptics;
use Pam\Native\UI\Column;
use Pam\Native\UI\Pressable;
use Pam\Native\UI\Row;
use Pam\Native\UI\SafeAreaView;
use Pam\Native\UI\Text;
use Pam\Native\UI\View;
use Pam\Native\WindowMetrics;

final class TabNavigator implements Renderable, Restorable
{
    /** @var list<NavigationTab> */
    private array $tabs;
    private int $selected;
    private float $windowWidth = 0.0;
    private float $windowHeight = 0.0;

    /**
     * @param list<NavigationTab> $tabs
     */
    public function __construct(
        string $initialTab,
        array $tabs,
        private readonly TabPresentation $presentation = TabPresentation::Adaptive,
        private readonly string $persistenceKey = 'tabs',
        private readonly int $barBackground = 0xFFFFFFFF,
        private readonly int $activeColor = 0xFF0F172A,
        private readonly int $inactiveColor = 0xFF64748B,
        private readonly int $dividerColor = 0xFFE2E8F0,
    ) {
        if ($tabs === [] || count($tabs) > 5) {
            throw new InvalidArgumentException('Tab navigation requires between one and five destinations.');
        }
        $names = array_map(
            static fn (NavigationTab $tab): string => $tab->name,
            $tabs,
        );
        if (count(array_unique($names)) !== count($names)) {
            throw new InvalidArgumentException('Tab names must be unique.');
        }
        $selected = array_search($initialTab, $names, true);
        if ($selected === false) {
            throw new InvalidArgumentException("Unknown initial tab {$initialTab}.");
        }
        $this->tabs = array_values($tabs);
        $this->selected = $selected + 1;
        $persisted = State::get($this->stateKey(), []);
        if (is_array($persisted)) {
            $this->restoreState($persisted);
        }
    }

    public function select(string $name): bool
    {
        foreach ($this->tabs as $index => $tab) {
            if ($tab->name !== $name) {
                continue;
            }
            $next = $index + 1;
            if ($next === $this->selected) {
                return false;
            }
            $this->selected = $next;
            State::set($this->stateKey(), $this->saveState());
            Haptics::trigger(HapticFeedback::Selection);

            return true;
        }

        return false;
    }

    public function selectedTab(): string
    {
        return $this->tabs[$this->selected - 1]->name;
    }

    public function dimensions(WindowMetrics $metrics): void
    {
        $this->windowWidth = max(0.0, $metrics->width);
        $this->windowHeight = max(0.0, $metrics->height);
    }

    public function resolvedPresentation(): TabPresentation
    {
        if ($this->presentation !== TabPresentation::Adaptive) {
            return $this->presentation;
        }

        return $this->windowWidth >= 840.0
            ? TabPresentation::Rail
            : TabPresentation::Bottom;
    }

    public function toElement(): \Pam\Native\Element
    {
        $destinations = [];
        $screens = [];
        foreach ($this->tabs as $index => $tab) {
            $selected = $index + 1 === $this->selected;
            $label = Text::make($tab->label)->style(new Style(
                textColor: $selected ? $this->activeColor : $this->inactiveColor,
                fontSize: 12.0,
                fontWeight: $selected ? 700 : 500,
                textAlign: \Pam\Native\TextAlignment::Center,
            ));
            $children = $tab->icon === null ? [$label] : [$tab->icon, $label];
            if ($tab->badge !== null) {
                $children[] = Text::make($tab->badge)->style(new Style(
                    minWidth: 20.0,
                    minHeight: 20.0,
                    fontSize: 11.0,
                    fontWeight: 700,
                    textAlign: \Pam\Native\TextAlignment::Center,
                    borderRadius: 10.0,
                ));
            }
            $destinations[] = Pressable::make(
                Column::make(...$children)->style(new Style(
                    alignItems: \Pam\Native\Align::Center,
                    justifyContent: \Pam\Native\Justify::Center,
                    gap: 4.0,
                )),
            )
                ->onPress(fn (): bool => $this->select($tab->name))
                ->hitSlop(4.0)
                ->style(new Style(
                    flexGrow: 1.0,
                    minWidth: 64.0,
                    minHeight: 56.0,
                    paddingHorizontal: 8.0,
                    paddingVertical: 6.0,
                    borderRadius: 12.0,
                    opacity: $selected ? 1.0 : 0.7,
                ))
                ->property(PropKey::Selected, $selected)
                ->accessibilityRole(AccessibilityRole::Tab)
                ->accessibilityLabel($tab->label);
            if ($selected) {
                $screens[] = View::make($tab->render())
                    ->style(new Style(
                        positionType: \Pam\Native\PositionType::Absolute,
                        top: 0.0,
                        right: 0.0,
                        bottom: 0.0,
                        left: 0.0,
                        overflow: Overflow::Hidden,
                    ))
                    ->key('tab-screen-'.$tab->name);
            }
        }

        if ($this->resolvedPresentation() === TabPresentation::Rail) {
            $layout = Row::make(
                Column::make(...$destinations)
                    ->style(new Style(
                        width: 104.0,
                        backgroundColor: $this->barBackground,
                        padding: 12.0,
                        gap: 8.0,
                        borderRightWidth: 1.0,
                        borderColor: $this->dividerColor,
                    ))
                    ->accessibilityRole(AccessibilityRole::TabList),
                Column::make(...$screens)->style(new Style(
                    flexGrow: 1.0,
                    overflow: Overflow::Hidden,
                )),
            )->style(new Style(flexGrow: 1.0));
        } else {
            $layout = Column::make(
                Column::make(...$screens)->style(new Style(
                    positionType: \Pam\Native\PositionType::Absolute,
                    top: 0.0,
                    right: 0.0,
                    bottom: 68.0,
                    left: 0.0,
                    overflow: Overflow::Hidden,
                )),
                Row::make(...$destinations)
                    ->style(new Style(
                        positionType: \Pam\Native\PositionType::Absolute,
                        right: 0.0,
                        bottom: 0.0,
                        left: 0.0,
                        height: 68.0,
                        minHeight: 68.0,
                        backgroundColor: $this->barBackground,
                        paddingHorizontal: 8.0,
                        paddingTop: 6.0,
                        gap: 4.0,
                        borderTopWidth: 1.0,
                        borderColor: $this->dividerColor,
                    ))
                    ->accessibilityRole(AccessibilityRole::TabList),
            )->style(new Style(
                widthPercent: 100.0,
                heightPercent: 100.0,
                flexGrow: 1.0,
            ));
        }

        return SafeAreaView::make($layout)
            ->edges(top: true, right: true, bottom: true, left: true)
            ->style(new Style(
                widthPercent: 100.0,
                height: $this->windowHeight > 0.0 ? $this->windowHeight : null,
                heightPercent: $this->windowHeight > 0.0 ? null : 100.0,
                flexGrow: 1.0,
            ));
    }

    public function stateKey(): string
    {
        return 'tab-navigator.'.$this->persistenceKey;
    }

    public function restoreState(array $state): void
    {
        $selected = $state['selected'] ?? null;
        if (is_string($selected)) {
            foreach ($this->tabs as $index => $tab) {
                if ($tab->name === $selected) {
                    $this->selected = $index + 1;
                    return;
                }
            }
        }
    }

    public function saveState(): array
    {
        return ['version' => 1, 'selected' => $this->selectedTab()];
    }
}
