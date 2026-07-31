<?php

declare(strict_types=1);

namespace Pam\Native\Navigation;

use Closure;
use InvalidArgumentException;
use Pam\Native\Renderable;

final readonly class ScreenOptions
{
    /** @param list<float>|null $sheetAllowedDetents */
    public function __construct(
        public ?string $title = null,
        public bool $headerShown = false,
        public bool $headerTransparent = false,
        public ?int $headerBackgroundColor = null,
        public ?int $headerTintColor = null,
        public bool $headerShadowVisible = true,
        public bool $headerLargeTitleEnabled = false,
        public bool $headerSearchEnabled = false,
        public ?Renderable $headerTitle = null,
        public ?Renderable $headerLeft = null,
        public ?Renderable $headerRight = null,
        public string $headerSearchPlaceholder = 'Search',
        public ?Closure $onHeaderSearchChange = null,
        public NavigationPresentation $presentation = NavigationPresentation::Card,
        public NavigationOrientation $orientation = NavigationOrientation::PlatformDefault,
        public NavigationTransition $animation = NavigationTransition::PlatformDefault,
        public ?int $animationDurationMs = null,
        public bool $gestureEnabled = true,
        public NavigationGestureDirection $gestureDirection = NavigationGestureDirection::Horizontal,
        public bool $fullScreenGestureEnabled = false,
        public bool $freezeOnBlur = false,
        public bool $autoHideHomeIndicator = false,
        public ?array $sheetAllowedDetents = null,
        public int $sheetInitialDetentIndex = 1,
        public bool $sheetGrabberVisible = false,
        public ?float $sheetCornerRadius = null,
        public bool $sheetExpandsWhenScrolledToEdge = true,
    ) {
        if ($title !== null && strlen($title) > 512) {
            throw new InvalidArgumentException('Navigation titles cannot exceed 512 bytes.');
        }
        if (strlen($headerSearchPlaceholder) > 256) {
            throw new InvalidArgumentException('Header search placeholders cannot exceed 256 bytes.');
        }
        if ($animationDurationMs !== null && ($animationDurationMs < 0 || $animationDurationMs > 2_000)) {
            throw new InvalidArgumentException('Navigation animation duration must be between 0 and 2000 ms.');
        }
        if ($sheetAllowedDetents !== null) {
            $previous = 0.0;
            if ($sheetAllowedDetents === [] || count($sheetAllowedDetents) > 3) {
                throw new InvalidArgumentException('Form sheets require between one and three detents.');
            }
            foreach ($sheetAllowedDetents as $detent) {
                if (!is_float($detent) && !is_int($detent) || $detent <= $previous || $detent > 1.0) {
                    throw new InvalidArgumentException('Form sheet detents must be ascending fractions in (0, 1].');
                }
                $previous = (float) $detent;
            }
            if ($sheetInitialDetentIndex < 1 || $sheetInitialDetentIndex > count($sheetAllowedDetents)) {
                throw new InvalidArgumentException('Initial sheet detent uses a sequential one-based index.');
            }
        }
    }

    /** @return array<string, mixed> */
    public function toArray(): array
    {
        return [
            'title' => $this->title,
            'headerShown' => $this->headerShown,
            'headerTransparent' => $this->headerTransparent,
            'headerBackgroundColor' => $this->headerBackgroundColor,
            'headerTintColor' => $this->headerTintColor,
            'headerShadowVisible' => $this->headerShadowVisible,
            'headerLargeTitleEnabled' => $this->headerLargeTitleEnabled,
            'headerSearchEnabled' => $this->headerSearchEnabled,
            'hasCustomHeaderTitle' => $this->headerTitle !== null,
            'hasHeaderLeft' => $this->headerLeft !== null,
            'hasHeaderRight' => $this->headerRight !== null,
            'headerSearchPlaceholder' => $this->headerSearchPlaceholder,
            'presentation' => $this->presentation->value,
            'orientation' => $this->orientation->value,
            'animation' => $this->animation->value,
            'animationDurationMs' => $this->animationDurationMs,
            'gestureEnabled' => $this->gestureEnabled,
            'gestureDirection' => $this->gestureDirection->value,
            'fullScreenGestureEnabled' => $this->fullScreenGestureEnabled,
            'freezeOnBlur' => $this->freezeOnBlur,
            'autoHideHomeIndicator' => $this->autoHideHomeIndicator,
            'sheetAllowedDetents' => $this->sheetAllowedDetents,
            'sheetInitialDetentIndex' => $this->sheetInitialDetentIndex,
            'sheetGrabberVisible' => $this->sheetGrabberVisible,
            'sheetCornerRadius' => $this->sheetCornerRadius,
            'sheetExpandsWhenScrolledToEdge' => $this->sheetExpandsWhenScrolledToEdge,
        ];
    }
}
