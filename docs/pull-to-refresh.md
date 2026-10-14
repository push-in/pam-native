# Pull to refresh

PAM Native follows React Native's `RefreshControl`: the platform indicator
(Android `SwipeRefreshLayout` with the Material spinner, iOS
`UIRefreshControl`) and a controlled `refreshing` value.

## On the list or ScrollView

Give the refresh props straight to `ScrollView`, `VirtualizedList`,
`FlatList` or `SectionList`. PAM hosts the scrollable in a native
RefreshControl automatically; there is nothing to wrap by hand.

```xml
<VirtualizedList
    flexGrow="1"
    minHeight="0"
    :refreshing="$refreshing"
    on:refresh="reload"
    tintColor="#FF16A34A"
    refreshColors="#FF16A34A"
    :progressViewOffset="$headerHeight"
>
    <!-- rows -->
</VirtualizedList>
```

```php
public bool $refreshing = false;

public function reload(): void
{
    $this->refreshing = true;
    $this->api->feed(function (array $posts): void {
        $this->posts = $posts;
        $this->refreshing = false;
    });
}
```

| Prop | RN | Effect |
| --- | --- | --- |
| `refreshing` | `refreshing` | Controlled. `true` shows the spinner, `false` ends it. |
| `on:refresh` | `onRefresh` | Called once per completed pull. |
| `tintColor` | `tintColor` (iOS) | Spinner color on iOS; Android uses it when `refreshColors` is absent. |
| `refreshColors` | `colors` (Android) | Comma-separated colors or a list; the Android spinner cycles through them. iOS uses the first one when there is no `tintColor`. |
| `progressBackgroundColor` | `progressBackgroundColor` | Android spinner disc color. |
| `progressViewOffset` | `progressViewOffset` | Moves the spinner down, e.g. below a translucent header or the status bar. |
| `refreshIndicatorSize` | `size` | `default` or `large` (Android). |
| `refreshEnabled` | `enabled` | `false` keeps the control but blocks new pulls. |

The PHP API is the same: `Scroll::make($content)->refreshing($loading)
->onRefresh(fn () => $this->reload())->refreshTintColor(0xFF16A34A)
->refreshColors(0xFF16A34A)->progressViewOffset(56.0)`.
`<RefreshControl>` / `RefreshControl::make()` remain available as an explicit
wrapper (`tintColor()`, `colors()`, `progressBackgroundColor()`).

## Behavior

- A pull starts only when the content is already at its top. A drag that
  starts with the content scrolled down just scrolls; a fling that reaches the
  top never refreshes. A gesture that moves horizontally first belongs to a
  nested carousel and never starts a pull.
- Empty lists and content shorter than the viewport can still be pulled.
- `refreshing` always wins: if `on:refresh` does not set it to `true`, the
  spinner ends after the event round trip, as in React Native.
- The scrollable's outer box (size, flex, margins, position, `zIndex`) sizes
  the host; its other props (padding, content, scroll events) stay on it.
- Safe area and translucent headers: the spinner starts at the top of the
  scrollable. Offset it with `progressViewOffset` when a translucent header or
  the status bar overlaps that edge.
- Inverted lists pull from their visual top (the oldest end), like RN.
