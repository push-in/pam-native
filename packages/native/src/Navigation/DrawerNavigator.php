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

final class DrawerNavigator implements Renderable, Restorable
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
    }

    public function closeDrawer(): void
    {
        $this->open = false;
    }

    public function toggleDrawer(): void
    {
        $this->open = !$this->open;
    }

    public function selectedRoute(): string
    {
        return $this->routes[$this->selected - 1]->name;
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
        $screen = View::make($selectedRoute->render())->style(new Style(
            widthPercent: 100.0,
            heightPercent: 100.0,
            flexGrow: 1.0,
        ));
        $routeItem = function (
            NavigationDrawerItem $route,
            int $index,
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
            $row = $route->icon === null
                ? Row::make($label)
                : Row::make($route->icon, $label);

            return Pressable::make($row->style(new Style(
                gap: 16.0,
                alignItems: \Pam\Native\Align::Center,
            )))
                ->onPress(fn (): bool => $this->navigate($route->name))
                ->style(new Style(
                    minHeight: 48.0,
                    paddingHorizontal: 16.0,
                    borderRadius: 4.0,
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
                    minHeight: 44.0,
                    paddingHorizontal: 16.0,
                    marginTop: 4.0,
                    borderRadius: 4.0,
                    justifyContent: \Pam\Native\Justify::Center,
                ))
                ->accessibilityRole(AccessibilityRole::Button)
                ->accessibilityLabel($group);
            if (!$expanded) {
                continue;
            }
            foreach ($groupRoutes as [$route, $index]) {
                $items[] = $routeItem($route, $index)->style(new Style(
                    marginLeft: 8.0,
                    paddingLeft: 12.0,
                ));
            }
        }
        $defaultDrawer = SafeAreaView::make(
            ScrollView::make(
                Column::make(...$items)->style(new Style(
                    padding: 8.0,
                    gap: 4.0,
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
                $this->open = true;
            })
            ->onClose(function (): void {
                $this->open = false;
            });
    }

    public function stateKey(): string
    {
        return 'drawer-navigator.'.$this->persistenceKey;
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
    }

    public function saveState(): array
    {
        return [
            'version' => 1,
            'selected' => $this->selectedRoute(),
            'open' => $this->open,
            'history' => $this->history,
            'expandedGroups' => array_keys(array_filter($this->expandedGroups)),
        ];
    }
}
