<div align="center">

# PAM Native

### Build real Android and iOS apps with PHP.

**The productivity you know from the web. The controls, performance, and feel users expect from native apps.**

[![Documentation](https://img.shields.io/badge/docs-push--in.github.io-5b50d6?style=flat-square)](https://push-in.github.io/pam-docs/native/overview/)
[![CI](https://img.shields.io/github/actions/workflow/status/push-in/pam-native/ci.yml?branch=main&style=flat-square&label=CI)](https://github.com/push-in/pam-native/actions/workflows/ci.yml)
![PHP](https://img.shields.io/badge/PHP-8.4-777BB4?style=flat-square&logo=php&logoColor=white)
![Android](https://img.shields.io/badge/Android-API%2026–36-3DDC84?style=flat-square&logo=android&logoColor=white)
![iOS](https://img.shields.io/badge/iOS-15%2B-000000?style=flat-square&logo=apple&logoColor=white)
![License](https://img.shields.io/badge/license-BUSL--1.1-f59e0b?style=flat-square)

**[Get started](https://push-in.github.io/pam-docs/native/overview/) · [Components](https://push-in.github.io/pam-docs/native/components/) · [Examples](docs/examples.md) · [Showcase](docs/showcase.md)**

</div>

---

PAM Native lets PHP developers ship native mobile applications without adding
a JavaScript runtime or turning the app into a website inside a WebView.

Write familiar PHP components, state, actions, and Laravel-inspired named
routes. PAM keeps your PHP application alive, reconciles its interface in Rust,
and renders real UIKit and Android controls.

```php
<?php

use App\Screens\HomeScreen;
use App\Screens\ProductScreen;
use Pam\Native\App;
use Pam\Native\Routing\Route;

App::run(
    Route::stack('main', initial: 'home', routes: function (): void {
        Route::screen('home', HomeScreen::class);
        Route::screen('product', ProductScreen::class);
    }),
);
```

Navigate by name. No URL-shaped internal routes, route context plumbing, or
navigator object passed through your component tree.

```php
$this->pushRoute('product', productId: 42);
$this->popRoute();
```

Route parameters map directly to typed constructor arguments:

```php
final class ProductScreen extends Component
{
    public function __construct(
        public readonly int $productId,
        public readonly bool $preview = false,
    ) {
    }

    public function render(): Renderable
    {
        return Screen::make(
            Text::make("Product #{$this->productId}"),
            Button::make('Open preview')->onPress(
                fn () => $this->replaceRoute(
                    'product',
                    productId: $this->productId,
                    preview: true,
                ),
            ),
        );
    }
}
```

## From an empty folder to a native app

```bash
pam init hello-native --template mobile
cd hello-native
pam composer install
pam mobile doctor .
pam mobile dev .
```

Edit PHP and save. PAM refreshes the mounted native tree while preserving the
state that can safely survive the change—including navigation, scroll, and
focused input where supported.

No JavaScript toolchain is required for application code. No DOM, CSS engine,
or browser is shipped in your app.

## It feels familiar because it is

Use explicit PHP trees when you want complete control:

```php
return Screen::make(
    SafeAreaView::make(
        Column::make(
            Text::make('Your cart')->style(new Style(fontSize: 28)),
            Text::make("{$this->items} items"),
            Button::make('Checkout')->onPress($this->checkout(...)),
        )->style(new Style(padding: 24, gap: 16)),
    ),
);
```

Or use single-file components when template authoring is a better fit:

```php
final class Counter extends Component
{
    #[State]
    public int $count = 0;

    public function increment(): void
    {
        $this->count++;
    }
}
?>

<template>
    <Column class="card">
        <Text class="title">Count: {{ $count }}</Text>
        <Button label="Increment" @press="increment" />
    </Column>
</template>

<style scoped>
    .card { padding: 24px; gap: 16px; }
    .title { font-size: 28px; font-weight: 700; }
</style>
```

Both styles compile to the same typed retained tree. Scoped styles become
native properties; they are not browser CSS.

## Native where it matters

- Real Android Views and UIKit controls
- Native stack, tabs, drawers, sheets, headers, gestures, and transitions
- Recycled lists and grids for large datasets
- Keyboard-safe inputs, safe areas, accessibility, and reduced motion
- Camera, media library, audio, contacts, location, sensors, notifications,
  push, sharing, files, SQLite, and secure storage
- Background execution and offline-first state
- Native animation and gesture execution without per-frame PHP traffic
- Hot reload, profiling, time travel, diagnostics, and repeatable benchmarks
- A plugin SDK for application-specific native modules and views

PHP describes the experience. Rust performs reconciliation and layout. Kotlin
and Swift apply bounded native mutations once per display frame.

## Built for real products

PAM Native includes the parts that demos usually skip:

- process and navigation restoration;
- bounded payloads, queues, caches, and retained state;
- typed integer-backed protocol enums;
- exact-host WebView allowlists and canonical file access;
- offline media caching and retry-safe operations;
- API 26 and API 36 Android contracts;
- UIKit simulator tests;
- deterministic protocol fuzzing;
- performance budgets and baseline profiles.

The public showcase includes a marketplace, finance dashboard, chat composer,
offline field workflow, component lab, hot reload, and a recycled list with
10,000 rows.

<table>
  <tr>
    <td align="center"><img src="docs/assets/showcase/marketplace.png" width="220" alt="Marketplace built with PAM Native"></td>
    <td align="center"><img src="docs/assets/showcase/finance.png" width="220" alt="Finance dashboard built with PAM Native"></td>
    <td align="center"><img src="docs/assets/showcase/chat.png" width="220" alt="Chat built with PAM Native"></td>
    <td align="center"><img src="docs/assets/showcase/field-operations.png" width="220" alt="Offline field app built with PAM Native"></td>
  </tr>
</table>

Explore the [showcase source](examples/showcase) or follow the
[showcase guide](docs/showcase.md) to build it locally.

## The PAM ecosystem

- **[PAM Native Nitro](https://github.com/push-in/pam-native-nitro)** — typed,
  offline-first models and native SQLite queries.
- **[PAM Mobile UI](https://push-in.github.io/pam-docs/mobile-ui/overview/)** —
  retained Material Design 3 components.
- **[PAM](https://github.com/push-in/pam)** — the persistent PHP runtime,
  async I/O, CLI, and wider application platform.
- **[Plugin SDK](docs/plugins.md)** — connect native SDKs, modules, views,
  resources, and PHP service providers.

## Learn at your pace

Start with the short path, then open the internals only when you need them:

1. [Create your first app](https://push-in.github.io/pam-docs/native/overview/)
2. [Build components](docs/components.md)
3. [Declare named routes](docs/navigation.md)
4. [Manage local and global state](docs/store.md)
5. [Use native capabilities](docs/examples.md)
6. [Understand performance and recovery](docs/runtime-performance.md)

For protocol, renderer, and runtime details, use the
[technical documentation index](docs/README.md).

## Install the SDK directly

Generated PAM projects already configure this dependency. For an existing
project:

```bash
composer require pushinbr/pam-native:^0.6
```

## Community

Bug reports, focused feature proposals, documentation fixes, components, and
plugins are welcome. Start with the
[contribution guide](https://push-in.github.io/pam-docs/community/contributing/)
or open a discussion describing the application you want to build.

PAM Native is source-available under BUSL-1.1. You may build and sell
proprietary or open applications with it without a revenue limit or runtime
royalty. See [LICENSING.md](LICENSING.md) for the plain-language grant and the
full [LICENSE](LICENSE) for controlling terms.
