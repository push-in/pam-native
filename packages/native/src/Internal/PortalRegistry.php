<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

use Pam\Native\Component;
use Pam\Native\Renderable;
use WeakMap;
use WeakReference;

/**
 * Content rendered by `Portal::make()` inside a component and drawn by the
 * matching `Portal::host()`. An entry lives while its owner keeps rendering
 * it: a re-render that no longer calls the portal, or the owner's unmount,
 * removes it. A memoized (skipped) owner keeps its entries.
 *
 * @internal
 */
final class PortalRegistry
{
    private static ?self $instance = null;

    /** @var array<string, array<string, array{owner: ?WeakReference<Component>, content: list<Renderable>}>> */
    private array $entries = [];

    /** @var WeakMap<Component, array<string, array{0: string, 1: string}>>|null keys owned per component */
    private ?WeakMap $owned = null;

    /** @var WeakMap<Component, array<string, true>>|null keys an owner has not rendered again yet */
    private ?WeakMap $unseen = null;

    /** @var array<string, true> hosts already drawn in the current render pass */
    private array $drawn = [];

    private bool $extraPass = false;

    /** @var array<string, true> hosts changed during this pass and not drawn since */
    private array $pending = [];

    private function __construct()
    {
    }

    public static function instance(): self
    {
        return self::$instance ??= new self();
    }

    /** True while any component owns a portal (keeps the per-render hooks free otherwise). */
    public static function active(): bool
    {
        return self::$instance !== null && self::$instance->owned !== null && count(self::$instance->owned) > 0;
    }

    /** @param list<Renderable> $content */
    public function register(string $host, string $key, array $content, ?Component $owner): void
    {
        $this->entries[$host][$key] = [
            'owner' => $owner === null ? null : WeakReference::create($owner),
            'content' => $content,
        ];
        if ($owner !== null) {
            $this->owned ??= new WeakMap();
            $keys = $this->owned[$owner] ?? [];
            $keys[$host."\0".$key] = [$host, $key];
            $this->owned[$owner] = $keys;
            if ($this->unseen !== null && isset($this->unseen[$owner])) {
                $unseen = $this->unseen[$owner];
                unset($unseen[$host."\0".$key]);
                $this->unseen[$owner] = $unseen;
            }
        }
        $this->changed($host);
    }

    /** Explicitly removes one portal (used for owner-less content). */
    public function remove(string $host, string $key): void
    {
        if (!isset($this->entries[$host][$key])) {
            return;
        }
        $owner = $this->entries[$host][$key]['owner']?->get();
        unset($this->entries[$host][$key]);
        if ($this->entries[$host] === []) {
            unset($this->entries[$host]);
        }
        if ($owner !== null && $this->owned !== null && isset($this->owned[$owner])) {
            $keys = $this->owned[$owner];
            unset($keys[$host."\0".$key]);
            if ($keys === []) {
                unset($this->owned[$owner]);
            } else {
                $this->owned[$owner] = $keys;
            }
        }
        $this->changed($host);
    }

    /** The owner starts a real render: its portals must be rendered again to stay. */
    public function beginOwner(Component $owner): void
    {
        if ($this->owned === null || !isset($this->owned[$owner])) {
            return;
        }
        $this->unseen ??= new WeakMap();
        $this->unseen[$owner] = array_fill_keys(array_keys($this->owned[$owner]), true);
    }

    /** The owner finished rendering: drop the portals it no longer rendered. */
    public function endOwner(Component $owner): void
    {
        if ($this->unseen === null || !isset($this->unseen[$owner])) {
            return;
        }
        $unseen = $this->unseen[$owner];
        unset($this->unseen[$owner]);
        $owned = $this->owned[$owner] ?? [];
        foreach (array_keys($unseen) as $id) {
            if (isset($owned[$id])) {
                [$host, $key] = $owned[$id];
                $this->remove($host, $key);
            }
        }
    }

    /** The owner unmounted. */
    public function forgetOwner(Component $owner): void
    {
        if ($this->owned === null || !isset($this->owned[$owner])) {
            return;
        }
        foreach ($this->owned[$owner] as [$host, $key]) {
            $this->remove($host, $key);
        }
        unset($this->owned[$owner]);
        if ($this->unseen !== null) {
            unset($this->unseen[$owner]);
        }
    }

    /**
     * The content to draw at $host, in registration order. Subscribes the
     * component drawing the host, which re-renders when the content changes.
     *
     * @return array<string, list<Renderable>>
     */
    public function read(string $host): array
    {
        DependencyTracker::read($this, $host);
        $this->drawn[$host] = true;
        unset($this->pending[$host]);
        $content = [];
        foreach ($this->entries[$host] ?? [] as $key => $entry) {
            $content[$key] = $entry['content'];
        }

        return $content;
    }

    /** A render() call starts: at most one extra pass is spent on portals. */
    public function beginRender(): void
    {
        $this->extraPass = false;
    }

    public function beginPass(): void
    {
        $this->drawn = [];
    }

    /**
     * True when a host changed during the pass but was not drawn after the
     * change (its component was reused before the owner rendered): the
     * runtime renders one more pass, where that component is dirty.
     */
    public function endPass(): bool
    {
        $pending = $this->pending !== [] && !$this->extraPass;
        $this->pending = [];
        if ($pending) {
            $this->extraPass = true;
            // Components that drew (or skipped) the host already recorded this
            // pass as current; only a global invalidation re-renders them.
            DependencyTracker::invalidateAll();
        }

        return $pending;
    }

    private function changed(string $host): void
    {
        DependencyTracker::invalidate($this, $host);
        if (!Runtime::isRendering()) {
            // Outside a render (an owner unmounted after the commit).
            Runtime::scheduleRender();

            return;
        }
        if (isset($this->drawn[$host])) {
            // Drawn earlier in this pass (the owner rendered after the host):
            // endPass() asks for one more pass.
            unset($this->drawn[$host]);
        }
        // Drawn later in this pass, or by the extra pass endPass() requests.
        $this->pending[$host] = true;
    }

    public static function reset(): void
    {
        self::$instance = null;
    }
}
