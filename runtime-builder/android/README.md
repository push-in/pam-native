# Pam PHP Runtime for Android

This builder produces the static PHP Embed runtime shipped with Pam Native.
It downloads the official PHP source archive, validates its pinned SHA-256,
applies the smallest Android portability patch, and cross-compiles with the
pinned Android NDK.

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
./pam-native/runtime-builder/android/build.sh all
```

The resulting `runtime/android/<abi>` directories are release inputs and stay
out of Git. Release automation must archive them, publish a SHA-256 manifest,
and sign that manifest. No unverified third-party PHP binary is accepted by
the Pam build.

The MVP runtime intentionally keeps networking and storage in Android native
modules. Its PHP extension set is: Core, date, PCRE, ctype, filter, hash, JSON,
Phar, random, Reflection, session, SPL, standard, and tokenizer.
