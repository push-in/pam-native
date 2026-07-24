<?php

declare(strict_types=1);

namespace Pam\Native\Navigation;

use Closure;
use InvalidArgumentException;
use Pam\Native\Component;
use Pam\Native\Renderable;
use Pam\Native\Restorable;

final class Navigator extends Component implements Restorable
{
    /** @var array<string, Closure(): Renderable> */
    private array $routes;

    /** @var list<array{name: string, id: int}> */
    private array $stack;
    private string $persistenceKey;
    private int $nextId = 2;
    private int $revision = 0;
    private NavigationOperation $operation = NavigationOperation::Idle;
    private ?array $outgoing = null;

    /**
     * @param array<array-key, mixed> $routes
     */
    public function __construct(
        string $initialRoute,
        array $routes,
        string $persistenceKey = 'main',
        private NavigationTransition $transition = NavigationTransition::PlatformDefault,
        private int $transitionDurationMs = 240,
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
        $this->stack = [['name' => $initialRoute, 'id' => 1]];
        $this->persistenceKey = $persistenceKey;
        $this->transitionDurationMs = max(0, min(2_000, $transitionDurationMs));
    }

    public function push(string $route): void
    {
        if (!isset($this->routes[$route])) {
            throw new InvalidArgumentException("Route {$route} is not registered.");
        }

        $this->outgoing = null;
        $this->stack[] = ['name' => $route, 'id' => $this->nextId++];
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

    public function render(): Renderable
    {
        $entries = [];

        if ($this->operation === NavigationOperation::Push && count($this->stack) > 1) {
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
            fn (array $entry): Renderable => ($this->routes[$entry['name']])()
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
        );
    }

    public function canGoBack(): bool
    {
        return count($this->stack) > 1;
    }

    public function replace(string $route): void
    {
        if (!isset($this->routes[$route])) {
            throw new InvalidArgumentException("Route {$route} is not registered.");
        }
        $this->outgoing = array_pop($this->stack);
        $this->stack[] = ['name' => $route, 'id' => $this->nextId++];
        $this->operation = NavigationOperation::Replace;
        $this->revision++;
    }

    public function reset(string $route): void
    {
        if (!isset($this->routes[$route])) {
            throw new InvalidArgumentException("Route {$route} is not registered.");
        }
        $this->outgoing = null;
        $this->stack = [['name' => $route, 'id' => $this->nextId++]];
        $this->operation = NavigationOperation::Reset;
        $this->revision++;
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

        foreach ($stack as $route) {
            if (!is_string($route) || !isset($this->routes[$route])) {
                return;
            }

            $restored[] = ['name' => $route, 'id' => $this->nextId++];
        }
        $this->stack = $restored;
        $this->operation = NavigationOperation::Reset;
        $this->revision++;
    }

    public function saveState(): array
    {
        return ['stack' => array_column($this->stack, 'name')];
    }
}
