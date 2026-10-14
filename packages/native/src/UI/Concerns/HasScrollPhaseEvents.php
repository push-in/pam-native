<?php

declare(strict_types=1);

namespace Pam\Native\UI\Concerns;

use Closure;
use Pam\Native\EventKind;
use Pam\Native\PageChangeEvent;
use Pam\Native\ScrollPhaseEvent;

/**
 * RN scroll lifecycle events, detected natively: `onScrollBeginDrag`,
 * `onScrollEndDrag` (release velocity) and `onMomentumScrollEnd` (after
 * fling/paging settles, with the page index). `onPageChange` fires once
 * when the current page changes mid-drag (pager/carousel indicators).
 * Nothing is sent per frame.
 */
trait HasScrollPhaseEvents
{
    public function onScrollBeginDrag(Closure $handler): self
    {
        return $this->withScrollPhase(EventKind::ScrollBeginDrag, $handler);
    }

    public function onScrollEndDrag(Closure $handler): self
    {
        return $this->withScrollPhase(EventKind::ScrollEndDrag, $handler);
    }

    public function onMomentumScrollEnd(Closure $handler): self
    {
        return $this->withScrollPhase(EventKind::MomentumScrollEnd, $handler);
    }

    /** Fires once each time the current page changes, already during the drag. */
    public function onPageChange(Closure $handler): self
    {
        return $this->withEvent(
            EventKind::PageChange,
            static fn (string $payload = ''): mixed => $handler(PageChangeEvent::fromPayload($payload)),
        );
    }

    private function withScrollPhase(EventKind $kind, Closure $handler): self
    {
        return $this->withEvent(
            $kind,
            static fn (string $payload = ''): mixed => $handler(ScrollPhaseEvent::fromPayload($payload)),
        );
    }
}
