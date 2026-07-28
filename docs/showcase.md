# PAM Native visual showcase

The PAM Native Gallery is an executable product demo, not a collection of
static mockups. The screenshots below were captured from the debug APK on a
physical Android device. The same PHP components and `.pam` templates run
through the production retained-tree, Rust layout and native renderer paths.

## Four native product experiences

| Experience | What it proves | Source |
| --- | --- | --- |
| Local marketplace | Fixed native header, scrolling catalog, press feedback and optimistic cart state | [`CommerceDemo.php`](../examples/showcase/src/CommerceDemo.php) · [`commerce.pam`](../examples/showcase/resources/native/screens/commerce.pam) |
| Offline finance | Persistent local state, native controls, data-rich layout and an offline-first visual language | [`FinanceDemo.php`](../examples/showcase/src/FinanceDemo.php) · [`finance.pam`](../examples/showcase/resources/native/screens/finance.pam) |
| Native chat | Retained input focus, keyboard-aware layout, animated message append and end-pinned scrolling | [`ChatDemo.php`](../examples/showcase/src/ChatDemo.php) · [`chat.pam`](../examples/showcase/resources/native/screens/chat.pam) |
| Field operations | Resilient sync state, operational hierarchy and touch-friendly field actions | [`FieldDemo.php`](../examples/showcase/src/FieldDemo.php) · [`field.pam`](../examples/showcase/resources/native/screens/field.pam) |

<table>
  <tr>
    <td align="center"><img src="assets/showcase/marketplace.png" width="240" alt="PAM Native marketplace showcase"></td>
    <td align="center"><img src="assets/showcase/finance.png" width="240" alt="PAM Native finance showcase"></td>
  </tr>
  <tr>
    <td align="center"><strong>Marketplace</strong></td>
    <td align="center"><strong>Offline finance</strong></td>
  </tr>
  <tr>
    <td align="center"><img src="assets/showcase/chat.png" width="240" alt="PAM Native chat showcase"></td>
    <td align="center"><img src="assets/showcase/field-operations.png" width="240" alt="PAM Native field operations showcase"></td>
  </tr>
  <tr>
    <td align="center"><strong>Native chat</strong></td>
    <td align="center"><strong>Field operations</strong></td>
  </tr>
</table>

## Run it

From the repository root:

```bash
pam composer install --working-dir pam-native/examples/showcase
pam mobile doctor pam-native/examples/showcase
pam mobile dev pam-native/examples/showcase
```

Build an installable debug APK:

```bash
pam mobile build pam-native/examples/showcase
adb install -r \
  pam-native/examples/showcase/.pam-native/android/app/build/outputs/apk/debug/app-debug.apk
```

## What to try

1. Add an item in the marketplace and watch PHP state update native views.
2. Toggle the finance balance and reopen the experience.
3. Send several chat messages: the keyboard remains open, the composer retains
   focus and each new bubble animates into the visible conversation.
4. Change field-operation state while offline and inspect the resilient sync
   messaging.
5. Open **LAB** from the gallery to exercise hot reload, persistent state,
   HTTPS/storage and the 10,000-row recycled list.

The showcase intentionally uses no screenshots or web surfaces inside the
application itself. What appears in these images is the actual native view
hierarchy produced by PAM Native.
