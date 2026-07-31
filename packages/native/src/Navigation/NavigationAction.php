<?php

declare(strict_types=1);

namespace Pam\Native\Navigation;

use InvalidArgumentException;

final readonly class NavigationAction
{
    /** @param array<string, string|int|float|bool|null> $params */
    public function __construct(
        public NavigationActionType $type,
        public ?string $route = null,
        public array $params = [],
        public ?string $source = null,
        public ?string $target = null,
        public bool $merge = false,
    ) {
        if ($route !== null && ($route === '' || strlen($route) > 128)) {
            throw new InvalidArgumentException('Navigation action routes must be bounded non-empty names.');
        }
    }

    /** @param array<string, string|int|float|bool|null> $params */
    public static function navigate(string $route, array $params = [], bool $merge = false): self
    {
        return new self(NavigationActionType::Navigate, $route, $params, merge: $merge);
    }

    /** @param array<string, string|int|float|bool|null> $params */
    public static function push(string $route, array $params = []): self
    {
        return new self(NavigationActionType::Push, $route, $params);
    }

    public static function pop(): self
    {
        return new self(NavigationActionType::Pop);
    }

    public static function goBack(): self
    {
        return new self(NavigationActionType::GoBack);
    }

    /** @param array<string, string|int|float|bool|null> $params */
    public static function replace(string $route, array $params = []): self
    {
        return new self(NavigationActionType::Replace, $route, $params);
    }

    /** @param array<string, string|int|float|bool|null> $params */
    public static function reset(string $route, array $params = []): self
    {
        return new self(NavigationActionType::Reset, $route, $params);
    }

    public static function popTo(string $route): self
    {
        return new self(NavigationActionType::PopTo, $route);
    }

    public static function popToTop(): self
    {
        return new self(NavigationActionType::PopToTop);
    }

    /** @param array<string, string|int|float|bool|null> $params */
    public static function setParams(array $params): self
    {
        return new self(NavigationActionType::SetParams, params: $params);
    }

    /** @param array<string, string|int|float|bool|null> $params */
    public static function replaceParams(array $params): self
    {
        return new self(NavigationActionType::ReplaceParams, params: $params);
    }

    /** @param array<string, string|int|float|bool|null> $params */
    public static function preload(string $route, array $params = []): self
    {
        return new self(NavigationActionType::Preload, $route, $params);
    }

    public function source(string $key): self
    {
        return new self($this->type, $this->route, $this->params, $key, $this->target, $this->merge);
    }

    public function target(string $key): self
    {
        return new self($this->type, $this->route, $this->params, $this->source, $key, $this->merge);
    }

    /** @return array<string, mixed> */
    public function toArray(): array
    {
        return [
            'type' => $this->type->value,
            'route' => $this->route,
            'params' => $this->params,
            'source' => $this->source,
            'target' => $this->target,
            'merge' => $this->merge,
        ];
    }
}
