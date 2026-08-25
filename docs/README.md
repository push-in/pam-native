# Pam Native documentation

- [Performance offensive](performance-offensive.md): frame scheduling,
  incremental invalidation, reproducible tail-latency budgets, LTO, and PGO.

- [PHP Runtime Manager](runtime-manager.md)
- [Singularity architecture](singularity.md)
- [Stable 1.x architecture, independence, capabilities and Freeze](architecture-1.0.md)
- [UI Language 2](ui-language-2.md)
- [Migrating to UI Language 2](migration-ui-language-2.md)

Every public feature added to Pam Native must include a copyable example. Start
with the focused guide below or use the [capability cookbook](examples.md) for
short end-to-end recipes.

| Area | Guide with examples |
| --- | --- |
| Product-quality Android gallery and source tour | [Visual showcase](showcase.md) |
| Components, props, state, lifecycle, effects, slots, context and refs | [Component runtime](component-runtime.md) |
| Global store, transactions, persistence, sync, undo and optimistic state | [Pam Store](store.md) |
| Files, direct gallery, camera, notifications, SQLite, WebView, media, animation and device APIs | [Capability cookbook](examples.md) |
| Permissions, push, observation and lifecycle recovery | [Production capabilities](production-capabilities.md) |
| Gestures and composition | [Gestures](gestures.md) |
| Bottom sheets | [Bottom Sheet](bottom-sheet.md) |
| Image, video and audio cache | [Native media cache](media-cache.md) |
| Scheduler, compiler fast paths, profiling and recovery | [Runtime performance](runtime-performance.md) |
| Typed IDL, Suspense, worklets, jobs, offline sync, Canvas and server-driven UI | [Platform runtime](platform-runtime.md) |
| Native navigation | [Navigation](navigation.md) |
| Navigation Core 2 architecture, actions, lifecycle and top tabs | [Navigation Core 2](navigation-core-2.md) |
| React Navigation native-mobile parity across all 17 delivery blocks | [Navigation parity matrix](navigation-parity.md) |
| Components and tags reference | [Components](components.md) |
| Debug overlay and diagnostics | [DevTools](devtools.md) |
| Native/community plugins | [Plugins](plugins.md) |
| Release process | [Releasing](releasing.md) |
| Signed runtime bundles, rollout and rollback | [OTA](ota.md) |
| Incremental adoption in existing Android and iOS apps | [Brownfield](brownfield.md) |
| Migrating from 0.5 to 0.6 | [Migration guide](migration-0.6.md) |

Examples use integer-backed enums for every coded status, type, state and
priority. Imports are shown in the cookbook where they materially clarify a
recipe.
