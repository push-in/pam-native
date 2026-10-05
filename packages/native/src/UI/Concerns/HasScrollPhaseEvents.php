<?php

declare(strict_types=1);

namespace Pam\Native\UI\Concerns;

use Closure;
use Pam\Native\EventKind;
use Pam\Native\ScrollPhaseEvent;

/**
 * RN scroll lifecycle events, detected natively: `onScrollBeginDrag`,
 * `onScrollEndDrag` (release velocity) and `onMomentumScrollEnd` (after
 * fling/paging settles, with the page index). Nothing is sent per frame.
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

    private function withScrollPhase(EventKind $kind, Closure $handler): self
    {
        return $this->withEvent(
            $kind,
            static fn (string $payload = ''): mixed => $handler(ScrollPhaseEvent::fromPayload($payload)),
        );
    }
}
