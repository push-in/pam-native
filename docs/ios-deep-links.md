# iOS incoming links

The iOS host routes custom URL schemes and universal links through the existing
`Linking::initial()` / `Linking::listen()` module. Declare registrations explicitly:

```json
{
  "ios": {
    "minimumVersion": "15.0",
    "urlSchemes": ["example"],
    "associatedDomains": ["applinks:example.com", "applinks:*.example.com"]
  }
}
```

`urlSchemes` appends an application entry to `CFBundleURLTypes`. The host's debug
diagnostic scheme and plugin URL registrations remain intact. HTTP, HTTPS, file,
and `pam-native-*` schemes are reserved. Schemes are unique ignoring case and
limited to 64 ASCII characters. Each list allows at most 32 entries.

`associatedDomains` merges into `com.apple.developer.associated-domains` alongside
plugin entitlements, including application groups and push capabilities. Values
must be `applinks:hostname` with an optional `*.` prefix; paths, query strings,
ports and malformed DNS labels are rejected. Existing conflicts remain errors.
The generated Xcode project signs the resulting entitlements file.

Universal links require the Associated Domains capability in the signing profile
and an `apple-app-site-association` document served by each domain, matching the
application's team and bundle identifier. Generating entitlements does not publish
that server document or configure the Apple developer account.

The host captures launch URLs before starting PHP, accepts registered custom
schemes in `application(_:open:options:)`, and forwards browsing-web activities in
`application(_:continue:restorationHandler:)`. URLs waiting for the PHP listener
use a bounded 32-entry queue; payloads over 8192 UTF-8 bytes are discarded. Closing
the runtime clears queued and initial URLs. Applications must validate the route
and parameters, and defer protected navigation until authentication completes.

Validation in this Linux change: Rust metadata/validation/host-wiring contracts.
`ios/Tests/PamNativeTests/LinkingModuleTests.swift` covers initial consumption,
queued custom and universal links, queue limits, oversized payloads and teardown.
UIKit compilation, XCTest and signed cold/warm universal-link tests still require
macOS/iOS. No claim of an iOS build is made by the Linux gates.
