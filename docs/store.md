# Pam Store

Pam Store is the global reactive state layer for Pam Native. Its source of
truth lives in the persistent PHP runtime. Kotlin and Swift receive only the
normal visual patches produced by the renderer; store snapshots are not copied
over the native bridge.

## Define and use a store

```php
use Pam\Native\Store\Attributes\Computed;
use Pam\Native\Store\Store;
use Pam\Native\Store\Stores;

final class CartStore extends Store
{
    protected function state(): array
    {
        return ['items' => [], 'loading' => false];
    }

    protected function persist(): array
    {
        return ['items'];
    }

    public function add(array $product): void
    {
        $items = $this->items;
        $items[] = $product;
        $this->items = $items;
    }

    #[Computed]
    protected function total(): int
    {
        return array_sum(array_column($this->items, 'price'));
    }
}

$cart = Stores::get(CartStore::class);
$cart->dispatch('add', ['product' => $product]);
$total = $cart->total;
```

`dispatch()` makes an action atomic. Multiple writes produce one history entry,
one persistence write and one render request. If an action throws, its state is
restored.

## Transactions, selectors and subscriptions

```php
$cart->transaction(function () use ($cart, $items): void {
    $cart->items = $items;
    $cart->loading = false;
}, 'cart:loaded');

$badge = $cart->selector(fn (CartStore $store): int => count($store->items));
$count = $badge->value($cart);

$id = $cart->subscribe(fn ($change) => Logger::debug($change->diff));
$cart->unsubscribe($id);
```

Computed properties and selectors are memoized. Changes enter the retained-tree
renderer, so unchanged native nodes are not rebuilt.

## Undo, optimistic updates and DevTools

```php
$cart->undo();
$cart->redo();

$cart->optimistic(
    name: 'cart:remove',
    apply: fn () => $cart->removeLocally($id),
    task: fn () => $api->delete("/cart/{$id}"),
);

$timeline = StoreDevTools::timeline();
StoreDevTools::timeTravel($timeline[0]['id']);
StoreDevTools::reset($cart);
```

Failed optimistic tasks restore the pre-action snapshot. A custom compensation
closure can be supplied. History and undo stacks are bounded to 200 entries.
`StoreDevTools::exportJson()` exposes integer-typed action/diff history.

## Middleware and action policies

```php
Stores::middleware(new AuditMiddleware());
$search->dispatch('search', ['query' => $query], ActionPolicy::Latest);
```

Policies are integer enums: `Every=1`, `Latest=2`, `Leading=3`,
`Debounced=4`. They coordinate overlapping/reentrant action starts. Native and
HTTP work remains callback-driven; commit callback results in a new action or
transaction.

## Persistence, encryption and migrations

Only keys returned by `persist()` are stored. Other keys are transient. The
default adapter uses Pam's atomic state storage.

```php
protected function version(): int
{
    return 2;
}

protected function migrations(): array
{
    return [new RenameCartTotalMigration()];
}
```

Migrations implement `StoreMigration` and must form a sequential chain. Pam
fails startup rather than silently discarding incompatible data.

For secrets, configure authenticated encryption before resolving stores:

```php
Stores::persistence(new EncryptedStatePersistence(
    getenv('PAM_STORE_KEY'),
));
```

The key is base64-encoded 256-bit data. Inject one unwrapped by Android
Keystore/iOS Keychain during bootstrap; never commit it.

## SQLite and server synchronization

`StoreReplica` keeps transport policy outside the engine:

```php
$replica = new StoreReplica(
    $cart,
    fn (string $key, int $version, array $state) =>
        $repository->upsert($key, $version, $state),
);
$replica->start();
$replica->merge($remote, $conflictResolver);
```

The writer can use Pam SQLite or an API. Incoming data is merged atomically.

## Testing and constraints

Call `Stores::resetRuntime()` between tests and exercise actions directly; no
simulator is required.

Declare state in `state()`, not as public PHP properties: direct public writes
bypass the reactive layer. State accepts JSON values only. Services, closures,
resources and native objects belong on the store as services, never in state.
