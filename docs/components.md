# PAM components

PAM supports two authoring styles over one renderer. Normal `.php` components
build an explicit `Element` tree and remain the default starting point.
`.pam.php` components are an optional single-file syntax for teams that prefer
Laravel, Vue, or React-style composition.

Both styles become the same `Renderable -> Element -> PNT1/PNP1` render plan.
There is no WebView, JavaScript runtime, virtual DOM, or second native bridge.

## Register components

Register one or more source directories before running the root component:

```php
use App\Screens\Home;
use Pam\Native\App;

require __DIR__.'/vendor/autoload.php';

App::components(__DIR__.'/src', __DIR__.'/.pam-native/components');
App::run(App::make(Home::class, ['userName' => 'Taylor']));
```

Discovery recursively compiles only `*.pam.php` files. PHP code and templates
are cached separately, while all expressions are parsed by PAM's restricted
expression engine. Templates never use `eval` and cannot call global PHP
functions.

## Single-file component

```php
<?php

declare(strict_types=1);

namespace App\Components;

use Pam\Native\Attributes\State;
use Pam\Native\Component;

final class ProfileCard extends Component
{
    #[State]
    public bool $following = false;

    public function __construct(
        public string $name,
        public ?string $subtitle = null,
        public bool $elevated = false,
    ) {
    }

    public function toggleFollowing(): void
    {
        $this->following = !$this->following;
        $this->emit('changed', $this->following);
    }
}
?>

<template>
    <Column :class="['card', 'p-4', 'elevation-2' => $elevated]">
        <Row class="items-center justify-between">
            <Column>
                <Text>{{ $name }}</Text>
                <Text v-if="$subtitle" class="text-muted">
                    {{ $subtitle }}
                </Text>
            </Column>

            <Slot name="action" />
        </Row>

        <Button @press="toggleFollowing">
            {{ $following ? 'Following' : 'Follow' }}
        </Button>

        <Slot />
    </Column>
</template>
```

Constructor-promoted public properties are component props. Required
parameters are required props; PHP defaults define optional props. PAM updates
mutable public props on stable child instances. A changed private or readonly
constructor prop intentionally remounts the child instead of mutating it.

## Composition

Any discovered class can be used by its short class name:

```xml
<ProfileCard
    :name="$user->name"
    subtitle="PAM developer"
    :elevated="true"
    @changed="followingChanged"
>
    <template #action>
        <Button @press="openProfile">Open</Button>
    </template>

    <Text>Default slot content</Text>
</ProfileCard>
```

The template syntax includes:

- `{{ expression }}` interpolation;
- `:prop="expression"` and `:class="expression"` bindings;
- `@press="method"` native events and `@event="method"` component events;
- `bind:value="$property"` and `bind:checked="$property"` two-way bindings;
- `v-if`, `v-else-if`, and `v-else`;
- `v-for="$item in $items"` for arrays and `Traversable` values;
- `v-for="$number in $count"` for one-based repetition from `1` to `$count`;
- `<Slot />`, `<Slot name="..."/>`, and `<template #name>`;
- `key` for stable identity in repeated or reordered children.

Expressions support component properties, local loop values, public component
methods, arrays, comparisons, boolean operators, and ternaries. Business logic
stays in PHP methods rather than in markup.

An integer `v-for` source repeats the element that many times and exposes the
current one-based number. Zero and negative integers render nothing:

```xml
<Column v-for="$number in $count" :key="$number">
    <Text>Item {{ $number }}</Text>
    <Text>Any subtree can be repeated.</Text>
</Column>
```

## Responsive grid and flex

`Grid` is the explicit rich-content grid and defaults to 12 columns. `Row` and
`Column` remain normal flex containers unless a positive `columns` value is
set. A grid row uses 12 columns by convention, accepts any rich PAM
element as a child, wraps automatically, and measures every row from its
tallest child. It works inside scroll views and reflows when the window,
orientation, split-screen width, or foldable posture changes.

```xml
<Grid gutterX="16" gutterY="16">
    <Column span="12" spanSm="6" spanMd="4">
        <Image :source="$photo->url" aspectRatio="1" />
        <Text>{{ $photo->title }}</Text>
    </Column>

    <ProfileCard
        v-for="$profile in $profiles"
        :key="$profile->id"
        span="12"
        spanSm="6"
        spanLg="3"
    />
</Grid>
```

Every direct `Grid` child is a complete retained PAM subtree, so cells may
contain `Image`, `Pressable`, inputs, custom components, nested flex containers,
or another grid. Use `Grid` when the complete layout should stay mounted.

For large rich datasets, use `VirtualizedList` or `VirtualGrid`. Their cells
are real component trees, but Android materializes only the visible and
prefetched window through `RecyclerView`; recycled cells release their native
views while preserving stable keyed identity and event routing:

```php
use Pam\Native\UI\{Column, Image, Pressable, Text, VirtualGrid};

$cells = array_map(
    fn (Photo $photo) => Pressable::make(
        Column::make(
            Image::make($photo->url),
            Text::make($photo->title),
        ),
    )
        ->key((string) $photo->id)
        ->onPress(fn () => $this->open($photo->id)),
    $this->photos,
);

return VirtualGrid::make(2, ...$cells)
    ->rowHeight(224)
    ->prefetch(6)
    ->onEndReached(fn () => $this->loadNextPage(), 0.35);
```

`FlatList` remains source-compatible for lightweight string arrays.

The responsive values are mobile-first. A missing value inherits the closest
smaller breakpoint:

| Suffix | Active from |
| --- | ---: |
| base | `0dp` |
| `Sm` | `600dp` |
| `Md` | `840dp` |
| `Lg` | `1200dp` |
| `Xl` | `1600dp` |

Grid properties:

- `columns`: activates grid layout and defines its track count;
- `span`, `spanSm`, `spanMd`, `spanLg`, `spanXl`: occupied columns;
- `offset`, and its responsive variants: empty columns before the item;
- `order`, and its responsive variants: visual order without changing data;
- `gutterX` and `gutterY`: independent horizontal and vertical spacing;
- `gap`: fallback used when a directional gutter is omitted.

The same API is available as utility classes:

```xml
<Row class="grid-12 gutter-x-4 gutter-y-4">
    <Column class="col-12 col-sm-6 col-md-4">...</Column>
    <Column class="col-6 col-lg-3 offset-lg-1 order-md-2">...</Column>
</Row>
```

Use regular flex whenever tracks are unnecessary:

```xml
<Row class="items-center gap-3">
    <Column flexGrow="1">...</Column>
    <Button @press="save">Save</Button>
</Row>
```

The tree API exposes the identical features through `Style`:

```php
$grid = Row::make(...$cards)->style(new Style(
    gridColumns: 12,
    gridColumnGap: 16,
    gridRowGap: 16,
));

$card = Column::make(...)->style(new Style(
    gridSpan: 12,
    gridSpanSm: 6,
    gridSpanLg: 3,
));
```

## Lifecycle

Override only the hooks a component needs:

```php
public function boot(): void {}
public function mount(): void {}
public function rendered(): void {}
public function attached(): void {}
public function resumed(): void {}
public function updated(string $property): void {}
public function paused(): void {}
public function unmount(): void {}
```

`boot` runs once per instance. `mount` runs on its first render, `rendered`
runs after each render pass, and `attached` runs after the first native commit.
App state drives `resumed` and `paused`. Stable constructor prop changes invoke
`updated`; removing a component invokes `unmount`.

`#[State]` marks local reactive state for tooling. Existing `Restorable`
components continue to control process recreation persistence. The marker does
not introduce proxies or a second state store: normal PHP properties remain
the source of truth.

## Keep the tree API

The tag convention is optional. A project, package, screen, or individual
component can always override `render()` and return a normal element tree:

```php
final class Checkout extends Component
{
    public function render(): Element
    {
        return Screen::make(
            Column::make(
                Text::make('Checkout'),
                Button::make('Pay')->onPress($this->pay(...)),
            ),
        );
    }
}
```

Tree components and `.pam.php` components can render each other because both
use the same component lifecycle, element identities, Rust diff engine, binary
protocol, and Android UI-thread commit queue.

## Generators

```bash
pam mobile make:screen Orders .
pam mobile make:component MetricCard .
```

These commands generate `src/Screens/Orders.pam.php` and
`src/Components/MetricCard.pam.php` without overwriting existing files.
`pam init --template mobile` still starts with the explicit PHP tree so the
lowest-level model is always visible and available.
