# Optional Android share targets

`Share::isPackageInstalled($packageName, $callback, $failure)` reports `true` or `false` on Android and `null` on iOS. Android results respect package visibility and disabled applications. The host manifest declares the optional Instagram, WhatsApp and WhatsApp Business packages; other targets need their own `<queries><package .../></queries>` declaration. No broad package-list permission is requested.

`Share::fileToPackage($privatePath, $packageName, mimeType: 'image/jpeg', text: $caption, opened: $opened, failure: $failure)` sends a sandbox file directly with ACTION_SEND, a FileProvider URI and temporary read grant. Missing/unavailable target apps report failure; the SDK never silently selects another app. Package names and private paths are validated. The opened callback means the target activity opened, not that a recipient sent anything.

On iOS, package names are not supported and targeted sharing fails explicitly. Use `Share::files([$privatePath], mimeType: 'image/jpeg', text: $caption)` for the native activity sheet with an image and caption on either platform.

Verification: PHP SDK bridge/validation tests; Android instrumented intent grants and real PackageManager installed/missing lookup tests. The iOS FilesParityTests capability/rejection test must run on macOS before iOS delivery.
