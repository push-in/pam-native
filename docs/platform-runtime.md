# Platform runtime

PAM Native's platform runtime provides bounded building blocks for typed
bridges, prioritized work, asynchronous UI, native-frame programs,
virtualization, recoverable background work, offline mutation delivery,
hardware-accelerated vector drawing, and server-driven UI.

Every status, type, kind, and opcode is an integer-backed enum. Every
serialized structure is versioned and bounded before allocation.

## PHP extensions

Android and iOS embed the same PHP build (`runtime/catalog.json` in PAM,
built by `runtime-builder/android/build.sh` and `runtime-builder/ios/build.sh`
with `--disable-all`). Whatever is not in this list does not exist on the
device, even when the desktop PHP running `pam dev` has it.

| Extension | Android | iOS | Notes |
| --- | --- | --- | --- |
| Core, standard, date, pcre (JIT off), hash, json, random, Reflection, SPL | ✅ | ✅ | Always compiled. |
| ctype, filter, session, tokenizer, Phar | ✅ | ✅ | |
| Zend OPcache (no JIT) | ✅ | ✅ | PHP 8.5 runtime. |
| uri (and lexbor) | ✅ | ✅ | PHP 8.5 runtime. |
| **mbstring** | 🟡 polyfill | 🟡 polyfill | Not compiled. The SDK defines the `mb_*` functions in PHP (below). |
| **sodium, openssl** | 🟡 native | 🟡 native | Not compiled. `Pam\Native\Crypto` covers Ed25519 verification and AES-256-GCM through the host (below). |
| intl | ❌ | ❌ | Not bundled: ICU adds ~30 MB of data plus ~5 MB of code per ABI. |
| iconv, zlib, curl, gd, dom/xml/simplexml, pdo/sqlite3, fileinfo, bcmath, gmp, posix, pcntl, sockets | ❌ | ❌ | Use the native modules (HTTP, database, media) instead. |

### mbstring

`pushinbr/pam-native` autoloads `src/Polyfill/mbstring.php` (Composer
`autoload.files`) before any application or package code. It defines each
function only when it is missing, so a real ext-mbstring (desktop, tests) or an
application's own polyfill loaded first always wins. The implementation
(`Pam\Native\Polyfill\Mbstring`) follows PHP 8.5's ext-mbstring, including
malformed input (counted and replaced like mbstring, `mb_substitute_character()`
modes), full Unicode case mapping with the Greek final sigma and Turkish
ISO-8859-9 rules, East Asian widths, `mb_detect_encoding()` scoring and the same
`ValueError`s:

`mb_check_encoding`, `mb_chr`, `mb_convert_case` (all `MB_CASE_*`),
`mb_convert_encoding`, `mb_convert_variables`, `mb_decode_numericentity`,
`mb_detect_encoding`, `mb_detect_order`, `mb_encode_numericentity`,
`mb_encoding_aliases`, `mb_get_info`, `mb_internal_encoding`, `mb_language`,
`mb_lcfirst`, `mb_list_encodings`, `mb_ltrim`, `mb_ord`,
`mb_preferred_mime_name`, `mb_rtrim`, `mb_scrub`, `mb_str_pad`, `mb_str_split`,
`mb_strcut`, `mb_strimwidth`, `mb_stripos`, `mb_stristr`, `mb_strlen`,
`mb_strpos`, `mb_strrchr`, `mb_strrichr`, `mb_strripos`, `mb_strrpos`,
`mb_strstr`, `mb_strtolower`, `mb_strtoupper`, `mb_strwidth`,
`mb_substitute_character`, `mb_substr`, `mb_substr_count`, `mb_trim`,
`mb_ucfirst`.

Encodings: UTF-8, UTF-16/UTF-32 (BE/LE), UCS-2/UCS-4 (BE/LE), ASCII, 8bit and
every single-byte code page mbstring has (ISO-8859-1…16, Windows-1251/1252/1254,
KOI8-R/U, CP866, CP850, ArmSCII-8). Not available: legacy multi-byte encodings
(SJIS, EUC-*, BIG-5, GB18030, ISO-2022-*, UTF-7, which raise the usual
"must be a valid encoding" `ValueError`), `mb_ereg*`/`mb_split`/`mb_regex_*`
(use `preg_*` with the `u` modifier), `mb_convert_kana`, the MIME header
functions, `mb_send_mail`, `mb_parse_str` and the HTTP I/O functions.

Cost: none in the APK/IPA binary; the tables are ~120 KB of PHP loaded on first
use and cached by OPcache. Typical calls take 1–5 µs on short UI strings.

Why not compile ext-mbstring: measured on PHP 8.5.8 with the NDK 27 flags of
`runtime-builder/android/build.sh` (`--enable-mbstring --disable-mbregex`, no
Oniguruma), linked the way the app links `libphp.a` (`--gc-sections`, stripped):

| ABI | Code + data | `.so` file | Compressed (download) |
| --- | --- | --- | --- |
| arm64-v8a | +1.14 MB | +0.03 MB (page padding absorbed it) | +0.63 MB |
| x86_64 | +1.16 MB | +2.13 MB (16 KB page alignment) | +0.64 MB |

Close to the 1.5 MB-per-ABI budget, and it would also need a new PAM runtime
release (Linux and macOS PAM builds) and an iOS XCFramework rebuilt on a Mac.
The polyfill covers both platforms today with the same behaviour.

### Crypto (sodium, openssl)

`Pam\Native\Crypto` is the SDK's crypto API and what `UpdateVerifier` (signed
OTA manifests) and `LocalFirst\EncryptedJournal` use:

```php
use Pam\Native\Crypto;

Crypto::ed25519Verify($signature, $message, $publicKey); // bool, raw 64/32 bytes
$sealed = Crypto::aes256GcmEncrypt($plaintext, $key, $nonce, $aad); // ciphertext . 16-byte tag
$plaintext = Crypto::aes256GcmDecrypt($sealed, $key, $nonce, $aad); // ?string, null when not authentic
```

| Runtime | Ed25519 verify | AES-256-GCM |
| --- | --- | --- |
| Desktop / server / tests | ext-sodium | ext-openssl (or ext-sodium's AES-256-GCM) |
| Android | Kotlin verifier (`PamCrypto.kt`), every API level | Platform JCA/Conscrypt `AES/GCM/NoPadding` |
| iOS | CryptoKit `Curve25519.Signing` after libsodium's encoding checks | CryptoKit `AES.GCM` |

On a device the host provides the synchronous PHP function
`pam_native_crypto(int $operation, string $key, string $nonce, string $aad,
string $input): string|bool|null` (1 = Ed25519 verify, 2 = seal, 3 = open;
`null` when the host has no provider); `Crypto::ed25519Backend()` and
`aes256GcmBackend()` report which `CryptoBackend` is used. Every backend gives
the same bytes and the same decisions as libsodium/OpenSSL, including
libsodium's Ed25519 rules (S < L, no small-order R or public key, canonical
public key, cofactorless equation): `packages/native/tests/Fixtures/crypto-vectors.json`
(`pam scripts/generate-crypto-vectors.php`, 76 Ed25519 and 48 AES-256-GCM
vectors) is replayed by the PHP tests, the Android JVM and instrumented tests
and the iOS XCTests. Without any backend (a device host older than 1.19.0) the
calls throw `CryptoUnavailableException`, `UpdateVerifier` refuses the update
and `EncryptedJournal` throws; nothing dies with "Call to undefined function".

HMAC and HKDF need no host: `hash_hmac()`, `hash_hkdf()` and `random_bytes()`
are core. `Store\EncryptedStatePersistence` (XSalsa20-Poly1305
`sodium_crypto_secretbox`) still needs ext-sodium and throws on the device.

Why not compile the extensions into the runtime: libsodium 1.0.20 alone is
+267 KB (arm64-v8a) / +406 KB (x86_64) of code per ABI before ext-sodium's
glue, and its AES-256-GCM needs the ARMv8 Crypto/AES-NI extensions; OpenSSL
3.5's libcrypto is +4.2 MB per ABI (arm64-v8a), measured with the runtime's NDK
flags. Either would also need a new PAM runtime release and an iOS
XCFramework rebuilt on a Mac, while the platform crypto ships today on both.

### Build-time audit

While staging an Android or iOS build (`pam-native build`, `run`, `release`,
update bundles) the CLI runs `Pam\Native\Tooling\MobileRuntimeAudit` over the
bundle (application code and Composer packages, the SDK included; package
tests and binaries are skipped). Every function call or class use (`new`, `::`,
`extends`, `implements`) that resolves to an extension the selected runtime
lacks, and that no bundled file or the mbstring polyfill declares, is printed:

```
PAM Native warning: src/Money.php:14: class NumberFormatter comes from ext-intl, which the PAM mobile PHP runtime (Android/iOS) does not include
PAM Native warning: src/Ota.php:9: function sodium_crypto_sign_verify_detached() comes from ext-sodium, which the PAM mobile PHP runtime (Android/iOS) does not include; use Pam\Native\Crypto::ed25519Verify(), which works on the device
```

Calls with a device-ready equivalent name it (`Pam\Native\Crypto` for
Ed25519 verification and AES-256-GCM, `random_bytes()`, `bin2hex()`,
`base64_encode()`...). The SDK's own sources produce no finding (its
sodium/openssl use is guarded and goes through `Pam\Native\Crypto`), and
`tests/runtime_audit.php` keeps it that way.

Uses guarded in the same file by `extension_loaded('…')`, `function_exists('…')`
or `class_exists(X::class)` for that extension are trusted. Findings never fail
the build.

### Running PHP on a device

`scripts/android-php-runtime-test.sh [script…]` builds a minimal embed host
against the installed runtime's `libphp.a` for the connected device's ABI,
pushes the SDK and runs the scripts there (default
`packages/native/tests/device/mbstring_runtime.php`, which checks that
ext-mbstring is absent and every recorded mbstring result comes out of the
polyfill unchanged). `packages/native/tests/device/crypto_runtime.php` checks
that the runtime has no ext-sodium/ext-openssl and that, in this bare host
without `pam_native_crypto()`, updates and journals fail closed. The host's
crypto itself runs in the instrumented tests `NativeCryptoInstrumentedTest`
(vectors through `PamCrypto`) and `NativeCryptoBridgeInstrumentedTest` (a PHP
entry calling `pam_native_crypto()` through the real runtime; run that class on
its own). It removes everything it pushed.

## Typed bridge IDL

`IdlCompiler` accepts a versioned JSON contract and generates fingerprinted
PHP, Kotlin, Swift, and Rust identifiers. Module, method, and field IDs are
append-only sequential integers beginning at `1`.

```php
$artifacts = IdlCompiler::compile(file_get_contents('bridge.pam-idl.json'));
```

Schemas are limited to 1 MiB, 256 modules, 256 methods per module, and 128
fields per method. The SHA-256 fingerprint can reject mismatched artifacts.

## Priority scheduler

```php
Scheduler::schedule(
    fn (CancellationToken $token) => $search->refresh($token),
    TaskPriority::UserBlocking,
    coalesce: 'search-results',
);
```

Immediate, user-blocking, render, normal, background, and idle work runs in
priority order. Coalescing cancels obsolete work. Draining respects a frame
budget and callbacks receive cooperative cancellation.

## Async resources and Suspense

```php
$products = new AsyncResource(
    fn (CancellationToken $token) => $repository->products($token),
    key: 'products',
);
$products->load(TaskPriority::UserBlocking);

return Suspense::make(
    $products->value(),
    content: fn (array $items) => ProductGrid::make($items),
    fallback: ProductSkeleton::make(),
    failure: fn (AsyncValue $state) => ErrorCard::make($state->message),
);
```

Loading may retain stale data; replacement loads cancel obsolete work.

## Worklet bytecode

```php
use Pam\Native\UI\Animated;
use Pam\Native\UI\Text;
use Pam\Native\Worklets\Worklet;
use Pam\Native\Worklets\WorkletTarget;

return Animated::worklet(
    Text::make('Runs without PHP on every frame'),
    Worklet::input()->interpolate(0, 300, 0, 1)->clamp(0, 1),
    WorkletTarget::Opacity,
    durationMs: 300,
)->iterations(3);
```

Worklets are data-only numeric programs. They cannot call PHP, allocate
objects, perform I/O, or access global state. Programs are limited to 256
instructions and all values must remain finite. Android evaluates PNW1 on its
native frame animator and iOS evaluates it from `CADisplayLink`; PHP is not
entered between frames. Opacity, X/Y translation, scale, and rotation targets
are available, and the numeric input is elapsed time in milliseconds.

## Advanced virtualization

`VirtualizedList`, `VirtualGrid`, and `SectionList` use Rust layout and platform
recycling. They support heterogeneous retained cells, stable keys, authored or
estimated extents, grids, horizontal/inverted presentation, initial index,
bounded prefetch, scroll events, and end-reached delivery.

```php
return VirtualizedList::make(...$cells)
    ->estimatedRowHeight(72)
    ->prefetch(8)
    ->inverted()
    ->onEndReached($loadOlder, threshold: 0.25);
```

Scrolling and recycling do not require a PHP callback per frame.
When an Android route is retained off-screen, aggregate visibility restoration
also repairs visible holders whose rich subtree was released. Recovery is
bounded to one attempt for each holder and item identity so a conditionally
empty row remains idle instead of scheduling perpetual RecyclerView layouts.
This covers route transitions that do not trigger a window-level visibility
callback.

## Recoverable background jobs

`BackgroundJobs` stores jobs as idempotent offline mutations. Persist its
snapshot with PAM storage or Nitro and restore it after process death.

```php
$jobs->register('messages.sync', function (
    array $payload,
    CancellationToken $token,
): void {
    $sync->conversation((int) $payload['conversationId'], $token);
});

$jobs->dispatch(
    'messages.sync',
    uniqueKey: 'conversation:42',
    payload: ['conversationId' => 42],
);
```

`runReady()` schedules jobs at background priority. Cancellation and failures
use bounded exponential backoff. Native execution windows remain available
through `System\BackgroundTasks`.

## Offline mutation queue

`OfflineMutationQueue` provides idempotency-key deduplication; integer-backed
queued, sending, applied, retry, conflict, and failed states; exponential
backoff capped at one hour; 256 KiB payloads; 10,000 operations; and a bounded,
versioned snapshot.

## Hardware-accelerated Canvas

```php
return Canvas::make()
    ->roundedRectangle(8, 8, 120, 48, 12, 0xFF6750A4)
    ->circle(180, 32, 24, 0xFFFFD23F)
    ->line(8, 80, 220, 80, 4, 0xFF111111)
    ->style(new Style(height: 96));
```

Android renders the retained vector commands on a hardware layer; iOS renders
through UIKit/Core Graphics. Geometry must be finite. Payloads are limited to
10,000 commands and 1 MiB.

## Server-driven UI

Server-driven documents describe data, not executable PHP. Version `1`
allowlists view, column, row, text, button, image, scroll, and spacer nodes.

```php
$tree = ServerDrivenUi::render(
    $document,
    actions: fn (string $name): ?Closure => match ($name) {
        'offer.open' => $this->openOffer(...),
        default => null,
    },
);
```

Only locally resolved actions may run. Documents cannot name PHP classes or
functions. Styles use a numeric allowlist. Limits are 1 MiB, 10,000 nodes, 64
levels, and 16 KiB text. Signature verification, rollout, caching, and rollback
remain the responsibility of the trusted application update service.
