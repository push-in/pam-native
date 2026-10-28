<?php

declare(strict_types=1);

namespace Pam\Native\UI;

use InvalidArgumentException;
use Pam\Native\Element;
use Pam\Native\FunctionalComponent;
use Pam\Native\Internal\DependencyTracker;
use Pam\Native\Internal\PortalRegistry;
use Pam\Native\PointerEvents;
use Pam\Native\PositionType;
use Pam\Native\Renderable;
use Pam\Native\Style;

/**
 * Renders content outside its parent tree, in the same window (React Native
 * `@gorhom/portal`): a menu, a reaction bar or a floating copy of a message
 * drawn above the whole screen without a Modal, so a finger that started a
 * long press keeps streaming to the gesture that opened it.
 *
 * ```php
 * // Screen root (last child, so it draws above its siblings):
 * View::make($content, Portal::host());
 *
 * // Anywhere below, while $this->menuOpen:
 * $this->menuOpen ? Portal::make('message-menu', MessageMenu::make($anchor)) : null
 * ```
 *
 * The portal renders nothing in place. Its content is drawn by the host with
 * the same name, absolutely positioned over the host's parent and
 * `pointerEvents: box-none` (touches outside the content reach the screen).
 * The content stays while its component keeps rendering the portal; it is
 * removed when that component re-renders without it or unmounts. Place the
 * host after the components that open portals so a change is drawn in the
 * same render pass. Use `ViewRef::measureInWindow()` to anchor content to a
 * view.
 */
final class Portal
{
    public const DEFAULT_HOST = 'screen';

    private function __construct()
    {
    }

    /** Portal to the default `screen` host; `$key` identifies it among that host's portals. */
    public static function make(string $key, Renderable|false|null ...$children): Element
    {
        return self::to(self::DEFAULT_HOST, $key, ...$children);
    }

    public static function to(string $host, string $key, Renderable|false|null ...$children): Element
    {
        self::name($host, 'host');
        self::name($key, 'key');
        PortalRegistry::instance()->register(
            $host,
            $key,
            array_values(array_filter($children, static fn ($child): bool => $child instanceof Renderable)),
            DependencyTracker::current(),
        );

        return View::make()->style(new Style(
            width: 0.0,
            height: 0.0,
            positionType: PositionType::Absolute,
            pointerEvents: PointerEvents::None,
        ));
    }

    /**
     * Where portals named `$name` are drawn: a full-size, touch-transparent
     * layer over its parent. Typically the last child of a screen root.
     */
    public static function host(string $name = self::DEFAULT_HOST): Renderable
    {
        self::name($name, 'host');

        return FunctionalComponent::make(static function () use ($name): Element {
            $layers = [];
            foreach (PortalRegistry::instance()->read($name) as $key => $content) {
                $layers[] = View::make(...$content)
                    ->key('portal:'.$key)
                    ->style(new Style(
                        positionType: PositionType::Absolute,
                        left: 0.0,
                        top: 0.0,
                        right: 0.0,
                        bottom: 0.0,
                        pointerEvents: PointerEvents::BoxNone,
                    ));
            }

            return View::make(...$layers)
                ->key('portal-host:'.$name)
                ->style(new Style(
                    positionType: PositionType::Absolute,
                    left: 0.0,
                    top: 0.0,
                    right: 0.0,
                    bottom: 0.0,
                    pointerEvents: PointerEvents::BoxNone,
                ));
        });
    }

    /** Removes a portal registered outside a component render (owner-less). */
    public static function remove(string $key, string $host = self::DEFAULT_HOST): void
    {
        PortalRegistry::instance()->remove($host, $key);
    }

    private static function name(string $value, string $what): void
    {
        if (preg_match('/^[A-Za-z0-9_.:-]{1,64}$/D', $value) !== 1) {
            throw new InvalidArgumentException("Portal {$what} must be 1-64 letters, digits, \"_\", \".\", \":\" or \"-\".");
        }
    }
}
