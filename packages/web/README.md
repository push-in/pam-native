# PAM Native Web

Compiles a [PAM Native](https://github.com/push-in/pam-native) app ahead of
time into a static HTML/JS app for Samsung (Tizen) and LG (webOS) TVs and
browsers. No PHP and no server run on the device: components, classes and
templates become JavaScript, `<style scoped>` becomes CSS, and a runtime of
about 20 KB gzip (with [`@preact/signals-core`](https://github.com/preactjs/signals),
MIT) keeps reactivity, D-pad focus and the remote keys.

```bash
pam composer require --dev pushinbr/pam-native-web
pam add target tizen
pam mobile tizen:build     # or webos:build, tizen:package, webos:run...
```

or directly: `vendor/bin/pam-native-web build . --target tizen|webos|web`.

See [docs/tv.md](https://github.com/push-in/pam-native/blob/main/docs/tv.md#samsung-and-lg-tvs-tizen-and-webos).
Developed in the `packages/web` directory of PAM Native.
