<?php

declare(strict_types=1);

namespace Pam\Native\UI\Concerns;

use Closure;
use Pam\Native\EventKind;
use Pam\Native\PropKey;
use Pam\Native\RefreshIndicatorSize;

/**
 * RN `refreshControl` straight on a ScrollView or list: pull-to-refresh with
 * the platform indicator (Android SwipeRefreshLayout, iOS UIRefreshControl).
 * The scrollable is hosted in a RefreshControl automatically; its outer box
 * (size, flex, margins, position) sizes the host. `refreshing` is controlled:
 * set it true while loading and false to end the spinner.
 */
trait HasRefreshControl
{
    public function refreshing(bool $refreshing): self
    {
        return $this->withProperty(PropKey::Refreshing, $refreshing);
    }

    public function onRefresh(Closure $handler): self
    {
        return $this->withEvent(EventKind::Refresh, $handler);
    }

    /** Android spinner colors (cycled); the first one is the iOS tint. */
    public function refreshColors(int ...$colors): self
    {
        return $this->withProperty(PropKey::RefreshColors, implode(',', $colors));
    }

    /** RN `tintColor`: the iOS spinner color (Android uses it when no colors). */
    public function refreshTintColor(int $color): self
    {
        return $this->withProperty(PropKey::TintColor, $color);
    }

    public function refreshProgressBackgroundColor(int $color): self
    {
        return $this->withProperty(PropKey::RefreshProgressBackgroundColor, $color);
    }

    /** Spinner offset from the top, e.g. below a translucent header. */
    public function progressViewOffset(float $offset): self
    {
        return $this->withProperty(PropKey::RefreshProgressViewOffset, $offset);
    }

    public function refreshIndicatorSize(RefreshIndicatorSize $size): self
    {
        return $this->withProperty(PropKey::RefreshIndicatorSize, $size->value);
    }

    /** RN `RefreshControl.enabled`: false keeps the control but blocks new pulls. */
    public function refreshEnabled(bool $enabled = true): self
    {
        return $this->withProperty(PropKey::RefreshEnabled, $enabled);
    }
}
