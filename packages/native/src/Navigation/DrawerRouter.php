<?php

declare(strict_types=1);

namespace Pam\Native\Navigation;

use Closure;
use InvalidArgumentException;
use Pam\Native\Renderable;

final class DrawerRouter
{
    /** @var list<NavigationDrawerItem> */
    private array $routes = [];
    private DrawerType $type = DrawerType::Front;
    private DrawerPosition $position = DrawerPosition::Automatic;
    private DrawerBackBehavior $backBehavior = DrawerBackBehavior::FirstRoute;
    private DrawerKeyboardDismissMode $keyboardDismissMode =
        DrawerKeyboardDismissMode::OnDrag;
    private DrawerStatusBarAnimation $statusBarAnimation =
        DrawerStatusBarAnimation::Slide;
    private string $persistenceKey = 'drawer';
    private bool $defaultOpen = false;
    private bool $swipeEnabled = true;
    private bool $hideStatusBarOnOpen = false;
    private float $width = 256.0;
    private float $swipeEdgeWidth = 32.0;
    private float $swipeMinDistance = 56.0;
    private float $permanentBreakpoint = 840.0;
    private int $backgroundColor = 0xFFFFFFFF;
    private int $activeColor = 0xFF0F172A;
    private int $inactiveColor = 0xFF64748B;
    private int $activeBackgroundColor = 0xFFE2E8F0;
    private int $overlayColor = 0x33000000;
    private int $dividerColor = 0xFFE2E8F0;
    private ?Closure $customContent = null;

    public function __construct(private readonly string $initialRoute)
    {
        if ($initialRoute === '') {
            throw new InvalidArgumentException(
                'The initial drawer route cannot be empty.',
            );
        }
    }

    public function route(
        string $name,
        string $label,
        Renderable|Closure $content,
        ?Renderable $icon = null,
        ?string $badge = null,
    ): self {
        $copy = clone $this;
        $copy->routes[] = new NavigationDrawerItem(
            $name,
            $label,
            $content,
            $icon,
            $badge,
        );

        return $copy;
    }

    public function presentation(
        DrawerType $type,
        DrawerPosition $position = DrawerPosition::Automatic,
    ): self {
        $copy = clone $this;
        $copy->type = $type;
        $copy->position = $position;

        return $copy;
    }

    public function backBehavior(DrawerBackBehavior $behavior): self
    {
        $copy = clone $this;
        $copy->backBehavior = $behavior;

        return $copy;
    }

    public function defaultOpen(bool $open = true): self
    {
        $copy = clone $this;
        $copy->defaultOpen = $open;

        return $copy;
    }

    public function gestures(
        bool $enabled = true,
        float $edgeWidth = 32.0,
        float $minimumDistance = 56.0,
        DrawerKeyboardDismissMode $keyboard =
            DrawerKeyboardDismissMode::OnDrag,
    ): self {
        $copy = clone $this;
        $copy->swipeEnabled = $enabled;
        $copy->swipeEdgeWidth = $edgeWidth;
        $copy->swipeMinDistance = $minimumDistance;
        $copy->keyboardDismissMode = $keyboard;

        return $copy;
    }

    public function responsive(float $permanentBreakpoint = 840.0): self
    {
        $copy = clone $this;
        $copy->permanentBreakpoint = max(0.0, $permanentBreakpoint);

        return $copy;
    }

    public function statusBar(
        bool $hideOnOpen,
        DrawerStatusBarAnimation $animation =
            DrawerStatusBarAnimation::Slide,
    ): self {
        $copy = clone $this;
        $copy->hideStatusBarOnOpen = $hideOnOpen;
        $copy->statusBarAnimation = $animation;

        return $copy;
    }

    public function appearance(
        int $backgroundColor,
        int $activeColor,
        int $inactiveColor,
        int $activeBackgroundColor,
        int $overlayColor,
        int $dividerColor,
        float $width = 256.0,
    ): self {
        $copy = clone $this;
        $copy->backgroundColor = $backgroundColor;
        $copy->activeColor = $activeColor;
        $copy->inactiveColor = $inactiveColor;
        $copy->activeBackgroundColor = $activeBackgroundColor;
        $copy->overlayColor = $overlayColor;
        $copy->dividerColor = $dividerColor;
        $copy->width = max(200.0, min(640.0, $width));

        return $copy;
    }

    public function content(Closure $content): self
    {
        $copy = clone $this;
        $copy->customContent = $content;

        return $copy;
    }

    public function persistence(string $key): self
    {
        $copy = clone $this;
        $copy->persistenceKey = $key;

        return $copy;
    }

    public function build(): DrawerNavigator
    {
        return new DrawerNavigator(
            initialRoute: $this->initialRoute,
            routes: $this->routes,
            type: $this->type,
            position: $this->position,
            backBehavior: $this->backBehavior,
            keyboardDismissMode: $this->keyboardDismissMode,
            statusBarAnimation: $this->statusBarAnimation,
            persistenceKey: $this->persistenceKey,
            defaultOpen: $this->defaultOpen,
            swipeEnabled: $this->swipeEnabled,
            hideStatusBarOnOpen: $this->hideStatusBarOnOpen,
            width: $this->width,
            swipeEdgeWidth: $this->swipeEdgeWidth,
            swipeMinDistance: $this->swipeMinDistance,
            permanentBreakpoint: $this->permanentBreakpoint,
            backgroundColor: $this->backgroundColor,
            activeColor: $this->activeColor,
            inactiveColor: $this->inactiveColor,
            activeBackgroundColor: $this->activeBackgroundColor,
            overlayColor: $this->overlayColor,
            dividerColor: $this->dividerColor,
            customContent: $this->customContent,
        );
    }
}
