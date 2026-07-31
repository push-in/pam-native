<?php

declare(strict_types=1);

namespace Pam\Native\Navigation;

use Closure;
use InvalidArgumentException;
use Pam\Native\AccessibilityRole;
use Pam\Native\Renderable;
use Pam\Native\Restorable;
use Pam\Native\State;
use Pam\Native\Style;
use Pam\Native\UI\Column;
use Pam\Native\UI\DrawerLayoutAndroid;
use Pam\Native\UI\Pressable;
use Pam\Native\UI\Row;
use Pam\Native\UI\SafeAreaView;
use Pam\Native\UI\ScrollView;
use Pam\Native\UI\Text;
use Pam\Native\UI\View;
use Pam\Native\WindowMetrics;

final class DrawerNavigator implements Renderable, Restorable, NavigationStateProvider, NavigationBackHandler, NavigationObservable, NavigationActionHandler
{
    /** @var list<NavigationDrawerItem> */
    private array $routes;
    /** @var list<string> */
    private array $history = [];
    private int $selected;
    private bool $open;
    private float $windowWidth = 0.0;
    /** @var array<string, bool> */
    private array $expandedGroups = [];
    /** @var array<string, Renderable> */
    private array $instances = [];
    /** @var array<int, array<int, Closure>> */
    private array $listeners = [];
    /** @var array<string, NavigationSubscription> */
    private array $childSubscriptions = [];
    /** @var array<string, array<string, mixed>> */
    private array $pendingChildState = [];
    private int $nextListenerId = 1;

    /**
     * @param list<NavigationDrawerItem> $routes
     */
    public function __construct(
        string $initialRoute,
        array $routes,
        private readonly DrawerType $type = DrawerType::Front,
        private readonly DrawerPosition $position = DrawerPosition::Automatic,
        private readonly DrawerBackBehavior $backBehavior =
            DrawerBackBehavior::FirstRoute,
        private readonly DrawerKeyboardDismissMode $keyboardDismissMode =
            DrawerKeyboardDismissMode::OnDrag,
        private readonly DrawerStatusBarAnimation $statusBarAnimation =
            DrawerStatusBarAnimation::Slide,
        private readonly string $persistenceKey = 'drawer',
        bool $defaultOpen = false,
        private readonly bool $swipeEnabled = true,
        private readonly bool $hideStatusBarOnOpen = false,
        private readonly float $width = 256.0,
        private readonly float $swipeEdgeWidth = 32.0,
        private readonly float $swipeMinDistance = 56.0,
        private readonly float $permanentBreakpoint = 840.0,
        private readonly int $backgroundColor = 0xFFFFFFFF,
        private readonly int $activeColor = 0xFF0F172A,
        private readonly int $inactiveColor = 0xFF64748B,
        private readonly int $activeBackgroundColor = 0xFFE2E8F0,
        private readonly int $overlayColor = 0x33000000,
        private readonly int $dividerColor = 0xFFE2E8F0,
        private readonly ?Closure $customContent = null,
    ) {
        if ($routes === []) {
            throw new InvalidArgumentException(
                'Drawer navigation requires at least one route.',
            );
        }
        $names = array_map(
            static fn (NavigationDrawerItem $route): string => $route->name,
            $routes,
        );
        if (count(array_unique($names)) !== count($names)) {
            throw new InvalidArgumentException('Drawer route names must be unique.');
        }
        $selected = array_search($initialRoute, $names, true);
        if ($selected === false) {
            throw new InvalidArgumentException(
                "Unknown initial drawer route {$initialRoute}.",
            );
        }
        $this->routes = array_values($routes);
        $this->selected = $selected + 1;
        $this->open = $defaultOpen;
        $this->history = [$initialRoute];
        $persisted = State::get($this->stateKey(), []);
        if (is_array($persisted)) {
            $this->restoreState($persisted);
        }
    }

    public function navigate(string $name): bool
    {
        foreach ($this->routes as $index => $route) {
            if ($route->name !== $name) {
                continue;
            }
            $event = $this->emitNavigation(
                NavigationEventType::DrawerItemPress,
                ['route' => $name],
                true,
                $name,
            );
            if ($event->isDefaultPrevented()) return false;
            $changed = $this->selected !== $index + 1;
            $this->selected = $index + 1;
            $this->open = false;
            if ($this->backBehavior === DrawerBackBehavior::FullHistory) {
                $this->history[] = $name;
            } else {
                $this->history = array_values(array_filter(
                    $this->history,
                    static fn (string $entry): bool => $entry !== $name,
                ));
                $this->history[] = $name;
            }
            State::set($this->stateKey(), $this->saveState());
            $this->emitNavigation(NavigationEventType::State, ['state' => $this->getState()]);

            return $changed;
        }

        return false;
    }

    public function toggleGroup(string $group): bool
    {
        $exists = false;
        foreach ($this->routes as $route) {
            if ($route->group === $group) {
                $exists = true;
                break;
            }
        }
        if (!$exists) {
            return false;
        }

        $this->expandedGroups[$group] = !$this->isGroupExpanded($group);
        State::set($this->stateKey(), $this->saveState());

        return true;
    }

    public function isGroupExpanded(string $group): bool
    {
        if (($this->expandedGroups[$group] ?? false) === true) {
            return true;
        }

        return $this->routes[$this->selected - 1]->group === $group;
    }

    public function openDrawer(): void
    {
        $this->open = true;
        State::set($this->stateKey(), $this->saveState());
        $this->emitNavigation(NavigationEventType::State, ['state' => $this->getState()]);
    }

    public function closeDrawer(): void
    {
        $this->open = false;
        State::set($this->stateKey(), $this->saveState());
        $this->emitNavigation(NavigationEventType::State, ['state' => $this->getState()]);
    }

    public function toggleDrawer(): void
    {
        $this->open = !$this->open;
        State::set($this->stateKey(), $this->saveState());
        $this->emitNavigation(NavigationEventType::State, ['state' => $this->getState()]);
    }

    public function addListener(NavigationEventType $type, Closure $listener): NavigationSubscription
    {
        $id = $this->nextListenerId++;
        $this->listeners[$type->value][$id] = $listener;
        return new NavigationSubscription(function () use ($type, $id): void {
            unset($this->listeners[$type->value][$id]);
        });
    }

    public function selectedRoute(): string
    {
        return $this->routes[$this->selected - 1]->name;
    }

    public function dispatch(NavigationAction $action): bool
    {
        $this->emitNavigation(NavigationEventType::Action, ['action' => $action->toArray()]);
        if ($action->target === null || $action->target === $this->key()) {
            if (
                $action->type === NavigationActionType::Navigate
                && $action->route !== null
                && array_any($this->routes, static fn (NavigationDrawerItem $item): bool => $item->name === $action->route)
            ) return $this->navigate($action->route) || $this->selectedRoute() === $action->route;
            if (in_array($action->type, [NavigationActionType::GoBack, NavigationActionType::Pop], true)) {
                return $this->goBack();
            }
        }
        $child = $this->instance($this->routes[$this->selected - 1]);
        return $child instanceof NavigationActionHandler && $child->dispatch($action);
    }

    public function canGoBack(): bool
    {
        if ($this->open && $this->resolvedType() !== DrawerType::Permanent) return true;
        $child = $this->instances[$this->selectedRoute()] ?? null;
        if ($child instanceof NavigationBackHandler && $child->canGoBack()) return true;
        return match ($this->backBehavior) {
            DrawerBackBehavior::None => false,
            DrawerBackBehavior::FirstRoute, DrawerBackBehavior::InitialRoute, DrawerBackBehavior::Order => $this->selected > 1,
            DrawerBackBehavior::History, DrawerBackBehavior::FullHistory => count($this->history) > 1,
        };
    }

    public function goBack(): bool
    {
        if ($this->open && $this->resolvedType() !== DrawerType::Permanent) {
            $this->closeDrawer();
            return true;
        }
        $child = $this->instances[$this->selectedRoute()] ?? null;
        if ($child instanceof NavigationBackHandler && $child->canGoBack()) return $child->goBack();
        if (!$this->canGoBack()) return false;
        $target = match ($this->backBehavior) {
            DrawerBackBehavior::FirstRoute, DrawerBackBehavior::InitialRoute => $this->routes[0]->name,
            DrawerBackBehavior::Order => $this->routes[$this->selected - 2]->name,
            DrawerBackBehavior::History, DrawerBackBehavior::FullHistory => $this->history[count($this->history) - 2],
            DrawerBackBehavior::None => $this->selectedRoute(),
        };
        if (in_array($this->backBehavior, [DrawerBackBehavior::History, DrawerBackBehavior::FullHistory], true)) {
            array_pop($this->history);
        }
        $changed = $this->navigate($target);
        if ($changed && $this->backBehavior === DrawerBackBehavior::FullHistory) {
            array_pop($this->history);
            State::set($this->stateKey(), $this->saveState());
        }
        return $changed;
    }

    public function dimensions(WindowMetrics $metrics): void
    {
        $this->windowWidth = max(0.0, $metrics->width);
    }

    public function resolvedType(): DrawerType
    {
        if (
            $this->permanentBreakpoint > 0.0
            && $this->windowWidth >= $this->permanentBreakpoint
        ) {
            return DrawerType::Permanent;
        }

        return $this->type;
    }

    public function toElement(): \Pam\Native\Element
    {
        $selectedRoute = $this->routes[$this->selected - 1];
        $screen = View::make($this->instance($selectedRoute))->style(new Style(
            widthPercent: 100.0,
            heightPercent: 100.0,
            flexGrow: 1.0,
        ));
        $routeItem = function (
            NavigationDrawerItem $route,
            int $index,
            bool $grouped = false,
        ): Pressable {
            $selected = $index + 1 === $this->selected;
            $label = Text::make($route->label)->style(new Style(
                textColor: $selected
                    ? $this->activeColor
                    : $this->inactiveColor,
                fontSize: 14.0,
                lineHeight: 20.0,
                fontWeight: $selected ? 600 : 400,
            ));
            $rowChildren = $route->icon === null
                ? Row::make($label)
                : Row::make($route->icon, $label);
            if ($route->badge !== null) {
                $rowChildren = Row::make(
                    $rowChildren,
                    Text::make($route->badge)->style(new Style(
                        minWidth: 20.0,
                        minHeight: 20.0,
                        paddingHorizontal: 5.0,
                        borderRadius: 10.0,
                        textAlign: \Pam\Native\TextAlignment::Center,
                        textColor: $this->activeColor,
                    )),
                )->style(new Style(alignItems: \Pam\Native\Align::Center));
            }

            return Pressable::make($rowChildren->style(new Style(
                gap: 16.0,
                alignItems: \Pam\Native\Align::Center,
            )))
                ->onPress(fn (): bool => $this->navigate($route->name))
                ->style(new Style(
                    minHeight: 44.0,
                    paddingLeft: $grouped ? 24.0 : 16.0,
                    paddingRight: 16.0,
                    borderRadius: 0.0,
                    backgroundColor: $selected
                        ? $this->activeBackgroundColor
                        : null,
                    justifyContent: \Pam\Native\Justify::Center,
                    animationDurationMs: 200,
                    animateChanges: true,
                ))
                ->accessibilityRole(AccessibilityRole::Button)
                ->accessibilityLabel($route->label);
        };
        $items = [];
        /** @var array<string, list<array{0: NavigationDrawerItem, 1: int}>> $groups */
        $groups = [];
        foreach ($this->routes as $index => $route) {
            if ($route->group === null) {
                $items[] = $routeItem($route, $index);
                continue;
            }
            $groups[$route->group][] = [$route, $index];
        }
        foreach ($groups as $group => $groupRoutes) {
            $expanded = $this->isGroupExpanded($group);
            $items[] = Pressable::make(
                Row::make(
                    Text::make($group)->style(new Style(
                        textColor: $this->inactiveColor,
                        fontSize: 12.0,
                        lineHeight: 16.0,
                        fontWeight: 700,
                        letterSpacing: 0.08,
                        flexGrow: 1.0,
                    )),
                    Text::make($expanded ? '-' : '+')->style(new Style(
                        textColor: $this->inactiveColor,
                        fontSize: 18.0,
                        lineHeight: 20.0,
                    )),
                )->style(new Style(
                    widthPercent: 100.0,
                    alignItems: \Pam\Native\Align::Center,
                )),
            )
                ->onPress(fn (): bool => $this->toggleGroup($group))
                ->style(new Style(
                    minHeight: 40.0,
                    paddingHorizontal: 16.0,
                    marginTop: 0.0,
                    borderRadius: 0.0,
                    justifyContent: \Pam\Native\Justify::Center,
                ))
                ->accessibilityRole(AccessibilityRole::Button)
                ->accessibilityLabel($group);
            if (!$expanded) {
                continue;
            }
            foreach ($groupRoutes as [$route, $index]) {
                $items[] = $routeItem($route, $index, true);
            }
        }
        $defaultDrawer = SafeAreaView::make(
            ScrollView::make(
                Column::make(...$items)->style(new Style(
                    paddingVertical: 8.0,
                    gap: 0.0,
                    widthPercent: 100.0,
                )),
            )->style(new Style(
                widthPercent: 100.0,
                heightPercent: 100.0,
                flexGrow: 1.0,
            )),
        )->style(new Style(
            width: $this->width,
            heightPercent: 100.0,
            flexGrow: 1.0,
            backgroundColor: $this->backgroundColor,
            borderRightWidth: 1.0,
            borderColor: $this->dividerColor,
        ));
        $drawer = $this->customContent === null
            ? $defaultDrawer
            : ($this->customContent)($this, $this->routes);

        return DrawerLayoutAndroid::make($screen, $drawer)
            ->open($this->open || $this->resolvedType() === DrawerType::Permanent)
            ->presentation($this->resolvedType(), $this->position)
            ->width($this->width)
            ->overlayColor($this->overlayColor)
            ->gestures(
                $this->swipeEnabled,
                $this->swipeEdgeWidth,
                $this->swipeMinDistance,
                $this->keyboardDismissMode,
            )
            ->statusBar(
                $this->hideStatusBarOnOpen,
                $this->statusBarAnimation,
            )
            ->permanentBreakpoint($this->permanentBreakpoint)
            ->onOpen(function (): void {
                $this->openDrawer();
            })
            ->onClose(function (): void {
                $this->closeDrawer();
            });
    }

    public function stateKey(): string
    {
        return 'drawer-navigator.'.$this->persistenceKey;
    }

    public function key(): string
    {
        return 'drawer.'.$this->persistenceKey;
    }

    /** @return array<string, mixed> */
    public function getState(): array
    {
        return [
            'version' => 2,
            'type' => 4,
            'key' => $this->key(),
            'index' => $this->selected - 1,
            'history' => $this->history,
            'open' => $this->open,
            'routes' => array_map(function (NavigationDrawerItem $item): array {
                $route = ['key' => $this->key().'.'.$item->name, 'name' => $item->name];
                $instance = $this->instances[$item->name] ?? null;
                if ($instance instanceof NavigationStateProvider) $route['state'] = $instance->getState();
                return $route;
            }, $this->routes),
        ];
    }

    public function restoreState(array $state): void
    {
        $route = $state['selected'] ?? null;
        if (is_string($route)) {
            foreach ($this->routes as $index => $item) {
                if ($item->name === $route) {
                    $this->selected = $index + 1;
                    break;
                }
            }
        }
        $this->open = ($state['open'] ?? false) === true;
        if (is_array($state['history'] ?? null)) {
            $valid = array_map(
                static fn (NavigationDrawerItem $item): string => $item->name,
                $this->routes,
            );
            $this->history = array_values(array_filter(
                $state['history'],
                static fn (mixed $entry): bool =>
                    is_string($entry) && in_array($entry, $valid, true),
            ));
        }
        if (is_array($state['expandedGroups'] ?? null)) {
            $validGroups = [];
            foreach ($this->routes as $item) {
                if ($item->group !== null) {
                    $validGroups[$item->group] = true;
                }
            }
            $this->expandedGroups = [];
            foreach ($state['expandedGroups'] as $group) {
                if (is_string($group) && isset($validGroups[$group])) {
                    $this->expandedGroups[$group] = true;
                }
            }
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
            'version' => 2,
            'selected' => $this->selectedRoute(),
            'open' => $this->open,
            'history' => $this->history,
            'expandedGroups' => array_keys(array_filter($this->expandedGroups)),
            'children' => $children,
        ];
    }

    private function instance(NavigationDrawerItem $item): Renderable
    {
        if (isset($this->instances[$item->name])) return $this->instances[$item->name];
        $instance = $item->render();
        $pendingState = $this->pendingChildState[$item->name] ?? null;
        if ($pendingState !== null && $instance instanceof Restorable) {
            $instance->restoreState($pendingState);
            unset($this->pendingChildState[$item->name]);
        }
        $this->instances[$item->name] = $instance;
        if ($instance instanceof NavigationObservable) {
            $this->childSubscriptions[$item->name] = $instance->addListener(
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
        bool $canPreventDefault = false,
        ?string $target = null,
    ): NavigationEvent {
        $event = new NavigationEvent($type, $target ?? $this->selectedRoute(), $data, $canPreventDefault);
        foreach ($this->listeners[$type->value] ?? [] as $listener) $listener($event);
        return $event;
    }
}
