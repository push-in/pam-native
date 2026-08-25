# PAM Native 1.x architecture contract

PAM Native is a client runtime. It does not require PAM HTTP, Laravel, a
particular cloud, or any sibling plugin. PAM HTTP is a server framework and
does not require PAM Native. Integration happens only through ordinary public
protocols selected by the application.

## Stable runtime boundary

The 1.x boundary has three independently versioned integers:

| Contract | Current | Meaning |
| --- | ---: | --- |
| ABI | 1 | Native host and runtime memory/call contract |
| Wire protocol | 1 | Retained tree, patch, event, and module frames |
| Plugin manifest | 1 | Composer discovery and native autolinking metadata |

Startup negotiation intersects protocol ranges and named capabilities. ABI or
protocol mismatches fail closed. Optional capabilities may disappear without
preventing startup; required capabilities produce one actionable diagnostic.
Capability names describe runtime contracts such as `wire.binary.v1`—never a
Composer package or product name.

## Dependency law

- An app chooses `pushinbr/pam-native` and only the plugins it uses.
- A plugin may require the PAM Native core contract.
- A plugin may not require another `pushinbr/pam-native-*` plugin.
- A backend adapter may not require PAM Native or a client plugin.
- Cross-product integrations are explicit application composition over public
  interfaces or JSON/HTTP contracts.

Both PHP discovery and the Rust build/autolink path enforce required
capabilities. The Rust path also rejects Composer-installed plugins that depend
on sibling PAM Native plugins.

## PAM Freeze

`pam native:optimize src .pam/cache --entry=AppShell` creates:

- `pam-preload.php`, whose compiled PHP files are SHA-256 verified before load;
- `pam-preload.json`, the deterministic included component inventory;
- `pam-freeze.json`, the build ID, ABI, protocol, capabilities, entrypoints,
  and tree-shaking counts.

Freeze traverses custom component tags from each explicit entrypoint. Only
reachable generated classes enter the preload. Omitting `--entry` preserves
the compatible include-all behavior for applications with dynamic component
registration.

## Independent sync example

The mobile package `pushinbr/pam-native-sync` contains the local outbox, CRDTs,
and a provider-neutral HTTP transport. `pushinbr/pam-native-sync-laravel` is a
standalone server library. Neither installs the other. A non-PHP service can
implement the same bounded integer-coded JSON protocol.
