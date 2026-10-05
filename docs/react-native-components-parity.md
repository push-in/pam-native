# React Native component parity (1.5)

Components and events that React Native screens rely on, with the same
semantics on Android.

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
away by the next sticky sibling. In a `ScrollView` the header stays
interactive; in a `VirtualizedList` rows keep recycling and the pinned header
is drawn from the last rendered frame.

## VirtualizedList

- Cells are measured from their content (FlashList / `VirtualizedList`
  behaviour), including multi-column rows (tallest cell) and horizontal lists
  without `rowHeight`; `rowHeight`/`estimatedRowHeight` remain estimates for
  cells that are not populated yet.
- `fullSpan="true"` (`Element::fullSpan()`) makes a child span all columns
  — use it for `ListHeaderComponent`/`ListFooterComponent` equivalents placed
  first/last; they scroll with the list.

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
