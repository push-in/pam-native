<?php

declare(strict_types=1);

namespace Pam\Native\Routing;

use BackedEnum;

final readonly class RouteTarget
{
    /** @var array<string, string|int|float|bool|null> */
    public array $params;
    public string $name;

    /** @param string|int|float|bool|null ...$params */
    public function __construct(string|BackedEnum $name, mixed ...$params)
    {
        $this->name = RouteName::value($name);
        $this->params = $params;
    }

    public function push(): void
    {
        Navigation::push($this->name, $this->params);
    }

    public function navigate(): bool
    {
        return Navigation::navigate($this->name, $this->params);
    }

    public function replace(): void
    {
        Navigation::replace($this->name, $this->params);
    }
}
