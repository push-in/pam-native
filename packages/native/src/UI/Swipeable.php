<?php

declare(strict_types=1);

namespace Pam\Native\UI;

use Closure;
use InvalidArgumentException;
use Pam\Native\Animation\Drag;
use Pam\Native\Animation\Spring;
use Pam\Native\Element;
use Pam\Native\GestureComposition;
use Pam\Native\GestureDirection;
use Pam\Native\GestureEvent;
use Pam\Native\GestureSettleEvent;
use Pam\Native\GestureType;
use Pam\Native\PositionType;
use Pam\Native\Renderable;
use Pam\Native\Style;

/**
 * react-native-gesture-handler `Swipeable`: horizontal drag reveals the
 * left and/or right action panels behind the row, settles open or closed
 * with a spring, and keeps one row open per group. Everything runs on the UI
 * thread; PHP receives `onOpen`/`onClose` after the row settles.
 *
 * ```php
 * Swipeable::make($row)
 *     ->rightActions($archiveAndDelete, width: 160)
 *     ->leftActions($pinPanel, width: 80)
 *     ->group('inbox')
 *     ->closeRequest($this->closeRows)
 *     ->onOpen(fn (string $side) => $this->opened($id, $side));
 * ```
 *
 * The row content must paint an opaque background so the panels stay hidden
 * while closed.
 */
final class Swipeable implements Renderable
{
    public const string CONTENT_REF = 'pam-swipeable-content';
    public const string LEFT_REF = 'pam-swipeable-left';
    public const string RIGHT_REF = 'pam-swipeable-right';

    private ?Renderable $left = null;
    private float $leftWidth = 0.0;
    private ?Renderable $right = null;
    private float $rightWidth = 0.0;
    private string $group = '';
    private float $threshold = 0.4;
    private float $velocity = 800.0;
    private Spring $spring;
    private ?string $snapSide = null;
    private int $request = 0;
    private bool $enabled = true;
    private ?Closure $onOpen = null;
    private ?Closure $onClose = null;
    private ?Closure $onBegin = null;

    private function __construct(private readonly Renderable $content)
    {
        $this->spring = new Spring(stiffness: 320, damping: 30, mass: 1);
    }

    public static function make(Renderable $content): self
    {
        return new self($content);
    }

    /** Panel revealed when dragging right (RN `renderLeftActions`). */
    public function leftActions(Renderable $panel, float $width): self
    {
        $copy = clone $this;
        $copy->left = $panel;
        $copy->leftWidth = self::width($width);

        return $copy;
    }

    /** Panel revealed when dragging left (RN `renderRightActions`). */
    public function rightActions(Renderable $panel, float $width): self
    {
        $copy = clone $this;
        $copy->right = $panel;
        $copy->rightWidth = self::width($width);

        return $copy;
    }

    /** Opening one row in [name] closes the others (one-open-at-a-time). */
    public function group(string $name): self
    {
        $copy = clone $this;
        $copy->group = $name;

        return $copy;
    }

    /**
     * Fraction of the panel width (RN `leftThreshold`/`rightThreshold`
     * default: half) or fling velocity in dp/s needed to open/close.
     */
    public function threshold(float $fraction, float $velocity = 800.0): self
    {
        if ($fraction <= 0 || $fraction > 1) {
            throw new InvalidArgumentException('Swipeable threshold must be a fraction in (0, 1].');
        }
        $copy = clone $this;
        $copy->threshold = $fraction;
        $copy->velocity = max(0.0, $velocity);

        return $copy;
    }

    public function spring(Spring $spring): self
    {
        $copy = clone $this;
        $copy->spring = $spring;

        return $copy;
    }

    public function enabled(bool $enabled): self
    {
        $copy = clone $this;
        $copy->enabled = $enabled;

        return $copy;
    }

    /** Programmatic close (RN `ref.close()`): bump [request] to close again. */
    public function closeRequest(int $request): self
    {
        return $this->snap('closed', $request);
    }

    /** Programmatic open of 'left' or 'right' (RN `openLeft()`/`openRight()`). */
    public function openRequest(string $side, int $request): self
    {
        return $this->snap($side, $request);
    }

    /** @param Closure(string): mixed $handler receives 'left' or 'right' */
    public function onOpen(Closure $handler): self
    {
        $copy = clone $this;
        $copy->onOpen = $handler;

        return $copy;
    }

    public function onClose(Closure $handler): self
    {
        $copy = clone $this;
        $copy->onClose = $handler;

        return $copy;
    }

    /** Swipe started (RN `onSwipeableWillOpen` precursor), e.g. to dismiss menus. */
    public function onBegin(Closure $handler): self
    {
        $copy = clone $this;
        $copy->onBegin = $handler;

        return $copy;
    }

    /** @return list<float> ascending snap positions */
    public function snapPoints(): array
    {
        $points = [];
        if ($this->right !== null) $points[] = -$this->rightWidth;
        $points[] = 0.0;
        if ($this->left !== null) $points[] = $this->leftWidth;

        return $points;
    }

    public function toElement(): Element
    {
        if ($this->left === null && $this->right === null) {
            throw new InvalidArgumentException('Swipeable requires leftActions() and/or rightActions().');
        }
        $points = $this->snapPoints();
        $smallest = min($this->leftWidth ?: INF, $this->rightWidth ?: INF);
        $drag = Drag::horizontal()
            ->bounds(min: $this->right !== null ? -$this->rightWidth : 0.0, max: $this->left !== null ? $this->leftWidth : 0.0)
            ->rubberBand(0.15)
            ->target(self::CONTENT_REF)
            ->snapPoints(...$points)
            ->threshold($smallest * $this->threshold, $this->velocity)
            ->settle($this->spring);
        if ($this->group !== '') {
            $drag = $drag->group($this->group);
        }
        $layers = [];
        if ($this->left !== null) {
            $drag = $drag->drive(self::LEFT_REF, 'opacity', [0, $this->leftWidth * 0.5, $this->leftWidth], [0, 0.6, 1]);
            $layers[] = View::make($this->left)
                ->nativeRef(self::LEFT_REF)
                ->style(new Style(positionType: PositionType::Absolute, left: 0.0, top: 0.0, bottom: 0.0, width: $this->leftWidth, opacity: 0.0));
        }
        if ($this->right !== null) {
            $drag = $drag->drive(self::RIGHT_REF, 'opacity', [-$this->rightWidth, -$this->rightWidth * 0.5, 0], [1, 0.6, 0]);
            $layers[] = View::make($this->right)
                ->nativeRef(self::RIGHT_REF)
                ->style(new Style(positionType: PositionType::Absolute, right: 0.0, top: 0.0, bottom: 0.0, width: $this->rightWidth, opacity: 0.0));
        }
        $layers[] = View::make($this->content)->nativeRef(self::CONTENT_REF);
        $detector = GestureDetector::make(GestureType::Pan, View::make(...$layers))
            ->direction(GestureDirection::Horizontal)
            ->composition(GestureComposition::Race)
            ->minimumDistance(12)
            ->gestureEnabled($this->enabled)
            ->drag($drag);
        if ($this->snapSide !== null) {
            $target = match ($this->snapSide) {
                'left' => $this->leftWidth,
                'right' => -$this->rightWidth,
                default => 0.0,
            };
            $index = array_search($target, $points, true);
            if ($index !== false) {
                $detector = $detector->snapTo($index, $this->request);
            }
        }
        if ($this->onBegin !== null) {
            $begin = $this->onBegin;
            $detector = $detector->onBegin(static fn (GestureEvent $event): mixed => $begin());
        }
        if ($this->onOpen !== null || $this->onClose !== null) {
            $open = $this->onOpen;
            $close = $this->onClose;
            $detector = $detector->onSettle(static function (GestureSettleEvent $event) use ($open, $close): void {
                if ($event->position > 0.5) {
                    $open?->__invoke('left');
                } elseif ($event->position < -0.5) {
                    $open?->__invoke('right');
                } else {
                    $close?->__invoke();
                }
            });
        }

        return $detector;
    }

    private function snap(string $side, int $request): self
    {
        if (!in_array($side, ['closed', 'left', 'right'], true)) {
            throw new InvalidArgumentException('Swipeable sides are closed, left or right.');
        }
        $copy = clone $this;
        $copy->snapSide = $side;
        $copy->request = max(0, $request);

        return $copy;
    }

    private static function width(float $width): float
    {
        if (!is_finite($width) || $width <= 0 || $width > 2_000) {
            throw new InvalidArgumentException('Swipeable action widths must be between 0 and 2000 dp.');
        }

        return $width;
    }
}
