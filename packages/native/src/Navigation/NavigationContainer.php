<?php

declare(strict_types=1);

namespace Pam\Native\Navigation;

use Closure;
use Pam\Native\Component;
use Pam\Native\Renderable;
use Pam\Native\System\Linking;

/**
 * Root authority for navigation state, actions, lifecycle and app integration.
 *
 * The container intentionally delegates rendering to the retained navigator:
 * dispatch and observation stay in PHP while transitions stay on the native UI
 * thread and never cross the runtime boundary per frame.
 */
final class NavigationContainer extends Component
{
    private ?NavigationSubscription $stateSubscription = null;
    private ?NavigationSubscription $unhandledSubscription = null;
    private bool $ready = false;
    private bool $systemLinkingEnabled = false;
    private ?int $linkSubscription = null;

    /** @var (Closure(array<string, mixed>): void)|null */
    private ?Closure $onStateChange = null;
    /** @var (Closure(): void)|null */
    private ?Closure $onReady = null;
    /** @var (Closure(NavigationAction): void)|null */
    private ?Closure $onUnhandledAction = null;
    /** @var (Closure(string, bool): void)|null */
    private ?Closure $onLink = null;

    public function __construct(
        private readonly Navigator $root,
        private readonly ?NavigationRef $ref = null,
    )
    {
        $root->claimSystemBackRoot();
        $this->subscribeToRoot();
        $ref?->attach($this);
    }

    private function subscribeToRoot(): void
    {
        if ($this->stateSubscription !== null || $this->unhandledSubscription !== null) return;
        $this->stateSubscription = $this->root->addListener(
            NavigationEventType::State,
            function (NavigationEvent $event): void {
                if ($this->onStateChange !== null) {
                    ($this->onStateChange)($event->data['state']);
                }
            },
        );
        $this->unhandledSubscription = $this->root->addListener(
            NavigationEventType::UnhandledAction,
            function (NavigationEvent $event): void {
                if ($this->onUnhandledAction === null) return;
                $data = $event->data['action'] ?? null;
                if (!is_array($data)) return;
                $type = NavigationActionType::tryFrom((int) ($data['type'] ?? 0));
                if ($type === null) return;
                ($this->onUnhandledAction)(new NavigationAction(
                    $type,
                    is_string($data['route'] ?? null) ? $data['route'] : null,
                    is_array($data['params'] ?? null) ? $data['params'] : [],
                    is_string($data['source'] ?? null) ? $data['source'] : null,
                    is_string($data['target'] ?? null) ? $data['target'] : null,
                    ($data['merge'] ?? false) === true,
                ));
            },
        );
    }

    public static function make(Navigator $root, ?NavigationRef $ref = null): self
    {
        return new self($root, $ref);
    }

    public function onReady(Closure $callback): self
    {
        $this->onReady = $callback;
        return $this;
    }

    /** @param Closure(array<string, mixed>): void $callback */
    public function onStateChange(Closure $callback): self
    {
        $this->onStateChange = $callback;
        return $this;
    }

    /** @param Closure(NavigationAction): void $callback */
    public function onUnhandledAction(Closure $callback): self
    {
        $this->onUnhandledAction = $callback;
        return $this;
    }

    public function theme(NavigationTheme $theme): self
    {
        $this->root->theme($theme);
        return $this;
    }

    /**
     * Owns cold- and warm-start application-link delivery for this container.
     * The native listener is armed on mount and always released on unmount.
     *
     * @param (Closure(string, bool): void)|null $callback
     */
    public function linking(?Closure $callback = null): self
    {
        $this->systemLinkingEnabled = true;
        $this->onLink = $callback;
        return $this;
    }

    public function mount(): void
    {
        $this->subscribeToRoot();
        $this->ref?->attach($this);
        if ($this->systemLinkingEnabled && $this->linkSubscription === null) {
            $this->linkSubscription = Linking::listenAndRoute($this->root, $this->onLink);
        }
        $this->ready = true;
        if ($this->onReady !== null) ($this->onReady)();
    }

    public function isReady(): bool
    {
        return $this->ready;
    }

    public function unmount(): void
    {
        $this->ready = false;
        if ($this->linkSubscription !== null) {
            Linking::unsubscribe($this->linkSubscription);
            $this->linkSubscription = null;
        }
        $this->stateSubscription?->unsubscribe();
        $this->stateSubscription = null;
        $this->unhandledSubscription?->unsubscribe();
        $this->unhandledSubscription = null;
        $this->ref?->detach($this);
    }

    public function dispatch(NavigationAction $action): bool
    {
        return $this->root->dispatch($action);
    }

    public function addListener(
        NavigationEventType $type,
        Closure $listener,
    ): NavigationSubscription {
        return $this->root->addListener($type, $listener);
    }

    /** @return array<string, mixed> */
    public function getRootState(): array
    {
        return $this->root->getState();
    }

    public function getCurrentRoute(): RouteContext
    {
        return $this->root->current();
    }

    public function getCurrentOptions(): ScreenOptions
    {
        return $this->root->currentOptions();
    }

    public function currentPath(): ?string
    {
        return $this->root->currentPath();
    }

    public function currentUrl(): ?string
    {
        return $this->root->currentUrl();
    }

    public function canGoBack(): bool
    {
        return $this->root->canGoBack();
    }

    public function render(): Renderable
    {
        return $this->root;
    }
}
