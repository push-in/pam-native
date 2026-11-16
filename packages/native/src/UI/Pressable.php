<?php

declare(strict_types=1);

namespace Pam\Native\UI;

use BackedEnum;
use Closure;
use Pam\Native\Animation\TapEffect;
use Pam\Native\Element;
use Pam\Native\EventKind;
use Pam\Native\Navigation\NavigationTransition;
use Pam\Native\Navigation\Navigator;
use Pam\Native\Navigation\PressPrewarm;
use Pam\Native\NodeKind;
use Pam\Native\PressEvent;
use Pam\Native\PropKey;
use Pam\Native\Renderable;
use Pam\Native\RendererContract;
use Pam\Native\Routing\RedirectMode;
use Pam\Native\Routing\TypedRoute;

use function is_string;

final class Pressable extends Element
{
    private ?PressPrewarm $prewarm = null;
    private ?Closure $pressInHandler = null;
    private ?Closure $pressOutHandler = null;

    public static function make(Renderable|false|null ...$children): self
    {
        return (new self(NodeKind::Pressable))->withChildren($children);
    }

    /**
     * Handlers typed `PressEvent $event` receive the touch location
     * (locationX/Y as `x`/`y`, pageX/pageY); others keep the empty payload.
     */
    public function onPress(Closure $handler): self
    {
        return $this->withEvent(EventKind::Press, self::pressHandler($handler));
    }

    /** Fires after `delayLongPress` while held; pair with `onPressOut` for hold-to-record. */
    public function onLongPress(Closure $handler): self
    {
        return $this->withEvent(EventKind::LongPress, self::pressHandler($handler));
    }

    /**
     * Second tap within `doubleTapDelay` near the first. When present, the
     * single `onPress` is deferred by that delay and cancelled by a double tap
     * (the RN ReelPage pattern), all on the UI thread.
     */
    public function onDoubleTap(Closure $handler): self
    {
        return $this->withPressEvent(EventKind::DoubleTap, $handler);
    }

    public function doubleTapDelay(int $milliseconds): self
    {
        return $this->withProperty(PropKey::PressDoubleTapDelayMs, max(80, min(1_000, $milliseconds)));
    }

    /** Native animation on a double tap (Reels heart burst) or a single tap (like pop); see TapEffect. */
    public function tapEffect(TapEffect $effect): self
    {
        return $this->withProperty(PropKey::PressTapEffect, $effect->encode());
    }

    public function onPressIn(Closure $handler): self
    {
        $copy = clone $this;
        $copy->pressInHandler = $handler;

        return $copy->withPressInOut(EventKind::PressIn);
    }

    public function onPressOut(Closure $handler): self
    {
        $copy = clone $this;
        $copy->pressOutHandler = $handler;

        return $copy->withPressInOut(EventKind::PressOut);
    }

    /**
     * Declares where this press navigates and mounts it ahead: on press-in
     * the destination is mounted hidden (Route::prewarm(), same validation
     * and middleware as the navigation), the press that navigates to the
     * same route and params reuses it, and a press that ends without
     * navigating (finger lifted outside, a scroll took the touch, a long
     * press) releases it shortly after. Unused, it is released after
     * `$releaseAfterMs`. The app's own onPressIn/onPressOut still run.
     *
     * The press itself still has to navigate (onPress, or a Link). Where the
     * renderer supports it, the push of the warm screen starts natively on
     * the frame after the tap and the navigation's commit adopts it.
     *
     * Only navigate (the default) and push warm: a navigate() back to an
     * entry already in the stack has nothing to mount.
     *
     * `$transition`: the action transition the press navigates with, when
     * it overrides the route's own (`Route::navigate(..., transition:)`). The
     * native start only runs that same motion, so a tab bar switching with
     * `None` still warms on press-in but waits for its commit.
     *
     * @param array<string, string|int|float|bool|null> $params
     */
    public function prewarm(
        string|BackedEnum|TypedRoute $route,
        array $params = [],
        RedirectMode $mode = RedirectMode::Navigate,
        ?int $releaseAfterMs = 4_000,
        ?NavigationTransition $transition = null,
    ): self {
        $copy = clone $this;
        $copy->prewarm = new PressPrewarm($route, $params, $mode, $releaseAfterMs);

        return $copy
            ->withNavigationIntent(self::intentWithMotion($copy->prewarm->intent, $transition))
            ->withPressInOut(EventKind::PressIn)
            ->withPressInOut(EventKind::PressOut);
    }

    /**
     * Declares where this press navigates without mounting it on press-in:
     * when the app mounted that destination ahead itself (an idle warmup
     * with Navigator::prewarm()), the native host starts its push on the
     * frame after the tap. The press must navigate to the same route and
     * params (and `$transition`, as for prewarm()).
     *
     * @param array<string, string|int|float|bool|null> $params
     */
    public function navigatesTo(
        string|BackedEnum|TypedRoute $route,
        array $params = [],
        ?NavigationTransition $transition = null,
    ): self {
        if ($route instanceof TypedRoute) {
            $params = [...$route->params, ...$params];
            $route = $route->name;
        }

        return $this->withNavigationIntent(self::intentWithMotion(Navigator::intentKey($route, $params), $transition));
    }

    /**
     * `key@transition` when the press declares its motion: the host starts
     * the push only when its warm route moves the same way (PlatformDefault
     * matches the stack's default; None never starts natively).
     */
    private static function intentWithMotion(string $intent, ?NavigationTransition $transition): string
    {
        return $transition === null ? $intent : $intent.'@'.$transition->value;
    }

    /** @internal The destination declared by prewarm(), if any. */
    public function pressPrewarm(): ?PressPrewarm
    {
        return $this->prewarm;
    }

    public function onPressMove(Closure $handler): self
    {
        return $this->withPressEvent(EventKind::PressMove, $handler);
    }

    public function ripple(
        int $color,
        bool $borderless = false,
        ?float $radius = null,
        bool $foreground = false,
        float $alpha = 1.0,
    ): self {
        $pressable = $this
            ->withProperty(PropKey::RippleColor, $color)
            ->withProperty(PropKey::RippleBorderless, $borderless)
            ->withProperty(PropKey::RippleForeground, $foreground)
            ->withProperty(PropKey::RippleAlpha, min(1.0, max(0.0, $alpha)));

        return $radius === null
            ? $pressable
            : $pressable->withProperty(PropKey::RippleRadius, max(0.0, $radius));
    }

    public function pressedBackgroundColor(int $color): self
    {
        return $this->withProperty(PropKey::PressBackgroundColor, $color);
    }

    public function pressedOpacity(float $opacity): self
    {
        return $this->withProperty(PropKey::PressOpacity, min(1.0, max(0.0, $opacity)));
    }

    public function pressedScale(float $scale): self
    {
        return $this->withProperty(PropKey::PressScale, min(4.0, max(0.01, $scale)));
    }

    public function hitSlop(float $amount): self
    {
        return $this->hitSlopEdges($amount, $amount, $amount, $amount);
    }

    public function hitSlopEdges(
        float $left,
        float $top,
        float $right,
        float $bottom,
    ): self {
        return $this
            ->withProperty(PropKey::HitSlopLeft, max(0.0, $left))
            ->withProperty(PropKey::HitSlopTop, max(0.0, $top))
            ->withProperty(PropKey::HitSlopRight, max(0.0, $right))
            ->withProperty(PropKey::HitSlopBottom, max(0.0, $bottom));
    }

    public function pressRetentionOffset(float $amount): self
    {
        return $this->pressRetentionEdges($amount, $amount, $amount, $amount);
    }

    public function pressRetentionEdges(
        float $left,
        float $top,
        float $right,
        float $bottom,
    ): self {
        return $this
            ->withProperty(PropKey::PressRetentionLeft, max(0.0, $left))
            ->withProperty(PropKey::PressRetentionTop, max(0.0, $top))
            ->withProperty(PropKey::PressRetentionRight, max(0.0, $right))
            ->withProperty(PropKey::PressRetentionBottom, max(0.0, $bottom));
    }

    public function delayLongPress(int $milliseconds): self
    {
        return $this->withProperty(
            PropKey::PressDelayLongMs,
            min(60_000, max(0, $milliseconds)),
        );
    }

    public function delayPressIn(int $milliseconds): self
    {
        return $this->withProperty(
            PropKey::PressDelayInMs,
            min(60_000, max(0, $milliseconds)),
        );
    }

    public function delayPressOut(int $milliseconds): self
    {
        return $this->withProperty(
            PropKey::PressDelayOutMs,
            min(60_000, max(0, $milliseconds)),
        );
    }

    public function androidDisableSound(bool $disabled = true): self
    {
        return $this->withProperty(PropKey::PressAndroidDisableSound, $disabled);
    }

    private static function pressHandler(Closure $handler): Closure
    {
        $parameter = (new \ReflectionFunction($handler))->getParameters()[0] ?? null;
        $type = $parameter?->getType();
        $typed = $type instanceof \ReflectionNamedType && $type->getName() === PressEvent::class;

        return $typed
            ? static fn (string $payload = ''): mixed => $handler(PressEvent::fromPayload($payload))
            : static fn (mixed $payload = ''): mixed => $handler(is_string($payload) ? '' : $payload);
    }

    private function withNavigationIntent(string $intent): self
    {
        // Older renderers do not implement the native launch: the press
        // then simply waits for the navigation's commit.
        return RendererContract::current()->supportsProperty(PropKey::PressNavigationIntent->value)
            ? $this->withProperty(PropKey::PressNavigationIntent, $intent)
            : $this;
    }

    /**
     * Press-in/out handler: the app's (decoded to a PressEvent) after the
     * declared prewarm. With an app handler the wrapper is as pure as that
     * handler (the prewarm marks only the navigator dirty); without one it
     * is a #[PureHandler]: a press-in re-renders the navigator only.
     */
    private function withPressInOut(EventKind $kind): self
    {
        $handler = $kind === EventKind::PressIn ? $this->pressInHandler : $this->pressOutHandler;
        $prewarm = $this->prewarm;
        if ($prewarm === null) {
            return $handler === null ? $this : $this->withPressEvent($kind, $handler);
        }
        $pressIn = $kind === EventKind::PressIn;
        if ($handler === null) {
            return $this->withEvent(
                $kind,
                #[\Pam\Native\Attributes\PureHandler] static function (mixed $payload = '') use ($prewarm, $pressIn): void {
                    $pressIn ? $prewarm->pressIn() : $prewarm->pressOut();
                },
            );
        }

        return $this->withEvent(
            $kind,
            static function (string $payload = '') use ($handler, $prewarm, $pressIn): void {
                $pressIn ? $prewarm->pressIn() : $prewarm->pressOut();
                $handler(PressEvent::fromPayload($payload));
            },
        );
    }

    private function withPressEvent(EventKind $kind, Closure $handler): self
    {
        return $this->withEvent(
            $kind,
            static function (string $payload) use ($handler): void {
                $handler(PressEvent::fromPayload($payload));
            },
        );
    }
}
