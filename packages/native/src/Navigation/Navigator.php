<?php

declare(strict_types=1);

namespace Pam\Native\Navigation;

use Closure;
use InvalidArgumentException;
use Pam\Native\App;
use Pam\Native\Component;
use Pam\Native\Internal\Runtime;
use Pam\Native\NativeOperation;
use Pam\Native\Renderable;
use Pam\Native\Restorable;
use ReflectionFunction;

final class Navigator extends Component implements Restorable
{
    /** @var array<string, Closure(): Renderable> */
    private array $routes;

    /** @var list<array{name: string, id: int, params: array<string, string|int|float|bool|null>}> */
    private array $stack;
    private string $persistenceKey;
    private int $nextId = 2;
    private int $revision = 0;
    private NavigationOperation $operation = NavigationOperation::Idle;
    private ?array $outgoing = null;
    /** @var (Closure(): bool)|null */
    private ?Closure $systemBackInterceptor = null;
    /** @var list<DeepLink> */
    private array $deepLinks;

    /**
     * @param array<array-key, mixed> $routes
     */
    public function __construct(
        string $initialRoute,
        array $routes,
        string $persistenceKey = 'main',
        private NavigationTransition $transition = NavigationTransition::PlatformDefault,
        private int $transitionDurationMs = 240,
        bool $handleSystemBack = true,
        array $deepLinks = [],
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
        foreach ($deepLinks as $link) {
            if (!$link instanceof DeepLink || !isset($validated[$link->route])) {
                throw new InvalidArgumentException('Deep links must target registered routes.');
            }
        }
        $this->deepLinks = array_values($deepLinks);
        $this->stack = [['name' => $initialRoute, 'id' => 1, 'params' => []]];
        $this->persistenceKey = $persistenceKey;
        $this->transitionDurationMs = max(0, min(2_000, $transitionDurationMs));
        if ($handleSystemBack) {
            App::onBack(function (): void {
                if ($this->consumeSystemBack()) {
                    return;
                }

                if (!$this->pop()) {
                    Runtime::callNative(
                        NativeOperation::CloseApp,
                        '',
                        static function (): void {
                        },
                    );
                }
            });
        }
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

    /** @param array<string, string|int|float|bool|null> $params */
    public function push(string $route, array $params = []): void
    {
        if (!isset($this->routes[$route])) {
            throw new InvalidArgumentException("Route {$route} is not registered.");
        }

        $this->outgoing = null;
        $this->stack[] = [
            'name' => $route,
            'id' => $this->nextId++,
            'params' => self::validatedParams($params),
        ];
        $this->operation = NavigationOperation::Push;
        $this->revision++;
    }

    public function pop(): bool
    {
        if (count($this->stack) <= 1) {
            return false;
        }

        $this->outgoing = array_pop($this->stack);
        $this->operation = NavigationOperation::Pop;
        $this->revision++;

        return true;
    }

    public function currentRoute(): string
    {
        return $this->stack[count($this->stack) - 1]['name'];
    }

    public function current(): RouteContext
    {
        $entry = $this->stack[count($this->stack) - 1];

        return new RouteContext($entry['name'], $entry['params']);
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
            fn (array $entry): Renderable => $this->renderRoute($entry)
                ->toElement()
                ->key('navigation.'.$entry['id']),
            $entries,
        );

        return NavigationHost::make(
            $this->operation,
            $this->transition,
            $this->transitionDurationMs,
            $this->revision,
            ...$screens,
        )->onGesturePop(function (): void {
            $this->pop();
        });
    }

    public function canGoBack(): bool
    {
        return count($this->stack) > 1;
    }

    /** @param array<string, string|int|float|bool|null> $params */
    public function replace(string $route, array $params = []): void
    {
        if (!isset($this->routes[$route])) {
            throw new InvalidArgumentException("Route {$route} is not registered.");
        }
        $this->outgoing = array_pop($this->stack);
        $this->stack[] = [
            'name' => $route,
            'id' => $this->nextId++,
            'params' => self::validatedParams($params),
        ];
        $this->operation = NavigationOperation::Replace;
        $this->revision++;
    }

    /** @param array<string, string|int|float|bool|null> $params */
    public function reset(string $route, array $params = []): void
    {
        if (!isset($this->routes[$route])) {
            throw new InvalidArgumentException("Route {$route} is not registered.");
        }
        $this->outgoing = null;
        $this->stack = [[
            'name' => $route,
            'id' => $this->nextId++,
            'params' => self::validatedParams($params),
        ]];
        $this->operation = NavigationOperation::Reset;
        $this->revision++;
    }

    /** @param array<string, string|int|float|bool|null> $params */
    public function navigate(string $route, array $params = []): void
    {
        $target = null;
        for ($index = count($this->stack) - 1; $index >= 0; $index--) {
            if ($this->stack[$index]['name'] === $route) {
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
                $this->stack[$target]['params'] = self::validatedParams($params);
                $this->operation = NavigationOperation::Idle;
                $this->revision++;
            }
            return;
        }
        $this->outgoing = $this->stack[count($this->stack) - 1];
        $this->stack = array_slice($this->stack, 0, $target + 1);
        if ($params !== []) {
            $this->stack[$target]['params'] = self::validatedParams($params);
        }
        $this->operation = NavigationOperation::Pop;
        $this->revision++;
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
        $this->outgoing = $this->stack[count($this->stack) - 1];
        $this->stack = [$this->stack[0]];
        $this->operation = NavigationOperation::Pop;
        $this->revision++;

        return true;
    }

    public function open(string $uri): bool
    {
        if ($uri === '' || strlen($uri) > 8_192) return false;
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
                $this->navigate($link->route, $params);

                return true;
            }
        }

        return false;
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

    public function stateKey(): string
    {
        return 'navigator.'.$this->persistenceKey;
    }

    public function restoreState(array $state): void
    {
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
            $restored[] = [
                'name' => $route,
                'id' => $this->nextId++,
                'params' => $validatedParams,
            ];
        }
        $this->stack = $restored;
        $this->operation = NavigationOperation::Reset;
        $this->revision++;
    }

    public function saveState(): array
    {
        return [
            'version' => 2,
            'stack' => array_map(
                static fn (array $entry): array => [
                    'name' => $entry['name'],
                    'params' => $entry['params'],
                ],
                $this->stack,
            ),
        ];
    }

    /** @param array{name: string, id: int, params: array<string, string|int|float|bool|null>} $entry */
    private function renderRoute(array $entry): Renderable
    {
        $route = $this->routes[$entry['name']];
        $reflection = new ReflectionFunction($route);
        return $reflection->getNumberOfParameters() === 0
            ? $route()
            : $route(new RouteContext($entry['name'], $entry['params']));
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
}
