<?php

declare(strict_types=1);

namespace Pam\Native\Navigation;

use BackedEnum;
use InvalidArgumentException;
use Pam\Native\Routing\RedirectMode;
use Pam\Native\Routing\RouteCollection;
use Pam\Native\Routing\RouteName;
use Pam\Native\Routing\TypedRoute;
use Pam\Native\System\Timers;

use function array_key_first;
use function count;

/**
 * @internal The destination a Pressable mounts on press-in (Pressable::prewarm()).
 *
 * Press-in mounts it hidden (Route API: params validated, middleware run);
 * the press that navigates to the same route and params reuses it. A press
 * that ends without navigating (the finger left, a scroll took the touch, a
 * long press) releases it RELEASE_GRACE_MS after its press-out, unless a
 * navigation or another prewarm used it meanwhile; an abandoned one is
 * released by the prewarm lifetime.
 */
final class PressPrewarm
{
    /**
     * The press of a tap follows its press-out in the same native touch
     * event, so it always reaches PHP before this grace ends.
     */
    public const int RELEASE_GRACE_MS = 300;
    private const int MAX_PENDING = 32;

    /** @var array<string, int> intent => warm token of the screen a press-in mounted, until its press-out */
    private static array $pending = [];

    public readonly string $route;
    /** @var array<string, string|int|float|bool|null> */
    public readonly array $params;
    public readonly string $intent;

    /** @param array<string, string|int|float|bool|null> $params */
    public function __construct(
        string|BackedEnum|TypedRoute $route,
        array $params = [],
        public readonly RedirectMode $mode = RedirectMode::Navigate,
        public readonly ?int $releaseAfterMs = 4_000,
    ) {
        if ($route instanceof TypedRoute) {
            $params = [...$route->params, ...$params];
            $route = $route->name;
        }
        $this->route = RouteName::value($route);
        $this->params = $params;
        $this->intent = Navigator::intentKey($this->route, $params);
    }

    public function pressIn(): void
    {
        $navigator = RouteCollection::active();
        if ($navigator === null) return;
        try {
            $token = $navigator->pressPrewarm($this->route, $this->params, $this->intent, $this->mode, $this->releaseAfterMs);
        } catch (InvalidArgumentException) {
            // Bad params fail the press itself, where the app sees the error.
            return;
        }
        if ($token === null) return;
        unset(self::$pending[$this->intent]);
        self::$pending[$this->intent] = $token;
        while (count(self::$pending) > self::MAX_PENDING) {
            unset(self::$pending[array_key_first(self::$pending)]);
        }
    }

    public function pressOut(): void
    {
        $token = self::$pending[$this->intent] ?? null;
        if ($token === null) return;
        unset(self::$pending[$this->intent]);
        Timers::after(self::RELEASE_GRACE_MS, static function () use ($token): void {
            RouteCollection::active()?->releasePressPrewarm($token);
        });
    }

    /** @internal Tests. */
    public static function reset(): void
    {
        self::$pending = [];
    }
}
