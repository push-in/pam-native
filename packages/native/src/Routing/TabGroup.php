<?php

declare(strict_types=1);

namespace Pam\Native\Routing;

use Pam\Native\Navigation\TabBackBehavior;
use Pam\Native\Navigation\TabPresentation;
use Pam\Native\Route;

/** A Route::tabs() group; its host is a single stack entry named after the group. */
final class TabGroup
{
    /** @var list<Route> */
    public array $tabs = [];
    public ?TabPresentation $presentation = null;
    public ?TabBackBehavior $backBehavior = null;

    /** @internal */
    public function __construct(public readonly Route $host)
    {
    }

    public function presentation(TabPresentation $presentation): self
    {
        $this->presentation = $presentation;

        return $this;
    }

    public function backBehavior(TabBackBehavior $behavior): self
    {
        $this->backBehavior = $behavior;

        return $this;
    }

    /** Boot into this tab group. */
    public function initial(): self
    {
        $this->host->initial();

        return $this;
    }

    /** The host route name ('tabs' by default); pass a name to rename it. */
    public function name(?string $name = null): string|self
    {
        if ($name === null) return $this->host->routeName();
        $this->host->named($name);

        return $this;
    }
}
