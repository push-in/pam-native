<?php

declare(strict_types=1);

namespace Pam\Native\Events;

use Closure;
use Pam\Native\Component;
use Pam\Native\Disposable;

/**
 * Handle returned by EventBus::on(), Observable::on() and Subscription::from().
 * cancel() removes the listener; a subscription owned by a component is
 * cancelled automatically when that component unmounts.
 */
final class Subscription implements Disposable
{
    private ?Closure $cancel;

    /** @param Closure(): void $cancel */
    private function __construct(Closure $cancel)
    {
        $this->cancel = $cancel;
    }

    /**
     * Adapts an id-based API to a Subscription, for example
     * `Subscription::from(fn () => App::offStateChange($id))`.
     *
     * @param Closure(): void $cancel
     */
    public static function from(Closure $cancel, ?Component $owner = null): self
    {
        $subscription = new self($cancel);
        if ($owner !== null) {
            $subscription->ownedBy($owner);
        }

        return $subscription;
    }

    /** Cancels this subscription when $owner unmounts. */
    public function ownedBy(Component $owner): self
    {
        if ($this->cancel !== null) {
            $owner->__pamOwn($this);
        }

        return $this;
    }

    public function cancel(): void
    {
        $cancel = $this->cancel;
        $this->cancel = null;
        $cancel?->__invoke();
    }

    public function active(): bool
    {
        return $this->cancel !== null;
    }

    public function dispose(): void
    {
        $this->cancel();
    }

    public function disposed(): bool
    {
        return $this->cancel === null;
    }
}
