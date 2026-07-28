# PAM Native Gallery

This application is the visual and technical proof for PAM Native. Its gallery
contains four product-quality native experiences:

- a food marketplace with an interactive cart;
- an offline-first financial dashboard;
- a media-rich chat composer;
- a field operations screen with native sync state.

The original engineering lab remains available from the gallery. It exercises
persistent PHP state, incremental Rust rendering, HTTPS and native storage,
stack navigation, a remote image and a virtualized list with 10,000 rows.

From the repository root:

```bash
pam composer install --working-dir pam-native/examples/showcase
pam mobile doctor pam-native/examples/showcase
pam mobile dev pam-native/examples/showcase
```

The first screen is intentionally presentation-ready for screenshots and short
product demos while every interaction continues to use the real PAM runtime.

<table>
  <tr>
    <td><img src="../../docs/assets/showcase/marketplace.png" width="220" alt="Marketplace showcase"></td>
    <td><img src="../../docs/assets/showcase/finance.png" width="220" alt="Finance showcase"></td>
    <td><img src="../../docs/assets/showcase/chat.png" width="220" alt="Chat showcase"></td>
    <td><img src="../../docs/assets/showcase/field-operations.png" width="220" alt="Field operations showcase"></td>
  </tr>
</table>

See the [visual showcase guide](../../docs/showcase.md) for the source map,
interaction checklist and APK build instructions.
