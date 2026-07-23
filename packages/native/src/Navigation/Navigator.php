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

    /** @var list<string> */
    private array $stack;
    private string $persistenceKey;

    /**
     * @param array<array-key, mixed> $routes
     */
    public function __construct(
        string $initialRoute,
        array $routes,
        string $persistenceKey = 'main',
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
        $this->stack = [$initialRoute];
        $this->persistenceKey = $persistenceKey;
    }

    public function push(string $route): void
    {
        if (!isset($this->routes[$route])) {
            throw new InvalidArgumentException("Route {$route} is not registered.");
        }

        $this->stack[] = $route;
    }

    public function pop(): bool
    {
        if (count($this->stack) <= 1) {
            return false;
        }

        array_pop($this->stack);

        return true;
    }

    public function currentRoute(): string
    {
        return $this->stack[count($this->stack) - 1];
    }

    public function render(): Renderable
    {
        return ($this->routes[$this->currentRoute()])();
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

            $restored[] = $route;
        }
        $this->stack = $restored;
    }

    public function saveState(): array
    {
        return ['stack' => $this->stack];
    }
}
