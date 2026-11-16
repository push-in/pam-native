<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

use Pam\Native\Component;
use Pam\Native\Element;
use WeakMap;

/**
 * Granular re-render: a component whose own inputs did not change but that
 * has dirty descendants keeps its last element and renders again only its
 * children leading to them, placing their new elements where its template
 * placed the previous ones (same call-site decoration and static slot) with
 * a copy of the path from its root (React/Vue-like updates: the ancestors of
 * a changed component no longer run their templates).
 *
 * Whenever a child cannot be placed safely (no placement from the owner's
 * template: a component given as slot content, a lazy list row, a portal, a
 * component built in PHP code; or not found exactly once in the owner's
 * tree), the caller falls back to the full render.
 *
 * @internal
 */
final class GranularRender
{
    /**
     * Child paths in an owner's last element: [root element (weakly), child
     * => list of child indexes from the root]. Paths survive granular updates
     * (only the elements on them are copied).
     *
     * @var WeakMap<Component, array{0: \WeakReference<Element>, 1: WeakMap<Component, list<int>>}>|null
     */
    private static ?WeakMap $indexes = null;

    private static bool $enabled = true;

    private function __construct()
    {
    }

    /** Turns granular updates off (every dirty descendant re-renders its ancestors), e.g. to compare. */
    public static function enabled(?bool $enabled = null): bool
    {
        if ($enabled !== null) {
            self::$enabled = $enabled;
        }

        return self::$enabled;
    }

    /**
     * The owner's last element with its dirty children rendered again, or
     * null when the owner must render completely.
     */
    public static function patch(Component $owner, Element $root): ?Element
    {
        if (!self::$enabled) {
            return null;
        }
        $children = DependencyTracker::pendingChildren($owner);
        if ($children === []) {
            return null;
        }
        $listed = [];
        foreach ($children as $child) {
            $listed[spl_object_id($child)] = true;
        }
        // #[AlwaysRender] children render whenever their parent renders.
        foreach (ComponentLifecycle::children($owner) as $child) {
            if (!isset($listed[spl_object_id($child)]) && Component::__pamAlwaysRenders($child)) {
                $listed[spl_object_id($child)] = true;
                $children[] = $child;
            }
        }
        $paths = self::locate($owner, $root, $children);
        if ($paths === null || !ComponentLifecycle::beginPatch($owner)) {
            return null;
        }

        // A child that fails propagates to the owner as from its render
        // (the owner's failed() boundary applies, see Component).
        $elements = [];
        $rendered = false;
        DependencyTracker::resume($owner);
        try {
            foreach ($children as $index => $child) {
                $elements[$index] = $child->toElement();
            }
            $rendered = true;
        } finally {
            DependencyTracker::suspend($owner);
            if (!$rendered) {
                ComponentLifecycle::endPatch($owner, $children, false);
            }
        }
        $complete = DependencyTracker::ownClean($owner);
        ComponentLifecycle::endPatch($owner, $children, $complete);
        if (!$complete) {
            // Rendering a child changed the owner itself.
            return null;
        }

        foreach ($children as $index => $child) {
            $placement = $child->__pamPlacement();
            $element = $elements[$index];
            if ($placement === null || $element === $placement->element) {
                continue;
            }
            $placed = TemplateRenderer::replacePlacement($child, $placement, $element);
            if ($placed === null) {
                return null;
            }
            $root = $root->__pamReplacePath($paths[$index], $placed);
        }
        $indexes = self::$indexes ??= new WeakMap();
        if (isset($indexes[$owner])) {
            // The paths stay valid: only the elements on them were copied.
            $indexes[$owner] = [\WeakReference::create($root), $indexes[$owner][1]];
        }
        DependencyTracker::settle($owner);

        return $root;
    }

    /**
     * Path of each child's placed element in the owner's last element, or
     * null when one cannot be placed again exactly.
     *
     * @param list<Component> $children
     * @return array<int, list<int>>|null
     */
    private static function locate(Component $owner, Element $root, array $children): ?array
    {
        $pass = ComponentLifecycle::renderedPass($owner);
        $revision = \Pam\Native\Style\StyleVariables::revision();
        foreach ($children as $child) {
            $placement = $child->__pamPlacement();
            if (
                $placement === null
                || $placement->owner !== $owner
                || $placement->pass !== $pass
                || $placement->styleRevision !== $revision
            ) {
                // Not placed by the owner's last real render (or placed
                // through a path that cannot be replayed).
                return null;
            }
        }
        $indexes = self::$indexes ??= new WeakMap();
        $index = $indexes[$owner] ?? null;
        if ($index === null || $index[0]->get() !== $root) {
            $index = [\WeakReference::create($root), self::index($owner, $root, $pass)];
            $indexes[$owner] = $index;
        }
        $known = $index[1];
        $paths = [];
        foreach ($children as $position => $child) {
            $path = $known[$child] ?? null;
            if ($path === null) {
                return null;
            }
            $node = $root;
            foreach ($path as $at) {
                $node = $node->children[$at] ?? null;
                if ($node === null) {
                    return null;
                }
            }
            if ($node !== $child->__pamPlacement()?->placed) {
                return null;
            }
            $paths[$position] = $path;
        }

        return $paths;
    }

    /**
     * Paths of the elements the owner's last real render placed for its
     * children, each found exactly once in its tree. The walk stops at them:
     * below is the child's own tree.
     *
     * @return WeakMap<Component, list<int>>
     */
    private static function index(Component $owner, Element $root, int $pass): WeakMap
    {
        $targets = [];
        foreach (ComponentLifecycle::children($owner) as $child) {
            $placement = $child->__pamPlacement();
            if ($placement === null || $placement->owner !== $owner || $placement->pass !== $pass) {
                continue;
            }
            $id = spl_object_id($placement->placed);
            // Two children sharing one element cannot be told apart.
            $targets[$id] = isset($targets[$id]) && $targets[$id] !== $child ? false : $child;
        }
        $found = [];
        $rootId = spl_object_id($root);
        if (isset($targets[$rootId])) {
            $found[$rootId] = [];
        } elseif ($targets !== []) {
            self::search($root, [], $targets, $found, 0);
        }
        $paths = new WeakMap();
        foreach ($found as $id => $path) {
            $child = $targets[$id];
            if ($child instanceof Component && $path !== false) {
                $paths[$child] = $path;
            }
        }

        return $paths;
    }

    /**
     * Finds the placed elements of $targets below $element (false marks one
     * found twice).
     *
     * @param list<int> $path
     * @param array<int, Component|false> $targets by placed element id
     * @param array<int, list<int>|false> $found
     */
    private static function search(Element $element, array $path, array $targets, array &$found, int $depth): void
    {
        if ($depth > 512) {
            return;
        }
        foreach ($element->children as $position => $child) {
            $id = spl_object_id($child);
            if (isset($targets[$id]) || ($targets[$id] ?? null) === false) {
                $found[$id] = array_key_exists($id, $found) ? false : [...$path, $position];
                continue;
            }
            if ($child->children === []) {
                continue;
            }
            $path[] = $position;
            self::search($child, $path, $targets, $found, $depth + 1);
            array_pop($path);
        }
    }

    /** @internal */
    public static function reset(): void
    {
        self::$indexes = null;
    }
}
