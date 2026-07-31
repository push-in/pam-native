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
use Closure;

final class TabNavigator implements Renderable, Restorable, NavigationStateProvider, NavigationBackHandler, NavigationObservable, NavigationActionHandler
{
    /** @var list<NavigationTab> */
    private array $tabs;
    private int $selected;
    private float $windowWidth = 0.0;
    private float $windowHeight = 0.0;
    /** @var array<string, Renderable> */
    private array $instances = [];
    /** @var list<string> */
    private array $history = [];
    /** @var array<int, array<int, Closure>> */
    private array $listeners = [];
    private int $nextListenerId = 1;
    private readonly string $initialTab;
    /** @var array<string, NavigationSubscription> */
    private array $childSubscriptions = [];
    /** @var array<string, array<string, mixed>> */
    private array $pendingChildState = [];

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
        private readonly TabBackBehavior $backBehavior = TabBackBehavior::FirstRoute,
        private readonly bool $popToTopOnBlur = false,
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
        $this->initialTab = $initialTab;
        $this->selected = $selected + 1;
        $this->history = [$initialTab];
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
                $instance = $this->instances[$name] ?? null;
                if ($instance instanceof Navigator) $instance->popToTop();
                return false;
            }
            $event = $this->emitNavigation(NavigationEventType::TabPress, ['route' => $name], true, $name);
            if ($event->isDefaultPrevented()) return false;
            $previous = $this->instances[$this->selectedTab()] ?? null;
            if ($this->popToTopOnBlur && $previous instanceof Navigator) $previous->popToTop();
            $this->selected = $next;
            if ($this->backBehavior === TabBackBehavior::FullHistory) {
                $this->history[] = $name;
            } else {
                $this->history = array_values(array_filter(
                    $this->history,
                    static fn (string $entry): bool => $entry !== $name,
                ));
                $this->history[] = $name;
            }
            State::set($this->stateKey(), $this->saveState());
            Haptics::trigger(HapticFeedback::Selection);
            $this->emitNavigation(NavigationEventType::State, ['state' => $this->getState()]);

            return true;
        }

        return false;
    }

    public function selectedTab(): string
    {
        return $this->tabs[$this->selected - 1]->name;
    }

    public function jumpTo(string $name): bool
    {
        return $this->select($name);
    }

    public function dispatch(NavigationAction $action): bool
    {
        $this->emitNavigation(NavigationEventType::Action, ['action' => $action->toArray()]);
        if ($action->target === null || $action->target === $this->key()) {
            if (
                $action->type === NavigationActionType::Navigate
                && $action->route !== null
                && array_any($this->tabs, static fn (NavigationTab $tab): bool => $tab->name === $action->route)
            ) return $this->select($action->route) || $this->selectedTab() === $action->route;
            if (in_array($action->type, [NavigationActionType::GoBack, NavigationActionType::Pop], true)) {
                return $this->goBack();
            }
        }
        $child = $this->instance($this->tabs[$this->selected - 1]);
        return $child instanceof NavigationActionHandler && $child->dispatch($action);
    }

    public function canGoBack(): bool
    {
        $child = $this->instances[$this->selectedTab()] ?? null;
        if ($child instanceof NavigationBackHandler && $child->canGoBack()) return true;
        return $this->canGoBackWithinTabs();
    }

    private function canGoBackWithinTabs(): bool
    {
        return match ($this->backBehavior) {
            TabBackBehavior::None => false,
            TabBackBehavior::FirstRoute => $this->selected !== 1,
            TabBackBehavior::InitialRoute => $this->selectedTab() !== $this->initialTab,
            TabBackBehavior::Order => $this->selected > 1,
            TabBackBehavior::History, TabBackBehavior::FullHistory => count($this->history) > 1,
        };
    }

    public function goBack(): bool
    {
        $child = $this->instances[$this->selectedTab()] ?? null;
        if ($child instanceof NavigationBackHandler && $child->canGoBack()) return $child->goBack();
        if (!$this->canGoBackWithinTabs()) return false;
        $target = match ($this->backBehavior) {
            TabBackBehavior::FirstRoute => $this->tabs[0]->name,
            TabBackBehavior::InitialRoute => $this->initialTab,
            TabBackBehavior::Order => $this->tabs[$this->selected - 2]->name,
            TabBackBehavior::History, TabBackBehavior::FullHistory => $this->history[count($this->history) - 2],
            TabBackBehavior::None => $this->selectedTab(),
        };
        if (in_array($this->backBehavior, [TabBackBehavior::History, TabBackBehavior::FullHistory], true)) {
            array_pop($this->history);
        }
        $changed = $this->select($target);
        if ($changed && $this->backBehavior === TabBackBehavior::FullHistory) {
            array_pop($this->history);
            State::set($this->stateKey(), $this->saveState());
        }
        return $changed;
    }

    public function addListener(NavigationEventType $type, Closure $listener): NavigationSubscription
    {
        $id = $this->nextListenerId++;
        $this->listeners[$type->value][$id] = $listener;
        return new NavigationSubscription(function () use ($type, $id): void {
            unset($this->listeners[$type->value][$id]);
        });
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
        $items = [];
        $screens = [];
        foreach ($this->tabs as $index => $tab) {
            $items[] = ['name' => $tab->name, 'label' => $tab->label, 'badge' => $tab->badge];
            $scene = isset($this->instances[$tab->name]) || $index + 1 === $this->selected
                ? $this->instance($tab)
                : View::make();
            $screens[] = View::make($scene)
                ->style(new Style(flexGrow: 1.0))
                ->key('tab-screen-'.$tab->name);
        }
        $position = $this->resolvedPresentation() === TabPresentation::Rail ? 3 : 1;
        return NativeTabHost::make(
            $items,
            $this->selected,
            $position,
            $this->activeColor,
            $this->inactiveColor,
            $this->barBackground,
            $this->activeColor,
            false,
            false,
            $screens,
            function (string $index): bool {
                $target = (int) $index;
                return isset($this->tabs[$target - 1]) && $this->select($this->tabs[$target - 1]->name);
            },
        );

        /* @deprecated PAM-rendered fallback retained temporarily for wire compatibility. */
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
                ->onLongPress(function () use ($tab): void {
                    $this->emitNavigation(
                        NavigationEventType::TabLongPress,
                        ['route' => $tab->name],
                        target: $tab->name,
                    );
                })
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
                $screens[] = View::make($this->instance($tab))
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

    public function key(): string
    {
        return 'tabs.'.$this->persistenceKey;
    }

    /** @return array<string, mixed> */
    public function getState(): array
    {
        return [
            'version' => 2,
            'type' => 2,
            'key' => $this->key(),
            'index' => $this->selected - 1,
            'history' => $this->history,
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
        if (is_string($selected)) {
            foreach ($this->tabs as $index => $tab) {
                if ($tab->name === $selected) {
                    $this->selected = $index + 1;
                    break;
                }
            }
        }
        if (is_array($state['history'] ?? null)) {
            $valid = array_map(static fn (NavigationTab $tab): string => $tab->name, $this->tabs);
            $history = array_values(array_filter(
                $state['history'],
                static fn (mixed $entry): bool => is_string($entry) && in_array($entry, $valid, true),
            ));
            if ($history !== []) $this->history = $history;
        }
        if (is_array($state['children'] ?? null)) {
            foreach ($state['children'] as $name => $childState) {
                if (is_string($name) && is_array($childState)) {
                    $this->pendingChildState[$name] = $childState;
                }
            }
        }
    }

    public function saveState(): array
    {
        $children = [];
        foreach ($this->instances as $name => $instance) {
            if ($instance instanceof Restorable) $children[$name] = $instance->saveState();
        }
        return [
            'version' => 3,
            'selected' => $this->selectedTab(),
            'history' => $this->history,
            'children' => $children,
        ];
    }

    private function instance(NavigationTab $tab): Renderable
    {
        if (!isset($this->instances[$tab->name])) {
            $instance = $this->instances[$tab->name] = $tab->render();
            $pendingState = $this->pendingChildState[$tab->name] ?? null;
            if ($pendingState !== null && $instance instanceof Restorable) {
                $instance->restoreState($pendingState);
                unset($this->pendingChildState[$tab->name]);
            }
            if ($instance instanceof NavigationObservable) {
                $this->childSubscriptions[$tab->name] = $instance->addListener(
                    NavigationEventType::State,
                    fn () => $this->emitNavigation(NavigationEventType::State, ['state' => $this->getState()]),
                );
            }
        }
        return $this->instances[$tab->name];
    }

    private function emitNavigation(
        NavigationEventType $type,
        array $data = [],
        bool $canPreventDefault = false,
        ?string $target = null,
    ): NavigationEvent {
        $event = new NavigationEvent($type, $target ?? $this->selectedTab(), $data, $canPreventDefault);
        foreach ($this->listeners[$type->value] ?? [] as $listener) $listener($event);
        return $event;
    }
}
