<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

use Pam\Native\Component;

/** Lifecycle flags of one component, updated in place. */
final class ComponentLifecycleState
{
    public bool $booted = false;
    public bool $setup = false;
    public bool $mounted = false;
    public bool $attached = false;
    public bool $resumed = false;
    public bool $inactive = false;
    public int $seen = 0;

    /** Creation order (the order commit() visits components in). */
    public int $sequence = 0;

    /** Listed in the commit queue (needs attach, resume or effects). */
    public bool $queued = false;

    /** @var list<Component> child components rendered during the last real render */
    public array $children = [];

    /** Render pass in which the children were retained (the walk ran). */
    public int $walked = -1;

    /** Render pass in which the component really rendered (not reused). */
    public int $rendered = -1;

    /**
     * Lifecycle states of every component under this one, collected the
     * first time it was reused after rendering (a reused component's subtree
     * cannot change until it renders again).
     *
     * @var list<ComponentLifecycleState>|null
     */
    public ?array $retained = null;
}
