# Migrating to Pam Native 1.25.4

No application change is needed; rebuild the Android host after updating.

- `PamNotificationCredentials.replace` (the `replaceCredentials` notification
  call) now rejects the whole set when any account or token is invalid and
  keeps the previously stored credentials; before, the store was emptied and
  the entries before the invalid one were written.
- Hosts that run Android lint on the renderer sources no longer need a
  baseline for it.
