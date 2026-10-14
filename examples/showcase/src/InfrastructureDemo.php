<?php

declare(strict_types=1);

namespace App;

use Pam\Native\Cache\BoundedStore;
use Pam\Native\Cache\IdLedger;
use Pam\Native\Cache\StoragePersistence;
use Pam\Native\Component;
use Pam\Native\Events\EventBus;
use Pam\Native\Events\Observable;
use Pam\Native\Renderable;
use Pam\Native\Scheduling\Debouncer;
use Pam\Native\Store\OptimisticOverlay;
use Pam\Native\Support\Format;
use Pam\Native\Support\Text as TextKit;
use Pam\Native\Sync\BackoffPolicy;
use Pam\Native\Sync\DrainTrigger;
use Pam\Native\Sync\OfflineMutationQueue;
use Pam\Native\UI\Button;
use Pam\Native\UI\Column;
use Pam\Native\UI\Screen;
use Pam\Native\UI\Text;
use Pam\Native\UI\TextInput;

/**
 * PAM Native 1.40 app infrastructure in one screen: an app event bus, an
 * observable badge, component timers, a debounced search over a bounded
 * cache, an id ledger, an optimistic like and a durable offline queue.
 */
final class InfrastructureDemo extends Component
{
    public static ?Observable $unread = null;
    private static ?BoundedStore $people = null;
    private static ?IdLedger $hidden = null;
    private static ?OptimisticOverlay $likes = null;
    private static ?OfflineMutationQueue $outbox = null;

    private string $query = '';
    private int $badge = 0;
    private string $clock = '';
    /** @var list<string> */
    private array $results = [];
    /** @var array{id: string, liked: bool, likes: int} */
    private array $post = ['id' => 'p1', 'liked' => false, 'likes' => 1_299];
    private ?Debouncer $search = null;

    public function mount(): void
    {
        self::$unread ??= new Observable(0);
        self::$people ??= new BoundedStore('demo.people', maxItems: 200, ttlMs: 600_000, maxBytes: 256 * 1024,
            persistence: new StoragePersistence());
        self::$hidden ??= new IdLedger('demo.hidden', maxIds: 500, ttlMs: 86_400_000, persistence: new StoragePersistence());
        self::$likes ??= new OptimisticOverlay();
        if (self::$outbox === null) {
            self::$outbox = new OfflineMutationQueue(new BackoffPolicy(baseMs: 2_000, maxMs: 600_000, maxAttempts: 10),
                new StoragePersistence(), 'demo.outbox');
            self::$outbox->hydrate();
            self::$outbox->drainOn(fn (OfflineMutationQueue $queue) => $this->send($queue),
                DrainTrigger::Enqueue, DrainTrigger::Connectivity, DrainTrigger::Foreground, DrainTrigger::RetryDue);
        }

        // Everything below stops by itself when this screen unmounts.
        self::$unread->on(fn (int $count) => $this->badge = $count, owner: $this, immediate: true);
        EventBus::shared()->on('people.refresh', fn () => self::$people?->clear(), owner: $this);
        self::$likes->on(fn () => $this->markForRender(), owner: $this);
        $this->timers()->every(30_000, fn () => $this->clock = Format::time(time()));
        $this->search = $this->timers()->debounce(300, fn (string $query) => $this->runSearch($query));
    }

    public function render(): Renderable
    {
        $post = self::$likes?->apply('post:p1', $this->post) ?? $this->post;

        return Screen::make(Column::make(
            Text::make("Unread {$this->badge} · {$this->clock}"),
            TextInput::make($this->query)
                ->placeholder('Search people')
                ->onChange(function (string $value): void {
                    $this->query = $value;
                    $this->search?->call($value);
                }),
            Text::make(implode(', ', $this->results)),
            Button::make(($post['liked'] ? 'Liked' : 'Like').' · '.Format::compact($post['likes']))
                ->onPress(fn () => $this->toggleLike($post)),
        ));
    }

    private function runSearch(string $query): void
    {
        $names = self::$people?->remember('directory', static fn (): array => ['Ana Lúcia', 'João', 'Érica']) ?? [];
        $this->results = array_values(array_filter(
            $names,
            static fn (string $name): bool => TextKit::contains($name, $query) && !(self::$hidden?->has($name) ?? false),
        ));
    }

    /** @param array{id: string, liked: bool, likes: int} $post */
    private function toggleLike(array $post): void
    {
        $applied = ['liked' => !$post['liked'], 'likes' => $post['likes'] + ($post['liked'] ? -1 : 1)];
        // The queue coalesces taps by dedupeKey, so the overlay records the
        // latest intent with set(); use begin()/settle() when every tap is sent.
        self::$likes?->set('post:p1', $applied);
        self::$outbox?->enqueue('like:p1:'.hrtime(true), 'post.like', ['post' => 'p1', 'liked' => $applied['liked']],
            dedupeKey: 'like:p1');
    }

    private function send(OfflineMutationQueue $queue): void
    {
        foreach ($queue->ready() as $mutation) {
            $queue->sending($mutation->id);
            // Send $mutation->payload with $mutation->key as the idempotency key,
            // then call applied(), retry() or conflict() from the HTTP callback.
            $queue->applied($mutation->id);
        }
    }
}
