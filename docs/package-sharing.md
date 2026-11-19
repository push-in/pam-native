# Optional Android share targets

`Share::isPackageInstalled($packageName, $callback, $failure)` reports `true` or `false` on Android and `null` on iOS. Android results respect package visibility and disabled applications. Android 11+ only sees the packages the application declares, so list every target under `android.queries.packages` in `pam-native.json`; the CLI writes them into the generated manifest's `<queries>`. The host manifest declares none (earlier releases carried Instagram, WhatsApp and WhatsApp Business for every app). No broad package-list permission is requested.

```json
{
  "android": {
    "queries": {
      "packages": ["com.instagram.android", "com.whatsapp", "com.whatsapp.w4b"],
      "intents": [{ "action": "android.intent.action.SENDTO", "scheme": "smsto" }]
    }
  }
}
```

`android.queries.intents` declares intents whose handlers the app resolves (`action`, optional `scheme` and `mimeType`). The host manifest keeps only the generic `VIEW` schemes `Linking` opens (`http`, `https`, `mailto`, `tel`, `geo`). The `smsto` `SENDTO` intent that `Sms::isAvailable()` resolves (pam-native-contacts) is no longer in the host manifest: the plugin's own manifest or the app's `android.queries.intents` declares it.

`Share::fileToPackage($privatePath, $packageName, mimeType: 'image/jpeg', text: $caption, opened: $opened, failure: $failure)` sends a sandbox file directly with ACTION_SEND, a FileProvider URI and temporary read grant. Missing/unavailable target apps report failure; the SDK never silently selects another app. Package names and private paths are validated. The opened callback means the target activity opened, not that a recipient sent anything.

On iOS, package names are not supported and targeted sharing fails explicitly. Use `Share::files([$privatePath], mimeType: 'image/jpeg', text: $caption)` for the native activity sheet with an image and caption on either platform.

Verification: PHP SDK bridge/validation tests; Android instrumented intent grants and real PackageManager installed/missing lookup tests. The iOS FilesParityTests capability/rejection test must run on macOS before iOS delivery.
