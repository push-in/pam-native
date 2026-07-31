<?php

declare(strict_types=1);

namespace Pam\Native\Navigation;

use Closure;
use InvalidArgumentException;
use Pam\Native\Component;
use Pam\Native\Renderable;
use Pam\Native\Restorable;
use ReflectionFunction;

final class Navigator extends Component implements Restorable, NavigationStateProvider, NavigationBackHandler, NavigationObservable, NavigationActionHandler
{
    /** @var array<string, Closure(): Renderable> */
    private array $routes;

    /** @var list<array{name: string, id: int, routeId: string|null, params: array<string, string|int|float|bool|null>}> */
    private array $stack;
    private string $persistenceKey;
    private int $nextId = 2;
    private int $revision = 0;
    private NavigationOperation $operation = NavigationOperation::Idle;
    private ?array $outgoing = null;
    private readonly string $navigationKey;
    /** @var array<int, array<int, Closure>> */
    private array $listeners = [];
    /** @var array<string, Renderable> */
    private array $preloaded = [];
    /** @var array<string, Renderable> */
    private array $routeInstances = [];
    private int $nextListenerId = 1;
    private ?string $focusedEntryKey = null;
    /** @var array<string, NavigationSubscription> */
    private array $childSubscriptions = [];
    /** @var array<string, array<string, mixed>> */
    private array $pendingChildState = [];
    /** @var (Closure(): bool)|null */
    private ?Closure $systemBackInterceptor = null;
    /** @var list<DeepLink> */
    private array $deepLinks;
    /** @var array<string, ScreenOptions|ScreenOptionsPatch|Closure> */
    private array $screenOptions;
    /** @var array<string, Closure> */
    private array $routeIds;
    /** @var array<string, Closure> */
    private array $routeGuards;
    /** @var array<string, ScreenOptions|ScreenOptionsPatch> */
    private array $dynamicOptions = [];
    private ScreenOptions|Closure|null $defaultOptions;
    /** @var list<array{routes: list<string>, options: ScreenOptionsPatch|Closure}> */
    private array $optionGroups;
    /** @var list<string> */
    private array $linkingPrefixes;
    /** @var (Closure(string): bool)|null */
    private ?Closure $linkFilter;
    private NavigationTheme $theme;

    /**
     * @param array<array-key, mixed> $routes
     */
    public function __construct(
        string $initialRoute,
        array $routes,
        string $persistenceKey = 'main',
        private NavigationTransition $transition = NavigationTransition::PlatformDefault,
        private int $transitionDurationMs = 240,
        private readonly bool $handleSystemBack = true,
        array $deepLinks = [],
        private readonly bool $restorePersistedState = true,
        array $screenOptions = [],
        array $linkingPrefixes = [],
        ?Closure $linkFilter = null,
        array $routeIds = [],
        array $routeGuards = [],
        private readonly ?string $guardFallback = null,
        ScreenOptions|Closure|null $defaultOptions = null,
        array $optionGroups = [],
    )
    {
        $validated = [];

        foreach ($routes as $name => $route) {
            if (!is_string($name) || $name === '' || !$route instanceof Closure) {
                throw new InvalidArgumentException('Routes require non-empty names and Closure handlers.');
            }

            $validated[$name] = $route;
        }

        if (!isset($validated[$initialRoute])) {
            throw new InvalidArgumentException("Initial route {$initialRoute} is not registered.");
        }
        if (preg_match('/^[A-Za-z][A-Za-z0-9_.-]{0,63}$/', $persistenceKey) !== 1) {
            throw new InvalidArgumentException('Navigator persistence keys must be safe identifiers.');
        }

        $this->routes = $validated;
        $this->navigationKey = 'stack.'.$persistenceKey;
        foreach ($deepLinks as $link) {
            if (!$link instanceof DeepLink || !isset($validated[$link->route])) {
                throw new InvalidArgumentException('Deep links must target registered routes.');
            }
        }
        $this->deepLinks = array_values($deepLinks);
        foreach ($screenOptions as $route => $options) {
            if (!is_string($route) || !isset($validated[$route]) || (!$options instanceof ScreenOptions && !$options instanceof ScreenOptionsPatch && !$options instanceof Closure)) {
                throw new InvalidArgumentException('Screen options must target a registered route and be options or a resolver.');
            }
        }
        $this->screenOptions = $screenOptions;
        foreach ($optionGroups as $group) {
            if (!is_array($group) || !is_array($group['routes'] ?? null) || !isset($group['options'])) {
                throw new InvalidArgumentException('Option groups require routes and an option layer.');
            }
            foreach ($group['routes'] as $route) {
                if (!is_string($route) || !isset($validated[$route])) {
                    throw new InvalidArgumentException('Option groups must target registered routes.');
                }
            }
            if (!$group['options'] instanceof ScreenOptionsPatch && !$group['options'] instanceof Closure) {
                throw new InvalidArgumentException('Option groups require sparse options or a resolver.');
            }
        }
        $this->defaultOptions = $defaultOptions;
        $this->optionGroups = array_values($optionGroups);
        foreach ($routeIds as $route => $getId) {
            if (!is_string($route) || !isset($validated[$route]) || !$getId instanceof Closure) {
                throw new InvalidArgumentException('Route identity resolvers must target a registered route.');
            }
        }
        $this->routeIds = $routeIds;
        foreach ($routeGuards as $route => $guard) {
            if (!is_string($route) || !isset($validated[$route]) || !$guard instanceof Closure) {
                throw new InvalidArgumentException('Route guards must target a registered route.');
            }
        }
        if ($guardFallback !== null && !isset($validated[$guardFallback])) {
            throw new InvalidArgumentException('The route guard fallback must be registered.');
        }
        $this->routeGuards = $routeGuards;
        foreach ($linkingPrefixes as $prefix) {
            if (!is_string($prefix) || strlen($prefix) > 512 || preg_match('#^[A-Za-z][A-Za-z0-9+.-]*://#', $prefix) !== 1) {
                throw new InvalidArgumentException('Linking prefixes require bounded absolute URI prefixes.');
            }
        }
        $this->linkingPrefixes = array_values(array_unique($linkingPrefixes));
        $this->linkFilter = $linkFilter;
        $this->theme = NavigationTheme::light();
        $bootRoute = $this->routeAvailable($initialRoute)
            ? $initialRoute
            : $guardFallback;
        if ($bootRoute === null || !$this->routeAvailable($bootRoute)) {
            throw new InvalidArgumentException('The initial route is guarded and no available fallback exists.');
        }
        $this->stack = [[
            'name' => $bootRoute,
            'id' => 1,
            'routeId' => $this->resolveRouteId($bootRoute, []),
            'params' => [],
        ]];
        $this->persistenceKey = $persistenceKey;
        $this->transitionDurationMs = max(0, min(2_000, $transitionDurationMs));
        if ($handleSystemBack) NavigationBackCoordinator::register($this);
    }

    /**
     * Runs before Android system Back changes the navigation stack.
     *
     * Return true from the interceptor after dismissing transient UI such as
     * selection, editing, search, or an in-screen viewer. Returning false lets
     * the navigator pop the current route normally.
     *
     * @param (Closure(): bool)|null $interceptor
     */
    public function interceptSystemBack(?Closure $interceptor): self
    {
        $this->systemBackInterceptor = $interceptor;

        return $this;
    }

    public function consumeSystemBack(): bool
    {
        return $this->systemBackInterceptor !== null
            && ($this->systemBackInterceptor)() === true;
    }

    public function claimSystemBackRoot(): void
    {
        if ($this->handleSystemBack) NavigationBackCoordinator::register($this, true);
    }

    public function dispatchSystemBack(): bool
    {
        return $this->consumeSystemBack() || $this->goBack();
    }

    /** @param array<string, string|int|float|bool|null> $params */
    public function push(string $route, array $params = []): void
    {
        if (!isset($this->routes[$route])) {
            throw new InvalidArgumentException("Route {$route} is not registered.");
        }

        $validatedParams = self::validatedParams($params);
        if (!$this->routeAvailable($route, $validatedParams)) {
            throw new InvalidArgumentException("Route {$route} is not currently available.");
        }
        $previous = $this->currentEntry();
        $this->outgoing = null;
        $this->stack[] = [
            'name' => $route,
            'id' => $this->nextId++,
            'routeId' => $this->resolveRouteId($route, $validatedParams),
            'params' => $validatedParams,
        ];
        $this->operation = NavigationOperation::Push;
        $this->revision++;
        $this->didNavigate($previous, NavigationAction::push($route, $params));
    }

    public function pop(): bool
    {
        if (count($this->stack) <= 1) {
            return false;
        }

        $previous = $this->currentEntry();
        if (!$this->mayRemove($previous, NavigationAction::pop())) return false;
        $this->outgoing = array_pop($this->stack);
        $this->operation = NavigationOperation::Pop;
        $this->revision++;
        $this->didNavigate($previous, NavigationAction::pop());

        return true;
    }

    public function currentRoute(): string
    {
        return $this->stack[count($this->stack) - 1]['name'];
    }

    public function current(): RouteContext
    {
        $entry = $this->stack[count($this->stack) - 1];

        return $this->contextFor($entry);
    }

    public function key(): string
    {
        return $this->navigationKey;
    }

    public function currentOptions(): ScreenOptions
    {
        $entry = $this->currentEntry();
        return $this->resolvedOptions($entry);
    }

    public function setOptions(ScreenOptions|ScreenOptionsPatch $options): void
    {
        $this->dynamicOptions[$this->entryKey($this->currentEntry())] = $options;
        $this->emitNavigation(NavigationEventType::State, [
            'state' => $this->getState(),
            'options' => $this->currentOptions()->toArray(),
        ]);
    }

    public function clearOptions(): void
    {
        unset($this->dynamicOptions[$this->entryKey($this->currentEntry())]);
        $this->emitNavigation(NavigationEventType::State, [
            'state' => $this->getState(),
            'options' => $this->currentOptions()->toArray(),
        ]);
    }

    public function theme(NavigationTheme $theme): void
    {
        $this->theme = $theme;
        $this->revision++;
    }

    public function isFocused(): bool
    {
        return true;
    }

    /** @return array<string, mixed> */
    public function getState(): array
    {
        return [
            'version' => 4,
            'type' => 1,
            'key' => $this->navigationKey,
            'index' => count($this->stack) - 1,
            'routes' => array_map(function (array $entry): array {
                $key = $this->entryKey($entry);
                $route = [
                    'key' => $key,
                    'name' => $entry['name'],
                    'params' => $entry['params'],
                    'id' => $entry['routeId'],
                    'options' => $this->resolvedOptions($entry)->toArray(),
                ];
                $instance = $this->routeInstances[$key] ?? null;
                if ($instance instanceof NavigationStateProvider) {
                    $route['state'] = $instance->getState();
                }
                return $route;
            }, $this->stack),
        ];
    }

    public function addListener(NavigationEventType $type, Closure $listener): NavigationSubscription
    {
        $id = $this->nextListenerId++;
        $this->listeners[$type->value][$id] = $listener;

        return new NavigationSubscription(function () use ($type, $id): void {
            unset($this->listeners[$type->value][$id]);
        });
    }

    public function dispatch(NavigationAction $action): bool
    {
        if ($action->target !== null && $action->target !== $this->navigationKey) {
            if ($this->activeChildActionHandler()?->dispatch($action) === true) return true;
            $this->emitNavigation(NavigationEventType::UnhandledAction, ['action' => $action->toArray()]);
            return false;
        }
        $this->emitNavigation(NavigationEventType::Action, ['action' => $action->toArray()]);
        $handled = match ($action->type) {
            NavigationActionType::Navigate => $this->dispatchRoute($action, fn () => $this->navigate($action->route ?? '', $action->params, $action->merge)),
            NavigationActionType::Push => $this->dispatchRoute($action, fn () => $this->push($action->route ?? '', $action->params)),
            NavigationActionType::Replace => $this->dispatchRoute($action, fn () => $this->replace($action->route ?? '', $action->params)),
            NavigationActionType::Reset => $this->dispatchRoute($action, fn () => $this->reset($action->route ?? '', $action->params)),
            NavigationActionType::Pop, NavigationActionType::GoBack => $this->pop(),
            NavigationActionType::PopTo => $this->popTo($action->route ?? ''),
            NavigationActionType::PopToTop => $this->popToTop(),
            NavigationActionType::SetParams => $this->setParams($action->params),
            NavigationActionType::ReplaceParams => $this->replaceParams($action->params),
            NavigationActionType::Preload => $this->preload($action->route ?? '', $action->params),
        };
        if ($handled) return true;
        if ($this->activeChildActionHandler()?->dispatch($action) === true) return true;
        $this->emitNavigation(NavigationEventType::UnhandledAction, ['action' => $action->toArray()]);
        return false;
    }

    public function render(): Renderable
    {
        $entries = [];

        if ($this->operation === NavigationOperation::Push && count($this->stack) > 1) {
            $entries[] = $this->stack[count($this->stack) - 2];
        }
        if ($this->operation === NavigationOperation::Idle && count($this->stack) > 1) {
            $entries[] = $this->stack[count($this->stack) - 2];
        }
        if ($this->operation === NavigationOperation::Replace && $this->outgoing !== null) {
            $entries[] = $this->outgoing;
        }
        $entries[] = $this->stack[count($this->stack) - 1];
        if ($this->operation === NavigationOperation::Pop && $this->outgoing !== null) {
            $entries[] = $this->outgoing;
        }

        $screens = array_map(
            fn (array $entry): Renderable => $this->decorateRoute($entry)
                ->toElement()
                ->key('navigation.'.$entry['id']),
            $entries,
        );

        $activeOptions = $this->currentOptions();
        return NavigationHost::make(
            $this->operation,
            $activeOptions->animation === NavigationTransition::PlatformDefault
                ? $this->transition
                : $activeOptions->animation,
            $activeOptions->animationDurationMs ?? $this->transitionDurationMs,
            $this->revision,
            ...$screens,
        )->gestureNavigation(
            $activeOptions->gestureEnabled,
            direction: $activeOptions->gestureDirection,
            fullScreen: $activeOptions->fullScreenGestureEnabled,
        )
        ->screenBehavior($activeOptions->orientation, $activeOptions->autoHideHomeIndicator)
        ->screenOptions($activeOptions)
        ->onTransitionEnd(function (): void {
            $this->finalizeOutgoingRoute();
            $this->emitNavigation(NavigationEventType::TransitionEnd, ['route' => $this->current()]);
        })
        ->onGestureStart(function (): void {
            $this->emitNavigation(NavigationEventType::GestureStart, ['route' => $this->current()]);
        })
        ->onGestureEnd(function (): void {
            $this->emitNavigation(NavigationEventType::GestureEnd, ['route' => $this->current()]);
        })
        ->onGestureCancel(function (): void {
            $this->emitNavigation(NavigationEventType::GestureCancel, ['route' => $this->current()]);
        })
        ->onGesturePop(function (): void {
            $this->pop();
        });
    }

    public function canGoBack(): bool
    {
        $child = $this->activeChildBackHandler();
        return $child?->canGoBack() === true || count($this->stack) > 1;
    }

    public function goBack(): bool
    {
        $child = $this->activeChildBackHandler();
        if ($child?->canGoBack() === true) return $child->goBack();
        return $this->pop();
    }

    /** @param array<string, string|int|float|bool|null> $params */
    public function replace(string $route, array $params = []): void
    {
        if (!isset($this->routes[$route])) {
            throw new InvalidArgumentException("Route {$route} is not registered.");
        }
        $validatedParams = self::validatedParams($params);
        if (!$this->routeAvailable($route, $validatedParams)) {
            throw new InvalidArgumentException("Route {$route} is not currently available.");
        }
        $previous = $this->currentEntry();
        if (!$this->mayRemove($previous, new NavigationAction(NavigationActionType::Replace, $route, $params))) return;
        $this->outgoing = array_pop($this->stack);
        $this->stack[] = [
            'name' => $route,
            'id' => $this->nextId++,
            'routeId' => $this->resolveRouteId($route, $validatedParams),
            'params' => $validatedParams,
        ];
        $this->operation = NavigationOperation::Replace;
        $this->revision++;
        $this->didNavigate($previous, new NavigationAction(NavigationActionType::Replace, $route, $params));
    }

    /** @param array<string, string|int|float|bool|null> $params */
    public function reset(string $route, array $params = []): void
    {
        if (!isset($this->routes[$route])) {
            throw new InvalidArgumentException("Route {$route} is not registered.");
        }
        $validatedParams = self::validatedParams($params);
        if (!$this->routeAvailable($route, $validatedParams)) {
            throw new InvalidArgumentException("Route {$route} is not currently available.");
        }
        $previous = $this->currentEntry();
        if (!$this->mayRemove($previous, new NavigationAction(NavigationActionType::Reset, $route, $params))) return;
        $this->outgoing = null;
        $this->stack = [[
            'name' => $route,
            'id' => $this->nextId++,
            'routeId' => $this->resolveRouteId($route, $validatedParams),
            'params' => $validatedParams,
        ]];
        $this->operation = NavigationOperation::Reset;
        $this->revision++;
        $this->didNavigate($previous, new NavigationAction(NavigationActionType::Reset, $route, $params));
    }

    /** @param array<string, string|int|float|bool|null> $params */
    public function navigate(string $route, array $params = [], bool $merge = false): void
    {
        if (!isset($this->routes[$route])) {
            throw new InvalidArgumentException("Route {$route} is not registered.");
        }
        $validatedParams = self::validatedParams($params);
        if (!$this->routeAvailable($route, $validatedParams)) return;
        $routeId = $this->resolveRouteId($route, $validatedParams);
        $previous = $this->currentEntry();
        $target = null;
        for ($index = count($this->stack) - 1; $index >= 0; $index--) {
            if (
                $this->stack[$index]['name'] === $route
                && ($routeId === null || $this->stack[$index]['routeId'] === $routeId)
            ) {
                $target = $index;
                break;
            }
        }
        if ($target === null) {
            $this->push($route, $params);
            return;
        }
        if ($target === count($this->stack) - 1) {
            if ($params !== []) {
                $this->stack[$target]['params'] = $merge
                    ? array_replace($this->stack[$target]['params'], $validatedParams)
                    : $validatedParams;
                $this->stack[$target]['routeId'] = $this->resolveRouteId(
                    $route,
                    $this->stack[$target]['params'],
                );
                unset($this->routeInstances[$this->entryKey($this->stack[$target])]);
                $this->operation = NavigationOperation::Idle;
                $this->revision++;
                $this->emitNavigation(NavigationEventType::ParamsChanged, ['params' => $this->stack[$target]['params']]);
                $this->emitNavigation(NavigationEventType::State, ['state' => $this->getState()]);
            }
            return;
        }
        if (!$this->mayRemove($previous, NavigationAction::navigate($route, $params, $merge))) return;
        $this->outgoing = $this->stack[count($this->stack) - 1];
        $this->stack = array_slice($this->stack, 0, $target + 1);
        if ($params !== []) {
            $this->stack[$target]['params'] = $merge
                ? array_replace($this->stack[$target]['params'], $validatedParams)
                : $validatedParams;
            $this->stack[$target]['routeId'] = $this->resolveRouteId(
                $route,
                $this->stack[$target]['params'],
            );
            unset($this->routeInstances[$this->entryKey($this->stack[$target])]);
        }
        $this->operation = NavigationOperation::Pop;
        $this->revision++;
        $this->didNavigate($previous, NavigationAction::navigate($route, $params, $merge));
    }

    /** @param array<string, string|int|float|bool|null> $params */
    public function setParams(array $params): bool
    {
        $index = count($this->stack) - 1;
        $this->stack[$index]['params'] = array_replace(
            $this->stack[$index]['params'],
            self::validatedParams($params),
        );
        $this->stack[$index]['routeId'] = $this->resolveRouteId(
            $this->stack[$index]['name'],
            $this->stack[$index]['params'],
        );
        unset($this->routeInstances[$this->entryKey($this->stack[$index])]);
        $this->revision++;
        $this->emitNavigation(NavigationEventType::ParamsChanged, ['params' => $this->stack[$index]['params']]);
        $this->emitNavigation(NavigationEventType::State, ['state' => $this->getState()]);
        return true;
    }

    /** @param array<string, string|int|float|bool|null> $params */
    public function replaceParams(array $params): bool
    {
        $index = count($this->stack) - 1;
        $this->stack[$index]['params'] = self::validatedParams($params);
        $this->stack[$index]['routeId'] = $this->resolveRouteId(
            $this->stack[$index]['name'],
            $this->stack[$index]['params'],
        );
        unset($this->routeInstances[$this->entryKey($this->stack[$index])]);
        $this->revision++;
        $this->emitNavigation(NavigationEventType::ParamsChanged, ['params' => $this->stack[$index]['params']]);
        $this->emitNavigation(NavigationEventType::State, ['state' => $this->getState()]);
        return true;
    }

    /** @param array<string, string|int|float|bool|null> $params */
    public function preload(string $route, array $params = []): bool
    {
        if (!isset($this->routes[$route])) return false;
        $validatedParams = self::validatedParams($params);
        $entry = [
            'name' => $route,
            'id' => 0,
            'routeId' => $this->resolveRouteId($route, $validatedParams),
            'params' => $validatedParams,
        ];
        $this->preloaded[$this->preloadKey($route, $entry['params'])] = $this->createRoute($entry);
        return true;
    }

    public function popTo(string $route): bool
    {
        if ($route === $this->currentRoute()) {
            return true;
        }
        $before = count($this->stack);
        $this->navigate($route);

        return count($this->stack) < $before;
    }

    public function popToTop(): bool
    {
        if (count($this->stack) <= 1) return false;
        $previous = $this->currentEntry();
        $action = NavigationAction::popToTop();
        if (!$this->mayRemove($previous, $action)) return false;
        $this->outgoing = $previous;
        $this->stack = [$this->stack[0]];
        $this->operation = NavigationOperation::Pop;
        $this->revision++;
        $this->didNavigate($previous, $action);

        return true;
    }

    public function open(string $uri): bool
    {
        if ($uri === '' || strlen($uri) > 8_192) return false;
        if ($this->linkFilter !== null && !($this->linkFilter)($uri)) return false;
        if (
            $this->linkingPrefixes !== []
            && !array_any($this->linkingPrefixes, static fn (string $prefix): bool => str_starts_with($uri, $prefix))
        ) return false;
        $parts = parse_url($uri);
        if ($parts === false) return false;
        $path = $parts['path'] ?? '/';
        $scheme = strtolower((string) ($parts['scheme'] ?? ''));
        $paths = [$path];
        if ($scheme !== '' && !in_array($scheme, ['http', 'https'], true)) {
            $host = trim((string) ($parts['host'] ?? ''), '/');
            if ($host !== '') {
                $paths[] = '/'.$host.($path === '/' ? '' : $path);
            }
        }
        foreach (array_unique($paths) as $candidate) {
            foreach ($this->deepLinks as $link) {
                $params = $link->match($candidate);
                if ($params === null) continue;
                if (isset($parts['query'])) {
                    parse_str($parts['query'], $query);
                    foreach ($query as $key => $value) {
                        if (is_string($key) && is_scalar($value)) {
                            $params[$key] = (string) $value;
                        }
                    }
                }
                if (!$this->routeAvailable($link->route, $params)) return false;
                $this->navigate($link->route, $params);
                return true;
            }
        }

        return false;
    }

    public function currentPath(): ?string
    {
        $entry = $this->currentEntry();
        foreach ($this->deepLinks as $link) {
            if ($link->route === $entry['name']) {
                return $link->build($entry['params']);
            }
        }
        return null;
    }

    public function currentUrl(): ?string
    {
        $path = $this->currentPath();
        if ($path === null) return null;
        $prefix = $this->linkingPrefixes[0] ?? null;
        return $prefix === null ? $path : rtrim($prefix, '/').$path;
    }

    public function transition(
        NavigationTransition $transition,
        ?int $durationMs = null,
    ): void {
        $this->transition = $transition;
        if ($durationMs !== null) {
            $this->transitionDurationMs = max(0, min(2_000, $durationMs));
        }
    }

    /** @param array<string, string|int|float|bool|null> $params */
    public function routeAvailable(string $route, array $params = []): bool
    {
        if (!isset($this->routes[$route])) return false;
        $guard = $this->routeGuards[$route] ?? null;
        return $guard === null || $guard(new RouteContext($route, self::validatedParams($params))) === true;
    }

    /**
     * Re-evaluates auth/feature conditions and removes inaccessible history in
     * one state revision. Call after the session or entitlement state changes.
     */
    public function refreshConditions(): bool
    {
        $previous = $this->currentEntry();
        $available = array_values(array_filter(
            $this->stack,
            fn (array $entry): bool => $this->routeAvailable($entry['name'], $entry['params']),
        ));
        if ($available === []) {
            $fallback = $this->guardFallback;
            if ($fallback === null || !$this->routeAvailable($fallback)) return false;
            $available[] = [
                'name' => $fallback,
                'id' => $this->nextId++,
                'routeId' => $this->resolveRouteId($fallback, []),
                'params' => [],
            ];
        }
        if ($available === $this->stack) return false;
        $this->stack = $available;
        $this->outgoing = null;
        $this->operation = NavigationOperation::Reset;
        $this->revision++;
        $this->didNavigate($previous, NavigationAction::reset($this->currentRoute(), $this->current()->all()));
        return true;
    }

    public function stateKey(): string
    {
        return 'navigator.'.$this->persistenceKey;
    }

    public function restoreState(array $state): void
    {
        $this->stateWasRestored();
        if (!$this->restorePersistedState) {
            return;
        }

        if (($state['version'] ?? 1) >= 3) {
            $checksum = $state['checksum'] ?? null;
            if (!is_string($checksum) || !hash_equals($checksum, self::stateChecksum($state['stack'] ?? null))) {
                return;
            }
        }

        $stack = $state['stack'] ?? null;

        if (!is_array($stack) || $stack === []) {
            return;
        }
        $restored = [];

        foreach ($stack as $saved) {
            $route = is_string($saved) ? $saved : ($saved['name'] ?? null);
            $params = is_array($saved) ? ($saved['params'] ?? []) : [];
            if (!is_string($route) || !isset($this->routes[$route]) || !is_array($params)) {
                return;
            }
            try {
                $validatedParams = self::validatedParams($params);
            } catch (InvalidArgumentException) {
                return;
            }
            if (!$this->routeAvailable($route, $validatedParams)) continue;
            $entry = [
                'name' => $route,
                'id' => $this->nextId++,
                'routeId' => $this->resolveRouteId($route, $validatedParams),
                'params' => $validatedParams,
            ];
            $restored[] = $entry;
            if (is_array($saved) && is_array($saved['state'] ?? null)) {
                $this->pendingChildState[$this->entryKey($entry)] = $saved['state'];
            }
        }
        if ($restored === []) {
            $fallback = $this->guardFallback;
            if ($fallback === null || !$this->routeAvailable($fallback)) return;
            $restored[] = [
                'name' => $fallback,
                'id' => $this->nextId++,
                'routeId' => $this->resolveRouteId($fallback, []),
                'params' => [],
            ];
        }
        $this->stack = $restored;
        $this->operation = NavigationOperation::Reset;
        $this->revision++;
    }

    public function saveState(): array
    {
        $stack = array_map(
            function (array $entry): array {
                $saved = [
                'name' => $entry['name'],
                'id' => $entry['routeId'],
                'params' => $entry['params'],
                ];
                $instance = $this->routeInstances[$this->entryKey($entry)] ?? null;
                if ($instance instanceof Restorable) $saved['state'] = $instance->saveState();
                return $saved;
            },
            $this->stack,
        );
        return [
            'version' => 4,
            'stack' => $stack,
            'checksum' => self::stateChecksum($stack),
        ];
    }

    /** @param array{name: string, id: int, params: array<string, string|int|float|bool|null>} $entry */
    private function renderRoute(array $entry): Renderable
    {
        $key = $this->entryKey($entry);
        if (isset($this->routeInstances[$key])) {
            $this->notifyRouteFocused($entry, $this->routeInstances[$key]);
            return $this->routeInstances[$key];
        }
        $preloadKey = $this->preloadKey($entry['name'], $entry['params']);
        if (isset($this->preloaded[$preloadKey])) {
            $renderable = $this->preloaded[$preloadKey];
            unset($this->preloaded[$preloadKey]);
            $this->routeInstances[$key] = $renderable;
            $this->observeChildNavigator($key, $renderable);
            $this->notifyRouteFocused($entry, $renderable);
            return $renderable;
        }
        $renderable = $this->createRoute($entry);
        $pendingState = $this->pendingChildState[$key] ?? null;
        if ($pendingState !== null && $renderable instanceof Restorable) {
            $renderable->restoreState($pendingState);
            unset($this->pendingChildState[$key]);
        }
        $this->routeInstances[$key] = $renderable;
        $this->observeChildNavigator($key, $renderable);
        $this->notifyRouteFocused($entry, $renderable);
        return $renderable;
    }

    /** @param array{name: string, id: int, params: array<string, string|int|float|bool|null>} $entry */
    private function createRoute(array $entry): Renderable
    {
        $route = $this->routes[$entry['name']];
        $reflection = new ReflectionFunction($route);
        return $reflection->getNumberOfParameters() === 0
            ? $route()
            : $route($this->contextFor($entry));
    }

    /** @param array{name: string, id: int, params: array<string, string|int|float|bool|null>} $entry */
    private function decorateRoute(array $entry): Renderable
    {
        $options = $this->resolvedOptions($entry);
        return new NavigationScreen(
            $this->renderRoute($entry),
            $options,
            $this->theme,
            count($this->stack) > 1,
            fn (): bool => $this->pop(),
        );
    }

    /** @param array{name: string, id: int, params: array<string, string|int|float|bool|null>} $entry */
    private function contextFor(array $entry): RouteContext
    {
        return new RouteContext($entry['name'], $entry['params'], $this->entryKey($entry));
    }

    /** @param array{name: string, id: int, params: array<string, string|int|float|bool|null>} $entry */
    private function entryKey(array $entry): string
    {
        return $entry['name'].'-'.$entry['id'];
    }

    /** @param array{name: string, id: int, routeId: string|null, params: array<string, string|int|float|bool|null>} $entry */
    private function resolvedOptions(array $entry): ScreenOptions
    {
        $resolved = new ScreenOptions();
        if ($this->defaultOptions !== null) {
            $resolved = $this->applyOptionLayer($this->defaultOptions, $entry, $resolved);
        }
        foreach ($this->optionGroups as $group) {
            if (in_array($entry['name'], $group['routes'], true)) {
                $resolved = $this->applyOptionLayer($group['options'], $entry, $resolved);
            }
        }
        $route = $this->screenOptions[$entry['name']] ?? null;
        if ($route !== null) $resolved = $this->applyOptionLayer($route, $entry, $resolved);
        $dynamic = $this->dynamicOptions[$this->entryKey($entry)] ?? null;
        if ($dynamic !== null) {
            $resolved = $this->applyOptionLayer($dynamic, $entry, $resolved);
        }
        return $resolved;
    }

    /** @param array{name: string, id: int, routeId: string|null, params: array<string, string|int|float|bool|null>} $entry */
    private function applyOptionLayer(
        ScreenOptions|ScreenOptionsPatch|Closure $layer,
        array $entry,
        ScreenOptions $inherited,
    ): ScreenOptions {
        if ($layer instanceof Closure) {
            $reflection = new ReflectionFunction($layer);
            $layer = match ($reflection->getNumberOfParameters()) {
                0 => $layer(),
                1 => $layer($this->contextFor($entry)),
                default => $layer($this->contextFor($entry), $inherited),
            };
        }
        if ($layer instanceof ScreenOptionsPatch) return $layer->apply($inherited);
        if ($layer instanceof ScreenOptions) return $layer;
        throw new InvalidArgumentException(
            'Screen option resolvers must return ScreenOptions or ScreenOptionsPatch.',
        );
    }

    /** @param array<string, string|int|float|bool|null> $params */
    private function resolveRouteId(string $route, array $params): ?string
    {
        $resolver = $this->routeIds[$route] ?? null;
        if ($resolver === null) return null;
        $reflection = new ReflectionFunction($resolver);
        $resolved = $reflection->getNumberOfParameters() === 0
            ? $resolver()
            : $resolver(new RouteContext($route, $params));
        if (!is_string($resolved) && !is_int($resolved)) {
            throw new InvalidArgumentException('Route identity resolvers must return a bounded string or integer.');
        }
        $identity = (string) $resolved;
        if ($identity === '' || strlen($identity) > 256) {
            throw new InvalidArgumentException('Route identities must contain between 1 and 256 bytes.');
        }
        return $identity;
    }

    /** @param array<string, string|int|float|bool|null> $params */
    private function preloadKey(string $route, array $params): string
    {
        return $route.'.'.hash('xxh3', serialize($params));
    }

    /** @return array{name: string, id: int, params: array<string, string|int|float|bool|null>} */
    private function currentEntry(): array
    {
        return $this->stack[count($this->stack) - 1];
    }

    private function mayRemove(array $entry, NavigationAction $action): bool
    {
        $instance = $this->routeInstances[$this->entryKey($entry)] ?? null;
        if (
            $instance instanceof NavigationLifecycleAware
            && !$instance->navigationBeforeRemove($this->contextFor($entry), $action)
        ) return false;
        $event = $this->emitNavigation(
            NavigationEventType::BeforeRemove,
            ['action' => $action->toArray(), 'route' => $this->contextFor($entry)],
            true,
            $this->entryKey($entry),
        );
        return !$event->isDefaultPrevented();
    }

    private function didNavigate(array $previous, NavigationAction $action): void
    {
        $current = $this->currentEntry();
        if ($this->entryKey($previous) !== $this->entryKey($current)) {
            $previousInstance = $this->routeInstances[$this->entryKey($previous)] ?? null;
            if ($previousInstance instanceof NavigationLifecycleAware) {
                $previousInstance->navigationBlurred($this->contextFor($previous));
            }
            $this->focusedEntryKey = null;
            $this->emitNavigation(NavigationEventType::Blur, ['route' => $this->contextFor($previous)], false, $this->entryKey($previous));
            $this->emitNavigation(NavigationEventType::Focus, ['route' => $this->contextFor($current)], false, $this->entryKey($current));
        }
        $this->pruneRouteInstances();
        $this->emitNavigation(NavigationEventType::TransitionStart, [
            'action' => $action->toArray(),
            'from' => $this->contextFor($previous),
            'to' => $this->contextFor($current),
        ]);
        $this->emitNavigation(NavigationEventType::State, ['state' => $this->getState(), 'action' => $action->toArray()]);
    }

    private function pruneRouteInstances(): void
    {
        $retained = [];
        foreach ($this->stack as $entry) $retained[$this->entryKey($entry)] = true;
        if ($this->outgoing !== null) $retained[$this->entryKey($this->outgoing)] = true;
        foreach (array_keys($this->routeInstances) as $key) {
            if (!isset($retained[$key])) {
                unset(
                    $this->routeInstances[$key],
                    $this->childSubscriptions[$key],
                    $this->pendingChildState[$key],
                );
            }
        }
    }

    private function emitNavigation(
        NavigationEventType $type,
        array $data = [],
        bool $canPreventDefault = false,
        ?string $target = null,
    ): NavigationEvent {
        $event = new NavigationEvent($type, $target ?? $this->entryKey($this->currentEntry()), $data, $canPreventDefault);
        foreach ($this->listeners[$type->value] ?? [] as $listener) {
            $listener($event);
        }
        return $event;
    }

    private function dispatchRoute(NavigationAction $action, Closure $operation): bool
    {
        if ($action->route === null || !isset($this->routes[$action->route])) {
            return false;
        }
        if (!$this->routeAvailable($action->route, $action->params)) return false;
        $operation();
        return true;
    }

    private function activeChildBackHandler(): ?NavigationBackHandler
    {
        $instance = $this->routeInstances[$this->entryKey($this->currentEntry())] ?? null;
        return $instance instanceof NavigationBackHandler ? $instance : null;
    }

    private function activeChildActionHandler(): ?NavigationActionHandler
    {
        $entry = $this->currentEntry();
        $instance = $this->routeInstances[$this->entryKey($entry)] ?? $this->renderRoute($entry);
        return $instance instanceof NavigationActionHandler ? $instance : null;
    }

    private function notifyRouteFocused(array $entry, Renderable $instance): void
    {
        $key = $this->entryKey($entry);
        if ($key !== $this->entryKey($this->currentEntry()) || $this->focusedEntryKey === $key) return;
        $this->focusedEntryKey = $key;
        if ($instance instanceof NavigationLifecycleAware) {
            $instance->navigationFocused($this->contextFor($entry));
        }
    }

    private function finalizeOutgoingRoute(): void
    {
        $entry = $this->outgoing;
        if ($entry === null) return;
        $key = $this->entryKey($entry);
        $instance = $this->routeInstances[$key] ?? null;
        if ($instance instanceof NavigationLifecycleAware) {
            $instance->navigationRemoved($this->contextFor($entry));
        }
        if (!array_any($this->stack, fn (array $candidate): bool => $this->entryKey($candidate) === $key)) {
            unset(
                $this->routeInstances[$key],
                $this->childSubscriptions[$key],
                $this->pendingChildState[$key],
            );
        }
        $this->outgoing = null;
    }

    private function observeChildNavigator(string $key, Renderable $instance): void
    {
        if (!$instance instanceof NavigationObservable || isset($this->childSubscriptions[$key])) return;
        $this->childSubscriptions[$key] = $instance->addListener(
            NavigationEventType::State,
            function (): void {
                $this->emitNavigation(NavigationEventType::State, ['state' => $this->getState()]);
            },
        );
    }

    /** @param array<array-key, mixed> $params
     *  @return array<string, string|int|float|bool|null>
     */
    private static function validatedParams(array $params): array
    {
        if (count($params) > 64) {
            throw new InvalidArgumentException('Routes cannot contain more than 64 parameters.');
        }
        $validated = [];
        foreach ($params as $key => $value) {
            if (
                !is_string($key)
                || preg_match('/^[A-Za-z][A-Za-z0-9_.-]{0,63}$/', $key) !== 1
                || !is_scalar($value) && $value !== null
                || is_string($value) && strlen($value) > 16_384
            ) {
                throw new InvalidArgumentException('Route parameters require safe string keys and bounded scalar values.');
            }
            $validated[$key] = $value;
        }

        return $validated;
    }

    private static function stateChecksum(mixed $stack): string
    {
        return hash('xxh3', serialize($stack));
    }
}
