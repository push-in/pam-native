# Typography and React Native layout parity

PAM Native measures and draws text the way React Native 0.7x does on
Android, so a screen ported from React Native keeps its line boxes, wrapping
and baselines to the pixel.

## Exact text measurement

Flexbox runs in the Rust engine, but text boxes are measured by the platform
text stack that later draws them — the same split React Native makes with
Yoga measure functions. On Android the engine calls the host for every text
box (`pam_native_engine_set_text_measurer`, see
`crates/pam-native-engine/src/text_measure.rs`), and `PamTextLayout` builds a
`StaticLayout` with exactly the parameters the `TextView` uses:

- font pixel size `ceil(fontSize × fontScale × density)` (React Native's
  integer `getEffectiveFontSize`), hinted glyph advances (no linear/subpixel
  paint flags), kerning, ligatures, emoji and font fallback;
- letter spacing authored in points, applied as pixels over the run's font
  size;
- `lineHeight` through React Native's `CustomLineHeightSpan` (every line is
  `ceil(lineHeight × density)` pixels, with the same ascent/descent split);
- `includeFontPadding` (default **true**, see below), fallback line spacing,
  high-quality line breaking and no hyphenation by default;
- width `ceil(max line width)` clamped to the available width, height = bottom
  of the last (or `numberOfLines`-th) line, first baseline for
  `align-items: baseline`. `numberOfLines` implies a tail ellipsis.

Results are cached per text/style/width. iOS and hosts without a measurer keep
the portable glyph-advance estimator.

## Nested text (inline spans)

A `Text` may contain text and nested `Text`/`Span` runs. They render as one
paragraph (one `StaticLayout`) and wrap together:

```html
<Text class="message">
    Olá <Text class="strong">mundo</Text>, veja
    <Span class="link" on:press="openLink">https://zé.chat</Span> e fale com
    <Span class="mention" on:press="openProfile">@ana</Span>
</Text>
```

```css
.message { font-size: 15px; line-height: 20px; color: #111; }
.strong { font-weight: 700; }
.link { color: #1B7A4E; text-decoration: underline; }
.mention { color: #0055FF; background-color: #EEF3FF; font-size: 14px; }
```

- Runs may change `font-size`, `font-weight`, `font-style`, `font-family`
  (including `@font-face` families), `color`, `background-color`,
  `text-decoration`, `letter-spacing` and `text-transform`; nested runs
  inherit from their ancestors.
- Paragraph properties (line height, alignment, `numberOfLines`, ellipsis,
  padding) belong to the outer `Text`.
- `on:press` on a run makes only that run pressable (links, mentions,
  hashtags). Presses on plain text fall through to the outer `Text`/parent.
- Whitespace inside `Text` follows JSX: spaces on a line are kept, line
  breaks and indentation between runs collapse.

PHP API:

```php
Text::rich(
    'Olá ',
    Text::make('mundo')->style(new Style(fontWeight: 700)),
    ' — ',
    Text::make('@ana')->style(new Style(textColor: 0xFF0055FF))
        ->on(EventKind::Press, fn () => $this->openProfile('ana')),
);
```

Wire contract: the outer node carries the concatenated `Text` and
`TextSpans` (`start,end,fontSize,fontWeight,fontStyle,color,backgroundColor,
decoration,letterSpacing,fontFamily,press,textTransform`, `;`-separated,
code-point offsets). Span presses arrive as `EventKind::SpanPress` with the
run's handler slot.

## includeFontPadding

React Native Android's default is `includeFontPadding: true`: the first and
last lines include the font's top/bottom padding (for Roboto 14 sp at 2.625×
the single-line box is 49 px instead of 43 px). PAM now uses the same default
for `Text` and measures with it. Opt out per element:

```css
.badge { include-font-padding: false; }        /* or -pam-include-font-padding */
```

```html
<Text includeFontPadding="false">12</Text>
```

`Text::make('12')->includeFontPadding(false)` does the same in PHP.

## Hairlines and device pixels

| Syntax | Value |
| --- | --- |
| `hairline` | `StyleSheet.hairlineWidth`: `PixelRatio.roundToNearestPixel(0.4)`, never 0 (1 physical px on 1×–3.5× screens) |
| `Ndpx` | `N` physical pixels (`N / density` points) |

They work anywhere a length is accepted, including `calc()` and border
shorthands: `border-bottom: hairline solid #DDD; height: 2dpx;`.
`Pam\Native\PixelRatio` exposes `get()`, `getFontScale()`,
`roundToNearestPixel()`, `getPixelSizeForLayoutSize()` and `hairlineWidth()`.

Layout keeps fractional points and the renderer snaps every edge to the
physical pixel grid (absolute left/top/right/bottom rounded, like Yoga's
`roundLayoutResultsToPixelGrid`); text widths are whole pixels.

## Borders and baselines (Yoga semantics)

- Border widths are part of the padding box: children and content (text,
  images) are inset by `padding + border` on every edge, and content-sized
  boxes grow by their borders.
- `align-items: baseline` / `align-self: baseline` use the first baseline of
  text, and for containers the baseline of their first in-flow child (or the
  first child that itself aligns to baseline), recursively — not the bottom
  edge.

## Font weights of bundled families

`@font-face` maps each weight of a family to its own file; when an exact
file exists the weight is resolved to that file and no synthetic bold is
applied:

```css
@font-face { font-family: "Space Grotesk"; src: url(asset://fonts/SpaceGrotesk-Regular.ttf); font-weight: 400; }
@font-face { font-family: "Space Grotesk"; src: url(asset://fonts/SpaceGrotesk-Medium.ttf); font-weight: 500; }
@font-face { font-family: "Space Grotesk"; src: url(asset://fonts/SpaceGrotesk-SemiBold.ttf); font-weight: 600; }
@font-face { font-family: "Space Grotesk"; src: url(asset://fonts/SpaceGrotesk-Bold.ttf); font-weight: 700; }
```

## onLayout

`on:layout` (template) or `Element::onLayout()` (PHP) receives a
`Pam\Native\LayoutEvent` with `x`, `y`, `width` and `height` in points,
relative to the parent, exactly like React Native's `onLayout`: once after
mount and again whenever the frame changes, coalesced to one event per element
per frame.

```html
<View on:layout="measured">…</View>
```

```php
public function measured(LayoutEvent $layout): void
{
    $this->bubbleWidth = $layout->width;
}
```
