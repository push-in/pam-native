# App infrastructure

PAM Native 1.40 ships the reusable plumbing that every production app ends up
writing by hand: event buses, timers that stop with their screen, rate
limiters, bounded caches, optimistic overlays, display formatting and a
durable offline queue. Everything is pure PHP, so it behaves identically on
Android and iOS. A runnable screen using all of it is in
[`examples/showcase/src/InfrastructureDemo.php`](../examples/showcase/src/InfrastructureDemo.php).

| Need | API |
| --- | --- |
| Cross-screen events | `Events\EventBus`, `Events\Observable`, `Events\Subscription` |
| Release on unmount | `Component::own()`, `Disposable`, `owner:` arguments |
| Timers bound to a component | `Component::timers()` → `System\ComponentTimers` |
| Rate limiting | `Scheduling\Debouncer`, `Scheduling\Throttler`, `Scheduling\SingleFlight` |
| Bounded caches | `Cache\BoundedStore`, `Cache\IdLedger`, `Cache\CachePersistence` |
| Optimistic UI | `Store\OptimisticOverlay` |
| Display text | `Support\Format`, `Support\Text` |
| Offline writes | `Sync\OfflineMutationQueue`, `Sync\BackoffPolicy`, `Sync\DrainTrigger` |

## Ownership: nothing outlives its screen

Every handle that keeps work alive implements `Pam\Native\Disposable`
(`dispose()`, `disposed()`). A component releases what it owns when it
unmounts, so listeners and timers never leak into hidden or destroyed
screens. Subscribe in `mount()`: a component that is remounted runs `mount()`
again.

```php
public function mount(): void
{
    // Either pass the owner…
    $this->bus->on('feed.refresh', fn () => $this->reload(), owner: $this);
    // …or adopt any Disposable.
    $this->own(Subscription::from(fn () => App::offStateChange($id)));
}
```

`Subscription::from(Closure $cancel)` adapts existing id-based APIs
(`App::onStateChange()`, `DeviceStatus::watch()`, plugin listeners) so they
can be owned too. Cancelling twice is a no-op.

## EventBus and Observable

```php
use Pam\Native\Events\EventBus;
use Pam\Native\Events\Observable;

final class MessageRead
{
    public function __construct(public readonly string $chatId) {}
}

$bus = EventBus::shared();                 // process-wide, by name
$bus->on(MessageRead::class, fn (MessageRead $e) => $this->clearBadge($e->chatId), owner: $this);
$bus->emit(new MessageRead('42'));         // typed: keyed by class
$bus->once('session.expired', fn (string $token) => $this->logout($token));
$bus->emit('session.expired', $token);     // named: string key + payload

$unread = new Observable(0);
$unread->on(fn (int $now, int $before) => $this->badge = $now, owner: $this, immediate: true);
$unread->update(fn (int $count) => $count + 1);
```

- `on()`/`once()` return a `Subscription`; `emit()` returns how many listeners ran.
- Listeners run synchronously in subscription order. One cancelled during an
  emit is skipped; one that throws does not stop the others, and the first
  error is rethrown afterwards.
- A bus holds at most `maxListeners` (1,024 by default) and throws when a
  leak would exceed it.
- `EventBus::shared($name)` buses are emptied on runtime shutdown (hot reload).
- `Observable::get()` inside `render()` re-renders the component on change
  (it is backed by a `Signal`); `peek()` reads without tracking. Changes are
  compared with `===`.

## Component timers

`$this->timers()` returns the component's `ComponentTimers`:

```php
public function mount(): void
{
    $this->timers()->every(30_000, fn () => $this->poll());                 // ComponentTimer
    $this->hide = $this->timers()->timeout(3_000, fn () => $this->toast = null);
    $this->search = $this->timers()->debounce(300, fn (string $q) => $this->query($q));
    $this->typing = $this->timers()->throttle(3_000, fn () => $this->sendTyping());
}
```

- Every timer, debouncer and throttler is cancelled on unmount.
- In the background, timeouts and intervals follow their
  `TimerBackgroundPolicy`: `Suspend` (default) stops the native timer and
  re-arms it on return (a timeout keeps its remaining delay, an interval
  restarts a full period), `Cancel` drops it, `Continue` keeps it running for
  work that must finish in the background.
- `ComponentTimer` exposes `cancel()`, `active()`, `suspended()` and
  `repeating()`. Interval callbacks receive the timer, so they can cancel
  themselves.

Plain `System\Timers` remains available for work that is not tied to a
component.

## Debouncer, Throttler and SingleFlight

```php
use Pam\Native\Scheduling\{Debouncer, SingleFlight, Throttler};

$save = new Debouncer(700, fn (string $draft) => $this->persist($draft), maxWaitMs: 5_000);
$save->call($text);       // runs once typing pauses for 700 ms (at most every 5 s)
$save->flush();           // run now, e.g. in paused()

$progress = new Throttler(250, fn (int $pct) => $this->report($pct)); // leading + trailing
$progress->call($pct);

$flights = new SingleFlight();
$flights->run("profile:{$id}", function (Closure $done) use ($id): void {
    Http::get("https://api.example.com/users/{$id}", fn (HttpResponse $r) => $done($r));
}, fn (?HttpResponse $r, ?Throwable $error) => $this->show($r), again: false);
```

- `Debouncer` runs with the arguments of the last call; `cancel()` drops it,
  `pending()` reports it.
- `Throttler($interval, $action, leading: true, trailing: true)` never loses
  the final value: the last call made during a window runs when it closes.
- `SingleFlight::run()` returns `true` when it started the work and `false`
  when the caller joined an in-flight one. `$done($result, $error)` must be
  called once; exceptions thrown by `$work` reach the waiters. With
  `again: true`, a request made while the key is in flight runs once more
  afterwards and late callers get that fresher result.

## BoundedStore and IdLedger

```php
use Pam\Native\Cache\{BoundedStore, IdLedger, StoragePersistence};

$profiles = new BoundedStore(
    'profiles',
    maxItems: 200,           // LRU bound
    ttlMs: 600_000,          // optional expiry
    maxBytes: 512 * 1024,    // optional JSON-size budget
    scope: "account:{$accountId}",
    persistence: new StoragePersistence(),
);
$profiles->hydrate(fn (int $restored) => $this->ready());
$profiles->put($id, $profile);
$profile = $profiles->get($id);              // null when missing or expired
$profile = $profiles->remember($id, fn () => $this->fallback($id));
$profiles->forget($id);
$profiles->persist();                        // writes only when changed
App::onMemoryPressure(fn () => $profiles->trim(0.5));
$profiles->useScope("account:{$other}");     // persists, then hides the old account

$deleted = new IdLedger('feed.deleted', maxIds: 2_000, ttlMs: 30 * 86_400_000,
    scope: "account:{$accountId}", persistence: new StoragePersistence());
$deleted->add($postId);
$visible = $deleted->filter($posts, fn (array $post) => $post['id']);
```

- Least recently used entries are evicted first, by count and by bytes. A
  value larger than the whole byte budget is rejected (`put()` returns false).
- `peek()` reads without refreshing recency and `replace()` updates a live
  entry keeping its position and expiry, so FIFO stores use `peek()`/`replace()`;
  `keys()`/`all()` list from least to most recently used; `count()` excludes
  expired entries.
- Snapshots are stored under `pam.cache.<name>[.<scope>]` and trimmed (oldest
  entries first) to the backend's `maxValueBytes()`: 240,000 bytes for
  `StoragePersistence`, whose native store rejects values over 256 KiB and
  keys outside `[A-Za-z0-9_.-]` (keys pass through `Storage::safeKey()`, so
  `account:42` is stored as `account-42`).
  Only JSON values persist (arrays, strings, numbers, booleans, null,
  `JsonSerializable`); other values stay in memory. Expired entries are not
  restored, and memory entries stay newer than restored ones.
- `CachePersistence` is the storage seam: `StoragePersistence` uses the
  native key-value `Storage`; `CallbackPersistence` adapts any other backend
  (for example a Nitro/MMKV key-value table or SQLite); `MemoryPersistence`
  serves tests.

```php
$kv = new CallbackPersistence(
    load: fn (string $key, Closure $done) => $nitroKv->get($key, $done),
    save: fn (string $key, string $value, ?Closure $done) => $nitroKv->set($key, $value, $done),
    maxValueBytes: 4 * 1024 * 1024,
);
```

For plain JSON written straight to `Storage`, `Storage\StorageBudget` keeps
values under the limit instead of letting the native write fail:

```php
use Pam\Native\Storage\{Storage, StorageBudget};

Storage::set('feed.page', StorageBudget::encodeList($postsNewestFirst, limit: 48));
Storage::set('drafts', StorageBudget::encodeMap($draftsOldestFirst, limit: 100)); // keeps the newest
Storage::set('profile.me', StorageBudget::encodeObject($profile));                // '' when oversized
$kept = StorageBudget::trimList($items, limit: 200, maxBytes: 64_000);
```

## OptimisticOverlay

One overlay per record type, shared by every screen that lists those records:

```php
use Pam\Native\Store\OptimisticOverlay;

$likes = new OptimisticOverlay(maxEntries: 400);
$likes->on(fn (string $key, array $fields) => $this->markForRender(), owner: $this);

$applied = ['liked' => true, 'likes' => $post['likes'] + 1];
$likes->begin("post:{$id}", $applied);                    // pending
Http::post($url, function (HttpResponse $response) use ($likes, $id, $applied, $post): void {
    $response->successful()
        ? $likes->settle("post:{$id}", ['likes' => json_decode($response->body, true)['likes'] ?? null])
        : $likes->rollback("post:{$id}", $applied, ['liked' => $post['liked'], 'likes' => $post['likes']]);
});

$rows = $likes->applyAll($rows, fn (array $row) => "post:{$row['id']}");
```

- `settle($key, $confirmed)` merges the server answer only when no newer
  request for that key is still in flight.
- `rollback($key, $applied, $restore)` restores only the fields that still
  hold the failed tap's value; a later tap's change survives.
- `set()` records a confirmed change (a push, another screen).
- `revision()` + `dropSettled($keys, $revision)` let fresh server pages
  replace settled overlays written before the request; `olderThanMs:` adds an
  age cut (e.g. keep overlays younger than 30 s).
- Listeners are notified only when an entry's fields actually change.
- Beyond `maxEntries` the least recently written settled entry is dropped;
  entries with requests in flight are never evicted. Pass a
  `resolver: fn (array $row, array $fields): array` for domain merge rules.

## Format and Text

```php
use Pam\Native\Support\{DateStyle, Format, RelativeTimeStyle, Text};

Format::count(12_345);                       // "12.345"        en "12,345"
Format::countLabel(3, 'item', 'itens');      // "3 itens"
Format::compact(1_234);                      // "1,2 mil"       en "1.2K"
Format::compact(12_345);                     // "12 mil"
Format::compact(12_345, truncate: true, decimalsBelow: 1_000); // "12,3 mil"
Format::relative($createdAt);                // "há 5 min"      en "5m ago"
Format::relative($createdAt, style: RelativeTimeStyle::Long);   // "há 5 minutos"
Format::relative($createdAt, style: RelativeTimeStyle::Narrow); // "5 min"
Format::relative($old);                      // "12 out." / "12 out. 2025"
Format::bytes(1_572_864);                    // "1,5 MB"
Format::duration(65);                        // "1:05"; padMinutes: "01:05"
Format::time($sentAt, 'America/Sao_Paulo');  // "14:05"         en "2:05 PM"
Format::date($sentAt, DateStyle::Medium);    // "12 de out."    en "Oct 12"
Format::date($sentAt, DateStyle::Numeric);   // "12/10"; NumericWithYear "12/10/2025", NumericWithShortYear "12/10/25"
Format::dateTime($sentAt);                   // "12/10/2025 14:05"
Format::relative($t, style: RelativeTimeStyle::Tiny, maxWeeks: 0,
    dateStyle: DateStyle::Medium);           // "5m", "3h", "2d", then "12 de out."
Format::useLocale('en');                     // default locale (pt-BR)

Text::fold('Ação');                          // "acao"
Text::contains('Conversa com João', 'joao'); // true
[$folded, $map] = Text::foldWithMap($name);  // highlight matches in the original
```

`relative()` accepts Unix seconds, ISO-8601 strings or `DateTimeInterface`,
uses days up to `maxDays` (6; `PHP_INT_MAX` never stops), weeks up to
`maxWeeks` (4; 0 jumps from days to a date) and then a calendar date in
`dateStyle`, with the year when it differs. Invalid times or time zones give
an empty string. `Text::fold()` is canonical
decomposition minus combining marks plus lower case for Latin, Greek and
Cyrillic, without ext-intl.

## OfflineMutationQueue

```php
use Pam\Native\Cache\StoragePersistence;
use Pam\Native\Sync\{BackoffPolicy, DrainTrigger, OfflineMutationQueue};

$queue = new OfflineMutationQueue(
    backoff: new BackoffPolicy(baseMs: 2_000, maxMs: 1_800_000, factor: 2.0, jitter: 0.2, maxAttempts: 12),
    persistence: new StoragePersistence(),
    name: "sync.{$accountId}",
);
$queue->hydrate();
$drain = $queue->drainOn(
    fn (OfflineMutationQueue $q) => $this->send($q),
    DrainTrigger::Enqueue,
    DrainTrigger::Connectivity,
    DrainTrigger::Foreground,
    DrainTrigger::RetryDue,
);

$queue->enqueue("like:{$postId}:{$tapId}", 'post.like', ['post' => $postId, 'liked' => true],
    dedupeKey: "like:{$postId}");

private function send(OfflineMutationQueue $queue): void
{
    foreach ($queue->ready() as $mutation) {
        $queue->sending($mutation->id);
        Http::json('POST', $url, $mutation->payload, function (HttpResponse $r) use ($queue, $mutation): void {
            match (true) {
                $r->successful() => $queue->applied($mutation->id),
                $r->statusCode === 409 => $queue->conflict($mutation->id, $r->body),
                default => $queue->retry($mutation->id, (int) (microtime(true) * 1000), $r->error !== '' ? $r->error : 'HTTP '.$r->statusCode),
            };
        }, headers: ['Idempotency-Key' => $mutation->key]);
    }
}
```

- `dedupeKey`: a new mutation replaces an unsent (Queued or Retry) one with the
  same key, so rapid toggles send only the last intent; one already Sending is
  never replaced. The idempotency `key` still deduplicates exact repeats.
- Re-enqueueing an unsent `key` updates its payload; `stored:` runs once the
  queue holding the mutation is written (immediately without persistence) and
  `failed:` receives the write error.
- `batch(fn ($q) => ...)` applies several changes with one write; `clear()`
  empties the queue (logout); `all($operation)` filters; mutations carry
  `createdAtMs`. Keys and dedupe keys accept 1–255 printable ASCII characters.
- `BackoffPolicy` controls the retry delay; after `maxAttempts` sends,
  `retry()` marks the mutation `Failed`.
- Persistence: call `hydrate()` once; from then on every change is written
  (`pam.sync.<name>`), Applied mutations are omitted, a snapshot larger than
  the backend's `maxValueBytes()` leaves out Failed and then the newest
  mutations (they stay in memory; use a `CallbackPersistence` over SQLite for
  very large queues), mutations queued before
  hydration are merged rather than overwritten, and mutations caught mid-send
  by process death come back as `Retry`.
- `drainOn()` calls the callback only when mutations are ready. The
  connectivity watch (one `DeviceStatus` poll every 3 s) and the single retry
  timer run only while mutations are pending and the app is in the
  foreground. `online()` reports connectivity from another source, `drain()`
  runs the callbacks now, and the returned `Subscription` stops everything.
