# Migrating to Pam Native 1.25.0

## Prewarming a screen before navigating

```php
// A list row: mount the destination while the finger is down.
public function pressIn(): void
{
    $this->navigator->prewarm(AppRoute::Chat, ['chatId' => $this->id]);
}

public function press(): void
{
    // Same route and params: reuses the mounted screen.
    $this->navigator->push(AppRoute::Chat, ['chatId' => $this->id]);
}
```

A prewarmed screen is mounted (its `mount()` and first render run) but not
shown and not focused. Work that means "the user opened this screen" (read
receipts, analytics, network refresh, secure-window flags) must wait for
`navigationFocused()`; check `Navigator::isPrewarming()` in `mount()` to tell
the two cases apart. Do not change component state when the screen gets
focus if that state is only bookkeeping: the push then reuses the rendered
tree as is.

## Android behaviour changes

- A non-visible route inserted into a `NavigationHost` gets its first layout on
  the next frame; push transitions start one frame after a fresh route is
  mounted (prewarmed routes start immediately).
- Routes moving inside the same host are no longer detached from the window.
- Hidden virtual lists mount their visible cells over several frames.

Rebuild the native hosts. No protocol identifier changed.
