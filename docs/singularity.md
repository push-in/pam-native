# PAM Native Singularity

Singularity is the additive architecture introduced in PAM Native 0.7. It does
not replace protocol v1 frames or existing plugins. New public boundaries use
versioned schemas and sequential integer discriminators so future hosts can
negotiate capabilities without breaking existing applications.

## The twelve production pillars

1. **Compile-time contracts.** Annotated PHP interfaces generate deterministic
   PHP, Kotlin, and Swift identifiers plus a canonical SHA-256 manifest.
2. **Transactional rendering.** Rust prepares bounded generations, atomically
   commits complete work, discards obsolete work, and budgets 60/90/120 Hz.
3. **Structured concurrency.** Task groups, deferred values, hierarchical
   cancellation, deadlines, and bounded streams make task ownership explicit.
4. **Local-first by default.** Optimistic records, outbox batches, deterministic
   conflict policies, and authenticated AES-256-GCM journals are built in.
5. **Stateful hot reload.** Bounded state and replay actions carry deterministic
   fingerprints so hosts can preserve navigation and forms.
6. **Deterministic DevTools.** Ordered integer-kind timelines can be replayed;
   native overlays continue to provide redacted frame and capability evidence.
7. **Governed zero-config plugins.** Composer discovery is joined by immutable
   versions, digests, trust tiers, capability grants, and quality scores.
8. **Backend inside the app.** Internal request/response middleware runs through
   `LocalTransport` without binding TCP or granting network authority.
9. **Mature declarative UI.** Components, themes, forms, navigation, virtual
   lists, worklets, accessibility, RTL and Dynamic Type share one renderer.
10. **One release surface.** Build, sign and release produce reproducible
    Android/iOS/PHP artifacts, checksums, budgets, SBOM inputs and attestations.
11. **Public comparative evidence.** PAM, React Native, Flutter, and native are
    compared only when every report follows the same physical-device contract.
12. **One Composer domain.** DTOs, validation, transport and domain services can
    run on PAM Server, HTTP, Native, Desktop, workers, and tests.

## Typed contract example

```php
#[NativeModule(id: 1, name: 'Camera')]
interface CameraContract
{
    #[NativeMethod(id: 1)]
    #[NativePermission('camera.capture')]
    public function capture(string $quality, bool $flash): array;
}

$artifacts = ContractCompiler::compile([CameraContract::class], 'App.Native');
```

For application code generation, create `pam-native.contracts.php` returning
the interface class names. `pam codegen` then writes the manifest, fingerprint,
PHP, Kotlin and Swift outputs atomically under `.pam-native/contracts`. The same
operation is available directly as `pam contracts`.

The schema records request/event/stream kind, deadline, nullability, return
type, and capabilities. IDs are sequential integers and generated output is
fingerprinted, making platform drift a build failure.

## Internal application API

```php
$router = (new Router())->route(
    'POST',
    '/process',
    fn (Request $request) => Response::json(['bytes' => strlen($request->body)]),
);
$response = (new LocalTransport($router))->send(
    new Request('POST', '/process', body: 'payload'),
);
```

`LocalTransport::opensNetworkSocket()` is always false. Authentication and
authorization remain required at domain boundaries.

## Claims and evidence

PAM does not claim a universal performance multiple. A release may claim a
measured advantage only from retained same-device evidence passing thermal,
build-mode, payload, accessibility, and statistical comparability checks.
