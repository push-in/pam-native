# PHP Runtime Manager

PAM owns, builds and versions every PHP runtime. Pam Native only declares its
PHP requirement and consumes the exact runtime resolved by the central PAM
installation. An application selects a supported PHP series in
`pam-native.json`:

```json
{
    "runtime": {
        "php": "8.5",
        "channel": "stable"
    }
}
```

The CLI resolves the series through PAM's checksummed runtime catalog and
writes `.pam-native/runtime.lock.json`. Build, run, development and profiling
all use that exact PAM-owned runtime directory.

```bash
pam mobile runtime:list
pam mobile runtime:use 8.5
pam mobile runtime:info
pam mobile runtime:update
```

Runtime IDs use `<php-version>-r<revision>`. A PHP security update changes the
PHP version and resets the revision; a PAM build, patch or flag change increments
only the runtime revision. Pam Native never publishes a PHP binary. For
example, `8.5.8-r2` is the second PAM build of the official PHP 8.5.8 source.

Release builds should commit `pam-native.json` and preserve the generated lock
as a CI artifact. Runtime archives must be published with checksums and must be
verified before extraction.
