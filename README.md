<!-- pam:product-page:start -->
<div align="center">

# PAM Native

**Build real Android and iOS applications with typed PHP.**

A Rust reconciler, retained native renderer, signals, navigation, UI-thread motion, and modular platform capabilities—with no JavaScript runtime or WebView.

[![Release](https://img.shields.io/github/v/release/push-in/pam-native?style=flat-square&label=stable)](https://github.com/push-in/pam-native/releases)
[![CI](https://img.shields.io/github/actions/workflow/status/push-in/pam-native/ci.yml?branch=main&style=flat-square&label=CI)](https://github.com/push-in/pam-native/actions)
![PHP](https://img.shields.io/badge/PHP-8.5-777BB4?style=flat-square&logo=php&logoColor=white)
![License](https://img.shields.io/github/license/push-in/pam-native?style=flat-square)

**[Documentation](https://push-in.github.io/pam-docs/native/overview/) · [Why this exists](#why-this-exists) · [What you can build](#what-you-can-build) · [Quick start](#quick-start) · [Issues](https://github.com/push-in/pam-native/issues)**

</div>

---

## Why this exists

A Rust reconciler, retained native renderer, signals, navigation, UI-thread motion, and modular platform capabilities—with no JavaScript runtime or WebView.

| | |
| --- | --- |
| **Role** | Native mobile product |
| **Execution path** | PHP 8.5 · Rust · Android Views · UIKit |
| **This repository owns** | Native project workflow, renderer, navigation, state, protocol, and plugin ABI |
| **Boundary** | Camera, video, maps, payments, and other capabilities remain focused packages |

## What you can build

- Production phone and tablet applications
- TV, foldable, keyboard, pointer, and D-pad experiences
- Media, social, commerce, and offline-first products assembled from focused modules

## Quick start

```bash
pam init my-app --template native
cd my-app
pam composer require pushinbr/pam-native
pam doctor --fix
pam dev
```

The **[PAM documentation](https://push-in.github.io/pam-docs/native/overview/)** covers prerequisites, production setup, and the complete workflow. PAM projects keep normal manifests and lockfiles; product features stay in the package that owns them.
<!-- pam:product-page:end -->

PAM Native lets PHP developers ship native mobile applications without adding
a JavaScript runtime or turning the app into a website inside a WebView.

Write familiar PHP components, state, actions, and Laravel-inspired named
routes. PAM keeps your PHP application alive, reconciles its interface in Rust,
and renders real UIKit and Android controls.

## See it in action

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

    #[\Pam\Native\Attributes\Action]
    public function increment(): void
    {
        $this->count++;
    }
}
?>

<template language="2">
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

Language 2 adds typed tag/event/slot contracts, native interaction states,
compiled tokens and recipes, viewport/container queries, declarative flow and
async branches, stable virtual-list identity, compositor keyframes,
accessibility diagnostics, Composer-extensible tags and a full editor-neutral
LSP. Attributes such as `#[Prop]` and `#[Action]` remain the canonical PHP API.
Read the [UI Language 2 guide](docs/ui-language-2.md).

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

- **[24 official ecosystem packages](docs/ecosystem-architecture.md)** — Firebase,
  auth, payments, subscriptions, maps, media, background transfer, realtime,
  offline sync, extensions, device capabilities, observability, testing, and
  plugin tooling. Install only the capabilities your application uses.
- **[PAM Native Nitro](https://github.com/push-in/pam-native-nitro)** — typed,
  offline-first models and native SQLite queries.
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

## Advanced: install only the SDK

Generated PAM projects already configure this dependency. Direct Composer
installation is intended for custom hosts and framework contributors; most
applications should start with `pam init` or `pam add`:

```bash
pam composer require pushinbr/pam-native:^0.7
```

## Community

Bug reports, focused feature proposals, documentation fixes, components, and
plugins are welcome. Start with the
[contribution guide](https://push-in.github.io/pam-docs/community/contributing/)
or open a discussion describing the application you want to build.

PAM Native is open source under Apache-2.0. You may build and sell
proprietary or open applications with it without a revenue limit or runtime
royalty. See [LICENSING.md](LICENSING.md) for the plain-language grant and the
full [LICENSE](LICENSE) for controlling terms.
