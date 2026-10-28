# Protocol extension table and renderer contract

Protocol v1 has two identifier tables each for properties (`PropKey`) and
events (`EventKind`):

| Table | Properties | Events | Status |
| --- | --- | --- | --- |
| core | `1`–`543` | `1`–`80` | closed: nothing new is added here |
| extension | `1001`–`65535` | `1001`–`65535` | every new identifier, append-only |

Each table counts up from its start with no gaps. `544`–`1000` and `81`–`1000`
are never used. The constants are `CORE_PROPERTY_LIMIT = 543`,
`CORE_EVENT_LIMIT = 80` and `EXTENSION_BASE = 1001`, defined in PHP
(`Protocol`), Rust (`pam-native-protocol`), Kotlin (`PamProtocol.kt`) and
Swift (`PamProtocol.swift`). `scripts/protocol-parity.py` checks that all four
are equal.

## Why no new wire encoding

The protocol already carries a property key as a `u16` in `PNT1`, `PNP1` and
`PNB1`. Event kinds cross the bridges as 32-bit integers. The only limit was
the table itself, not the wire. An escape key followed by a varint would
have changed the golden frames for no gain. An extension ID travels in the
same field: `1001` is `e9 03`. No frame, version or golden byte changes.

What older renderers lacked was a way to *refuse clearly*. The Rust engine
and the Android decoder reject an unknown key with `UnknownProperty`. The
host then clears its tree and asks for a remount, which fails again. iOS
skips the unknown key without a word. The renderer contract fixes both.

## Renderer contract (handshake)

Before PHP starts, each host exports what it implements as compact JSON in
`PAM_RENDERER_CONTRACT`:

```json
{"abi":1,"events":[79,1000],"properties":[540,1001],"protocol":[1,1]}
```

- `protocol`: the minimum and maximum protocol versions.
- `properties[0]` / `events[0]`: the highest core ID the host implements.
- `properties[1]` / `events[1]`: the highest extension ID it implements.
  A value of `1000` means none.

The keys are sorted and the JSON has no spaces. PHP (`RendererContract::toJson`),
Kotlin (`rendererContractJson`) and Swift (`pamRendererContractJSON`) pin the
same golden string. Android computes the values from its enums. iOS declares
`PAM_PROPERTY_CEILING`, `PAM_EXTENSION_PROPERTY_CEILING`,
`PAM_EVENT_CEILING` and `PAM_EXTENSION_EVENT_CEILING`, and the parity gate
checks them against the PHP tables.

On the PHP side:

1. **Boot.** `Runtime::boot` calls
   `RendererContract::current()->assertCompatible()` before the first frame.
   An ABI or protocol range mismatch throws `ProtocolMismatchException` and is
   reported in the `Boot` phase. No frame is sent.
2. **Encoding.** `TreeEncoder` checks every node that carries an extension
   property, and every handler for an extension event, against the contract.
   This covers full trees, patches and template-encoded nodes. If the host
   did not declare the ID, the encoder throws `ProtocolMismatchException`
   before the frame is built. The message names the property or event, its
   ID and the ceiling the host declared. Properties are sorted, so a node
   without extension IDs costs one integer comparison.

## Compatibility matrix

| SDK (app) | Renderer (host) | Result |
| --- | --- | --- |
| old | old | unchanged |
| old | new | unchanged: the host only *adds* table entries and an environment variable |
| new, core IDs only | old (no contract) | works: a missing contract is treated as the full core table without extensions, which is how those hosts behaved |
| new, uses an extension ID | old (no contract) | `ProtocolMismatchException`: "does not implement property X (1001); it predates the extension table…" |
| new, uses extension ID N | new, ceiling below N | `ProtocolMismatchException` naming N and the declared ceiling |
| any | ABI/protocol mismatch | refused at boot |

A host without a contract is assumed to implement the whole core table
(`543`/`80`), because that is what it would receive anyway. Hosts built from
this release onward declare their real core ceiling, so a renderer without
properties `541`–`543` refuses them too.

## Adding an identifier

1. Append the next extension ID (`1001`, `1002`, …) to `PropKey` or
   `EventKind` in PHP, the Rust enum and its `try_from`, the Kotlin enum, and
   Swift (`PamConstants` or `EventKind`).
2. On iOS, raise `PAM_EXTENSION_PROPERTY_CEILING` or
   `PAM_EXTENSION_EVENT_CEILING`. The parity gate fails until you do.
3. A new event also needs its handler property. Add it to the extension
   table as well.
4. Run `python3 scripts/protocol-parity.py`, `php packages/native/tests/run.php`
   and the Rust, Android and iOS protocol tests.

Parallel branches reserve their IDs before using them, because two branches
that take the same next number collide on rebase.
