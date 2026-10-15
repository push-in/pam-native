# React Native component parity (1.5)

Components and events that React Native screens rely on, with the same
semantics on Android and, since 1.9.0, iOS (see [iOS parity](ios-parity.md)
for the per-feature status).

## Vector icons

`<Icon font="Ionicons" name="chatbubble-ellipses" size="24" color="#1B7A4E" />`
renders one glyph of a bundled icon font, exactly like
`react-native-vector-icons`: a `Text` using the icon font, default size 12,
black, `allowFontScaling` off, measured with the font's own metrics
(`includeFontPadding` included) and crisp at any size. CSS `color` and
`font-size` apply as on text.

Bundle the font and its glyph map from `react-native-vector-icons`:

```text
assets/fonts/Ionicons.ttf    # react-native-vector-icons/Fonts/Ionicons.ttf
assets/fonts/Ionicons.json   # react-native-vector-icons/glyphmaps/Ionicons.json
```

Any font name works by the same convention (`MaterialCommunityIcons`,
`Feather`, …). Other locations: `Icon::register('Brand',
'asset://assets/fonts/brand.ttf', 'assets/fonts/brand.json')` (or an array
`name => codepoint`). Unknown glyphs render `?`. PHP: `Icon::make($name,
$font, $size, $color)` and `Icon::has()`.

## Sticky headers

Mark a direct child of a `ScrollView` or `VirtualizedList` with
`stickyHeader="true"` (`Element::stickyHeader()`), the equivalent of
`stickyHeaderIndices`: it pins to the top once scrolled past and is pushed
away by the next sticky sibling. The pinned header is a real, interactive
view in a `ScrollView`, a `VirtualizedList` and a `VirtualGrid` (FlashList
parity): presses, pressed state and accessibility reach it, a touch inside it
never reaches the row scrolling underneath, and list rows keep recycling
while it is pinned. Its native views move into the pinned holder and back, so
their state (a horizontal tab rail offset, a focused input) is kept.

## VirtualizedList

- Cells are measured from their content (FlashList / `VirtualizedList`
  behaviour), including multi-column rows (tallest cell) and horizontal lists
  without `rowHeight`; `rowHeight`/`estimatedRowHeight` remain estimates for
  cells that are not populated yet.
- `fullSpan="true"` (`Element::fullSpan()`) makes a child span all columns
  — use it for `ListHeaderComponent`/`ListFooterComponent` equivalents placed
  first/last; they scroll with the list.

### Windowed materialization (long lists)

Write long lists the plain way (`VirtualizedList::make(...$rows)`,
`VirtualGrid`, or `p-for` inside `<VirtualizedList>`); nothing else is needed.
A vertical list with more than 96 rows is materialized by window, like
FlashList's `renderItem`: the first frame carries the first 48 rows (and the
rows around `initialScrollIndex`) and every other row as a placeholder — its
own root node with its authored size, without children or events, sized by
the list estimate (`estimatedRowHeight`) when it has no height of its own.

While the user scrolls, the host reports the rows on screen whenever a
placeholder comes within a screen of them (event kind 80, framework-owned,
never an app callback) and the next frame turns the rows ahead into full rows
in place: same node ids, so materialized rows keep their views, decoded
images and state and are never re-encoded. Sticky headers and full-span rows
are always materialized; horizontal lists and lists up to 96 rows are encoded
completely as before. On a 10,000-row list this removes ~99% of the first
frame's nodes from the PHP encoder, the engine's measure/layout pass and the
native mount, so the list appears in the time of a short one.

### Rows rendered on demand (templates, closures)

Nothing to opt into: when a `p-for` is the only child of a
`<VirtualizedList>` or `<VirtualGrid>` with more than 96 items, each row is
rendered when the window reaches it, exactly as the eager loop would render
it (same `p-key`, slot, events and component instances).

```html
<VirtualizedList estimatedRowHeight="72">
    <MessageRow p-for="$message in $messages" :key="$message['id']" :message="$message" />
</VirtualizedList>
```

Row components are children of the component that owns the template: they
mount when their row is first rendered, keep their state by key across
re-renders and reorders, and unmount when they leave the list or the
materialized window (while the owner is not re-rendered, their instances
stay keyed, so state survives a return to the window). State a row reads
subscribes the owner, so changing it re-renders the list as before. Loops
with 96 items or fewer, loops of control tags and lists with other children
render eagerly. `VirtualizedList::make()` defers closure rows (and
generators of them) the same way, keyed by position.

### Data-driven lists (`VirtualizedList::of`)

The FlatList/FlashList shape: items, a row builder and a key.

```php
VirtualizedList::of($messages, fn (Message $m, int $i) => MessageRow::make($m), fn (Message $m) => $m->id)
VirtualGrid::of(3, $photos, fn (Photo $p) => Thumb::make($p))
```

`$row` runs only for the rows the window materializes (and once per row per
list instance); the other rows are keyed placeholders. A 10,000-item list
builds about 48 rows on its first frame instead of 10,000 `Element` trees.
Keys default to the item's `key`/`id` (array key or public property), then
to its index. Theme defaults reach rows built later; a row builder may return
`null` for a hole. Both the PHP and the native encoders handle these lists.

### Keyed sections (tabs over one list)

Screens such as chat details render a header, a sticky tab rail and the
active tab's rows in one list. Instead of rebuilding the rows on every tab
switch, give each tab's rows a section and the list the active one:

```html
<VirtualizedList :activeSection="$activeTab">
    <Column><Text>Grupo</Text></Column>
    <Row stickyHeader="true"><!-- tab rail --></Row>
    <Row p-for="$row in $mediaRows" p-key="$row['key']" listSection="media">…</Row>
    <Row p-for="$row in $fileRows" p-key="$row['key']" listSection="files">…</Row>
</VirtualizedList>
```

Like `stickyHeader`, `listSection` is a property of the cell's root element:
a row component sets it on its own root (for example from a `section`
prop).
`Element::listSection()` / `VirtualizedList::activeSection()` are the
builder equivalents. Rows without a section (hero, rail, footer) are always
shown. Rows of the other sections stay in the tree, hidden:

- their layouts are kept (every section of a block starts at the same
  origin, so a switch moves nothing but the rows after the block) and the
  engine reuses the measured extent of clean rows instead of measuring them
  again;
- the views of their bound rows stay materialized off screen (Android
  parks them when their holder is recycled, iOS detaches them), images keep
  their decoded pixels, so switching back reattaches the same views: no PHP
  rebuild, no remount, no decode;
- while the rail is pinned each section keeps its own scroll position (a
  first visit starts with the rail at the top); with the header still
  visible the switch keeps the current offset, like React Native.

Changing `activeSection` is a property-only update. Render only the
sections the user visited (lazy, like React Native tab content) and keep
their row arrays and child components identical between renders so PHP
re-renders nothing but the rail. Rows that scroll out of a section's
window recycle as usual; under critical memory pressure parked rows are
released and rebuilt on demand.

## Pressed styles

`:active` and `:pressed` rules apply on touch-down to `Pressable` and
buttons, including `transform: translate…` (in points, like React Native's
`transform: [{ translateY: 1.1 }]`), `opacity`, `scale`, colors and
elevation:

```css
.primary:active { transform: translateY(1.1px); }
```

## Media events

`MediaPlayer` reports `on:mediaLoadStart`, `on:buffering` (`bool`),
`on:ready` (`MediaReadyEvent` with `naturalWidth`, `naturalHeight`,
`duration`; parameterless and `string` handlers keep working), progress, end
and error. PHP: `onLoadStart()`, `onBuffering()`, `onReady()`.

## In-app toast

```php
Toast::message('Mensagem enviada', 'Seu convite foi entregue.', ToastType::Success);
Toast::message('Sem conexão', 'Tentaremos novamente.', ToastType::Error, ToastPosition::Bottom,
    durationMs: 3000, fontFamily: 'asset://assets/fonts/SpaceGrotesk-Medium.ttf');
```

Matches `react-native-toast-message`'s default card (white, 6 dp radius, 5 dp
accent bar per type, bold 12 sp title over a 10 sp `#979797` message, 40 dp
below the top safe area, 4 s, tap to dismiss); every color, size, offset and
duration is configurable. `Toast::show()` keeps the system toast.

## Keyboard-aware scroll content

`<ScrollView keyboardInset="true">` adds the visible keyboard overlap as bottom
content inset inside the scroll content and keeps the focused input revealed
(`KeyboardAwareScrollView`).

On Android, `keyboardVerticalOffset="156"` adds 156 dp to the default 24 dp
focused-field clearance (180 dp in total). The viewport keeps its full height;
only scroll content receives the IME inset. Native inset animations and focus
changes reveal the editor without PHP callbacks. An unchanged inset or keyboard
hide does not reset a manually scrolled position. Use this directly on the
`ScrollView`, without a padding/resizing `KeyboardAvoidingView` around it.

## Bottom sheet

Sheets present like `@gorhom/bottom-sheet`: the backdrop fades while the sheet
and its handle slide by their own height, independently. Percentage snap
points resolve against the container minus the top safe-area inset. Drag,
backdrop dismissal, keyboard behaviours and the handle indicator are unchanged.

## Bundled font files by name

Besides `@font-face`, a bare `fontFamily` resolves bundled files by React
Native's conventions: `assets/fonts/{Family}-{Weight}.ttf`
(`SpaceGrotesk-SemiBold`), `{Family}_{weight}`, `{Family}_bold/_italic` and
`{Family}.ttf` (spaces removed). An exact weight/style file is used as is (no
synthetic bold); otherwise the closest file is synthesized like React Native.
