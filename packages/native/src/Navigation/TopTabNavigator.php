<?php

declare(strict_types=1);

namespace Pam\Native\Navigation;

use Closure;
use InvalidArgumentException;
use Pam\Native\AccessibilityRole;
use Pam\Native\Align;
use Pam\Native\GestureDirection;
use Pam\Native\GestureEvent;
use Pam\Native\GestureType;
use Pam\Native\Renderable;
use Pam\Native\Restorable;
use Pam\Native\State;
use Pam\Native\Style;
use Pam\Native\TextAlignment;
use Pam\Native\UI\Column;
use Pam\Native\UI\GestureDetector;
use Pam\Native\UI\Pressable;
use Pam\Native\UI\Row;
use Pam\Native\UI\ScrollView;
use Pam\Native\UI\Text;
use Pam\Native\UI\View;
use Pam\Native\PropKey;

final class TopTabNavigator implements Renderable, Restorable, NavigationStateProvider, NavigationBackHandler, NavigationObservable
{
    /** @var list<NavigationTab> */
    private array $tabs;
    /** @var array<string, Renderable> */
    private array $instances = [];
    private int $selected;
    /** @var array<int, array<int, Closure>> */
    private array $listeners = [];
    /** @var array<string, NavigationSubscription> */
    private array $childSubscriptions = [];
    private int $nextListenerId = 1;

    /** @param list<NavigationTab> $tabs */
    public function __construct(
        string $initialTab,
        array $tabs,
        private readonly string $persistenceKey = 'top-tabs',
        private readonly bool $swipeEnabled = true,
        private readonly bool $scrollEnabled = false,
        private readonly bool $lazy = true,
        private readonly int $barBackground = 0xFFFFFFFF,
        private readonly int $activeColor = 0xFF2563EB,
        private readonly int $inactiveColor = 0xFF64748B,
        private readonly int $indicatorColor = 0xFF2563EB,
    ) {
        if ($tabs === []) throw new InvalidArgumentException('Top tabs require at least one destination.');
        $names = array_map(static fn (NavigationTab $tab): string => $tab->name, $tabs);
        if (count(array_unique($names)) !== count($names)) throw new InvalidArgumentException('Top tab names must be unique.');
        $selected = array_search($initialTab, $names, true);
        if ($selected === false) throw new InvalidArgumentException("Unknown initial top tab {$initialTab}.");
        $this->tabs = array_values($tabs);
        $this->selected = $selected + 1;
        $state = State::get($this->stateKey(), []);
        if (is_array($state)) $this->restoreState($state);
        if (!$this->lazy) foreach ($this->tabs as $tab) $this->instance($tab);
    }

    public function jumpTo(string $name): bool
    {
        foreach ($this->tabs as $index => $tab) {
            if ($tab->name !== $name) continue;
            $next = $index + 1;
            if ($next === $this->selected) return false;
            $this->selected = $next;
            State::set($this->stateKey(), $this->saveState());
            $this->emitNavigation(NavigationEventType::State, ['state' => $this->getState()]);
            return true;
        }
        return false;
    }

    public function selectedTab(): string
    {
        return $this->tabs[$this->selected - 1]->name;
    }

    public function addListener(NavigationEventType $type, Closure $listener): NavigationSubscription
    {
        $id = $this->nextListenerId++;
        $this->listeners[$type->value][$id] = $listener;
        return new NavigationSubscription(function () use ($type, $id): void {
            unset($this->listeners[$type->value][$id]);
        });
    }

    public function canGoBack(): bool
    {
        $child = $this->instances[$this->selectedTab()] ?? null;
        return $child instanceof NavigationBackHandler && $child->canGoBack()
            || $this->selected > 1;
    }

    public function goBack(): bool
    {
        $child = $this->instances[$this->selectedTab()] ?? null;
        if ($child instanceof NavigationBackHandler && $child->canGoBack()) return $child->goBack();
        if ($this->selected <= 1) return false;
        return $this->jumpTo($this->tabs[$this->selected - 2]->name);
    }

    public function toElement(): \Pam\Native\Element
    {
        $items = [];
        foreach ($this->tabs as $index => $tab) {
            $selected = $this->selected === $index + 1;
            $label = Text::make($tab->label)->numberOfLines(1)->style(new Style(
                textColor: $selected ? $this->activeColor : $this->inactiveColor,
                fontSize: 14.0,
                fontWeight: $selected ? 700 : 500,
                textAlign: TextAlignment::Center,
            ));
            $items[] = Pressable::make(
                Column::make(
                    $label,
                    View::make()->style(new Style(
                        widthPercent: 100.0,
                        height: 3.0,
                        backgroundColor: $selected ? $this->indicatorColor : 0x00000000,
                    )),
                )->style(new Style(gap: 9.0, alignItems: Align::Center)),
            )
                ->onPress(fn (): bool => $this->jumpTo($tab->name))
                ->property(PropKey::Selected, $selected)
                ->accessibilityRole(AccessibilityRole::Tab)
                ->accessibilityLabel($tab->label)
                ->style(new Style(
                    minWidth: $this->scrollEnabled ? 96.0 : null,
                    flexGrow: $this->scrollEnabled ? 0.0 : 1.0,
                    minHeight: 48.0,
                    paddingHorizontal: 16.0,
                    justifyContent: \Pam\Native\Justify::End,
                ));
        }
        $bar = Row::make(...$items)->style(new Style(
            widthPercent: 100.0,
            minHeight: 48.0,
            backgroundColor: $this->barBackground,
            alignItems: Align::Stretch,
        ))->accessibilityRole(AccessibilityRole::TabList);
        $tabBar = $this->scrollEnabled
            ? ScrollView::make($bar)->horizontal(true)
            : $bar;
        $scene = View::make($this->instance($this->tabs[$this->selected - 1]))
            ->style(new Style(flexGrow: 1.0));
        $content = Column::make($tabBar, $scene)->style(new Style(flexGrow: 1.0));
        if (!$this->swipeEnabled) return $content->toElement();

        return GestureDetector::make(GestureType::Swipe, $content)
            ->direction(GestureDirection::Horizontal)
            ->onEnd(function (GestureEvent $event): void {
                $rtlAdjusted = $event->translationX;
                if ($rtlAdjusted < 0 && $this->selected < count($this->tabs)) {
                    $this->jumpTo($this->tabs[$this->selected]->name);
                } elseif ($rtlAdjusted > 0 && $this->selected > 1) {
                    $this->jumpTo($this->tabs[$this->selected - 2]->name);
                }
            })
            ->toElement();
    }

    public function stateKey(): string
    {
        return 'top-tab-navigator.'.$this->persistenceKey;
    }

    public function key(): string
    {
        return 'top-tabs.'.$this->persistenceKey;
    }

    /** @return array<string, mixed> */
    public function getState(): array
    {
        return [
            'version' => 1,
            'type' => 3,
            'key' => $this->key(),
            'index' => $this->selected - 1,
            'routes' => array_map(function (NavigationTab $tab): array {
                $route = ['key' => $this->key().'.'.$tab->name, 'name' => $tab->name];
                $instance = $this->instances[$tab->name] ?? null;
                if ($instance instanceof NavigationStateProvider) $route['state'] = $instance->getState();
                return $route;
            }, $this->tabs),
        ];
    }

    public function restoreState(array $state): void
    {
        $selected = $state['selected'] ?? null;
        if (is_string($selected)) $this->jumpTo($selected);
    }

    public function saveState(): array
    {
        return ['version' => 1, 'selected' => $this->selectedTab()];
    }

    private function instance(NavigationTab $tab): Renderable
    {
        if (isset($this->instances[$tab->name])) return $this->instances[$tab->name];
        $instance = $tab->render();
        $this->instances[$tab->name] = $instance;
        if ($instance instanceof NavigationObservable) {
            $this->childSubscriptions[$tab->name] = $instance->addListener(
                NavigationEventType::State,
                fn (): NavigationEvent => $this->emitNavigation(
                    NavigationEventType::State,
                    ['state' => $this->getState()],
                ),
            );
        }
        return $instance;
    }

    private function emitNavigation(
        NavigationEventType $type,
        array $data = [],
    ): NavigationEvent {
        $event = new NavigationEvent($type, $this->selectedTab(), $data);
        foreach ($this->listeners[$type->value] ?? [] as $listener) $listener($event);
        return $event;
    }
}
