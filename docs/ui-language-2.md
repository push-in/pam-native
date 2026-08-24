# PAM Native UI Language 2

Language 2 is PAM Native's additive, compiled UI language. PHP remains PHP
8.5, attributes remain the source of truth, and every template/style feature is
lowered to the versioned UI IR before the application starts. There is no DOM,
JavaScript runtime, CSS interpreter or `eval` in the application hot path.

## Start here

PAM Native depends on the PAM Runtime. Install PAM, create a native project and
install the Composer product through PAM's verified toolchain:

```bash
curl --proto '=https' --proto-redir '=https' --tlsv1.2 \
  --connect-timeout 15 --max-time 60 --max-filesize 1048576 -fsSL \
  https://github.com/push-in/pam/releases/latest/download/install.sh | sh

pam doctor
pam init galaxy --template native
cd galaxy
pam composer require pushinbr/pam-native
pam doctor --fix
pam dev
```

Opt in per component. Existing `<template>` components remain Language 1 and
keep their behavior forever; `<template language="2">` enables the stricter
compiler and the new IR capabilities.

```php
<?php

declare(strict_types=1);

namespace App\Components;

use App\Domain\Product;
use Pam\Native\Attributes\Action;
use Pam\Native\Attributes\Prop;
use Pam\Native\Attributes\State;
use Pam\Native\Component;

final class ProductCard extends Component
{
    public function __construct(
        #[Prop(required: true)]
        public readonly Product $product,
    ) {
    }

    #[State]
    public bool $favorite = false;

    #[Action]
    public function toggleFavorite(): void
    {
        $this->favorite = !$this->favorite;
    }
}
?>

<template language="2">
    <Pressable
        class="product-card"
        recipe="card"
        variant:tone="featured"
        accessibilityLabel="Toggle favorite product"
        @press="toggleFavorite"
    >
        <Image
            :source="$product->image"
            :accessibilityLabel="$product->name"
        />
        <Text>{{ $product->name }}</Text>
    </Pressable>
</template>
```

`#[Prop]`, `#[State]`, `#[Action]`, `#[Event]`, `#[Slot]` and `#[Tag]` are the
canonical contracts. PAM does not invent `props {}` or `actions {}` syntax
inside PHP, so PHPStan, refactoring tools, formatters and ordinary PHP IDEs see
the real program.

## The 12 pillars

### 1. Typed tag, prop, event and slot contracts

Constructor parameters marked `#[Prop]` generate the component's prop
contract. `#[Event]` and `#[Slot]` are repeatable class attributes. Language 2
rejects missing/unknown props and events, invalid slot cardinality and template
handlers that are not public `#[Action]` methods. Legacy `#[Expose]` remains an
accepted compatibility alias.

Composer packages can publish a stable tag without string registration:

```php
#[Tag('Map')]
#[Event('regionChanged', RegionChanged::class)]
#[Slot('overlay', minimum: 0, maximum: 8)]
final class Map extends Component
{
    public function __construct(
        #[Prop(required: true)] public readonly Coordinate $center,
        #[Prop] public readonly float $zoom = 12,
    ) {
    }
}
```

Providers may also register a `TagContract` through
`TemplateRegistry::contract()` for native views that do not use PHP component
classes. Composer discovery remains governed by `pam-native.plugin.json`.

### 2. Native interaction states

Language 2 accepts the bounded states `:pressed`, `:focused`, `:disabled`,
`:selected`, `:checked` and `:hovered`. They compile to state IR. Press opacity
and scale lower directly to the platform press properties; the state is not
round-tripped through PHP.

```css
.card:pressed {
    opacity: 0.72;
    transform: scale(0.98);
}
```

### 3. Compiled design tokens

Tokens are declared once and become immutable compile-time custom properties.
Dot notation is normalized to CSS-safe hyphens in IR.

```css
@tokens {
    color.brand: #4F46E5;
    color.surface: #FFFFFF;
    space.md: 16px;
    radius.card: 20px;
}
```

Use them as `var(--color-brand)`, `var(--space-md)` and so on. Unknown or
circular token references fail compilation.

### 4. Recipes and typed variants

Recipes replace duplicated bags of classes with a named base and bounded
variant choices:

```css
@recipe card {
    base {
        padding: var(--space-md);
        border-radius: var(--radius-card);
    }

    variant tone=featured {
        background: var(--color-brand);
        color: #FFFFFF;
    }
}
```

```xml
<View recipe="card" variant:tone="featured" />
```

An unknown recipe, variant axis or choice is an error instead of a silent
visual fallback.

### 5. Responsive viewport and container queries

Queries use logical device pixels and a deliberately bounded grammar:

```css
@media (min-width: 768dp) {
    .catalog { gap: 24px; }
}

@container product (min-width: 320dp) {
    .title { font-size: 20px; }
}
```

Viewport rules react to the native dimensions event. Container rules use the
nearest authored container dimensions. There is no selector/layout feedback
loop and no runtime CSS parser.

### 6. Declarative flow

`Show` replaces simple conditional wrappers. `Match` performs strict matching
and accepts only `Case` plus one optional `Default`:

```xml
<Show when="$available">
    <Button label="Buy" @press="buy" />
</Show>

<Match value="$layout">
    <Case value="compact"><CompactCatalog /></Case>
    <Case value="expanded"><ExpandedCatalog /></Case>
    <Default><EmptyState /></Default>
</Match>
```

Existing `p-if`, `p-else-if`, `p-else` and `p-for` remain supported.

Two-way bindings use the Vue-like `p-model` directive. It binds `value` for
inputs and `checked` for `Switch`/`Toggle` without evaluating PHP in markup:

```xml
<Input p-model="$search" />
<Switch p-model="$enabled" />
```

`p-model:checked` is available when a custom checked control needs an explicit
binding target. The older `bind:value` and `bind:checked` spellings remain
compatible aliases.

### 7. Stable virtual lists

Every Language 2 loop below `VirtualizedList` or `VirtualGrid` requires
`p-key`. Keys must resolve to a unique string or integer and drive component
identity across insertions, moves and recycling:

```xml
<VirtualizedList>
    <ProductRow
        p-for="$product in $products"
        p-key="$product->id"
        :product="$product"
    />
</VirtualizedList>
```

Duplicate and non-scalar keys fail deterministically before corrupting local
row state.

### 8. Native compositor animations

Keyframes accept compositor-safe properties only. They are sorted and encoded
at build time, then executed by the Rust worklet VM and platform animators.

```css
@keyframes enter {
    from { opacity: 0; transform: translateY(12px); }
    to { opacity: 1; transform: translateY(0px); }
}
```

```xml
<Animated animation="enter" durationMs="240" easing="easeOut">
    <ProductCard :product="$product" />
</Animated>
```

Animations are interruptible and platform reduced-motion policy remains
authoritative.

### 9. Typed asynchronous branches

`Await` consumes `AsyncResource` or `AsyncValue` and exposes the existing
integer-backed async state contract:

```xml
<Await value="$products">
    <Pending><CatalogSkeleton /></Pending>
    <Content><Catalog :products="$data" /></Content>
    <Empty><EmptyCatalog /></Empty>
    <Error><RetryState :message="$message" /></Error>
    <Offline><OfflineCatalog :products="$data" /></Offline>
    <Stale><Catalog :products="$data" /></Stale>
</Await>
```

Branches are unique and validated by the compiler.

### 10. Accessibility as a compiler contract

Language 2 rejects meaningful images without `accessibilityLabel`; decorative
images must say `decorative="true"`. The engine exposes native audits for
labels and minimum 44-point interactive targets. Platform renderers continue
to enforce Dynamic Type, focus, custom actions and reduced motion.

Diagnostics use stable codes (`PAM2301`, etc.) so CI and editors can treat
selected findings as errors.

### 11. Composer-extensible tags

`#[Tag]` and `TagContract` let `pushinbr/*` or community packages add tags while
keeping Composer as the package authority. Contracts are registered before
Language 2 templates are validated. Plugin manifests, SDK ranges, protocol
versions and vendor-directory boundaries remain mandatory.

### 12. One editor-neutral language server

`vendor/bin/pam-native-language-server` provides formatting, Language 2
diagnostics, tag/directive/property completion, hover, go-to-definition,
references, safe workspace rename, document symbols, signature help, semantic
tokens and quick fixes. VS Code, Neovim, Helix and any LSP client use the same
Composer-installed server.

## Compatibility and permanence

- Language 1 is frozen and remains the default for existing `<template>` files.
- Language 2 is explicit and additive.
- UI IR version `2` and capability IDs `1..12` are stable and append-only.
- All coded versions, capabilities, states and kinds cross boundaries as
  sequential integers represented by enums.
- Unknown future capabilities are rejected by older runtimes rather than
  guessed.
- PHP attributes are canonical; template and CSS syntax are compile-time
  authoring layers only.

See [the migration guide](migration-ui-language-2.md) for adopting the stricter
contracts incrementally.
