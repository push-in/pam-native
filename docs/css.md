# CSS support reference

PAM Native compiles CSS from `src/app.css`, `@import`ed files and component
`<style>` blocks **at build time**. Every declaration becomes a typed native
property; nothing is parsed while rendering. Layout runs in the Rust engine
(shared by Android and iOS), paint runs in the platform renderer.

Unsupported but valid CSS never disappears silently: the compiler fails with
`<file>:<line>: <message>` pointing at the declaration (for example
`src/Screens/Chat.pam.php:212: CSS property float has no native layout/paint
equivalent in …`). Properties with no native meaning (see *No-op properties*)
are accepted and ignored on purpose.

Legend: **A** Android renderer, **i** iOS renderer, **L** shared layout engine
(both platforms). `—` means a compile-time diagnostic.

## Layout (flexbox)

PAM follows React Native/Yoga defaults: `flex-direction: column`,
`align-items: stretch`, `flex-shrink: 0`, `align-content: flex-start`.

| Property | Values | Where |
| --- | --- | --- |
| `display` | `flex`, `inline-flex`, `block`, `flow-root`, `grid`, `inline-grid`, `none` (`contents`/`inline`/`table` → —) | L |
| `flex-direction` | `row`, `row-reverse`, `column`, `column-reverse` | L |
| `flex-wrap` | `nowrap`, `wrap`, `wrap-reverse` | L |
| `flex-flow` | `<direction> <wrap>` | L |
| `flex` | `none`, `auto`, `initial`, `<grow>`, `<grow> <shrink>`, `<grow> <shrink> <basis>`, `<basis>` | L |
| `flex-grow`, `flex-shrink` | `<number>` | L |
| `flex-basis` | `<length>`, `<percentage>`, `auto`, `content`, `calc()` | L |
| `order` | `<integer>` ≥ 0 | L |
| `justify-content` | `flex-start`/`start`/`left`, `center`, `flex-end`/`end`/`right`, `space-between`, `space-around`, `space-evenly` | L |
| `align-items`, `align-self` | `flex-start`, `center`, `flex-end`, `stretch`, `baseline`, `auto` (self) | L |
| `align-content` | `flex-start`, `center`, `flex-end`, `stretch`, `space-between`, `space-around`, `space-evenly` | L |
| `place-items`, `place-content`, `place-self` | shorthands of the above | L |
| `gap`, `row-gap`, `column-gap` | `<length>`; `gap: <row> <column>` | L |
| `margin: auto` (any side) | absorbs free space on the main axis, aligns on the cross axis | L |

`flex-basis` semantics: the flexible-length algorithm of CSS Flexbox §9.7
(grow, shrink weighted by `shrink × basis`, min/max freezing). A growing item
**without** a basis starts from 0 (`flex-grow: 1` behaves like `flex: 1 1 0`,
as in React Native). Write `flex-basis: auto` (or `flex: 1 1 auto`) to start
from the content/explicit size. `flex: 0` keeps the content size because native
layout has no CSS automatic minimum size.

## Grid

`display: grid`, `grid-template-columns: repeat(N, …)` / track lists (1–64
tracks), `grid-column: span N`, `gap`/`row-gap`/`column-gap`. Grid placement
by line numbers or areas → —.

## Box model and positioning

| Property | Values | Where |
| --- | --- | --- |
| `width`, `height`, `min-*`, `max-*` | `<length>`, `<percentage>`, `auto`, `none` (max), `calc()/min()/max()/clamp()` (`min-content`/`max-content`/`fit-content` → —) | L |
| `aspect-ratio` | `<number>`, `<w> / <h>`, `auto` | L |
| `padding`, `margin` (+ `-top/-right/-bottom/-left`, `-inline`, `-block`, `-inline-start/end`, `-block-start/end`) | 1–4 values, `auto` (margin) | L |
| `box-sizing` | `border-box` (always; `content-box` → —) | L |
| `position` | `static`/`relative` (offsets shift the box), `absolute`, `fixed` (relative to the viewport; `sticky` → —) | L |
| `top`, `right`, `bottom`, `left`, `inset`, `inset-inline/block` | `<length>`, `<percentage>`, `auto` | L |
| `z-index` | `<integer>`, `auto` | A i |
| `overflow`, `overflow-x/y` | `visible`, `hidden`, `clip` (`auto`/`scroll` → use `<ScrollView>`) | A i |
| `visibility` | `visible`, `hidden`, `collapse` | A i |
| `opacity` | `<number>`, `<percentage>` | A i |

## Borders and shadows

| Property | Values | Where |
| --- | --- | --- |
| `border`, `border-top/right/bottom/left` | width, style, color in any order; `none` | A (per side), i (single color) |
| `border-width` (+ per side) | 1–4 values, `thin`/`medium`/`thick` | A i |
| `border-color` (+ per side) | 1–4 values | A per side; i uses the last side color |
| `border-style` (+ per side) | `solid`, `dashed`, `dotted`, `none` (one style per box) | A i |
| `border-radius` (+ per corner, logical corners) | 1–4 lengths, `50%`+ (pill/circle); other `%` and elliptical `/` → — | A i |
| `box-shadow` | comma-separated list of `[inset] x y [blur] [spread] [color]`, `none` | A (all), i (first outer shadow) |
| `border-image`, `border-image-source` | `<linear/radial gradient> [1]`, `none` — a gradient stroke over the border widths that **follows `border-radius`** (story rings); `border-image-slice: 1`, other `border-image-*` initial values only | A |
| `elevation` | Android elevation | A |

Shadow details: blur follows CSS (Gaussian σ = blur / 2), spread grows the
shape and its non-zero radii, shadows paint last-to-first under the box
(outer) or inside the padding box above the background (inset). Outer
shadows are drawn by the parent container from cached ALPHA_8 nine-slice
masks (one small bitmap per radius/blur combination, shared by every view;
large blurs are rendered downscaled), so lists of shadowed cards cost one
bitmap draw per shadow and never re-blur on scroll.

## Backgrounds and effects

| Property | Values | Where |
| --- | --- | --- |
| `background-color` | any color, `none`, `transparent` | A i |
| `background` | comma-separated gradient layers plus an optional final color (`linear-gradient(…), radial-gradient(…), #fff`); `no-repeat` accepted; `url()`/`image-set()` → — (use `<ImageBackground>`) | A, i (color only) |
| `background-image` | gradient layers, `none` | A |
| `background-size/-position/-repeat/-clip/-origin/-attachment` | neutral values only (`cover`, `100% 100%`, `0 0`, `no-repeat`, `border-box`, `scroll`); gradients always cover the border box | A |
| `filter` | `blur()`, `brightness()`, `contrast()`, `saturate()`, `grayscale()`, `sepia()`, `invert()`, `opacity()`, `hue-rotate()`, in any combination/order, `none` (`drop-shadow()`/`url()` → —) | A (blur: API 31+) |
| `backdrop-filter`, `-webkit-backdrop-filter` | same functions as `filter`, `none` | A (API 31+, containers) |
| `mix-blend-mode`, `clip-path`, `mask` | `normal` / `none` only | — |

### Gradients

`linear-gradient()`, `radial-gradient()`, `repeating-linear-gradient()`,
`repeating-radial-gradient()` and legacy `-webkit-linear-gradient()`:

* Direction: angles (`deg`, `rad`, `grad`, `turn`), `to <side>` and magic
  corners (`to top right` — perpendicular to the box diagonal, resolved with
  the painted size).
* Radial: `circle`/`ellipse`, `closest-side`, `closest-corner`,
  `farthest-side`, `farthest-corner` (default) or explicit radii (`40px`,
  `40px 50%`), `at <position>` with keywords, percentages and lengths
  (1–2 values).
* Color stops: any CSS color, `transparent`, positions in `%` or lengths,
  double positions (`red 10px 40px`), auto positions distributed as in CSS,
  hard stops. Interpolation happens in **premultiplied** space like
  browsers, so `transparent` fades never darken. Color hints
  (`red, 30%, blue`) and `conic-gradient()` → —.
* Layers paint in CSS order (first on top) over `background-color`, clipped
  anti-aliased to `border-radius`. Shaders are rebuilt only when the box
  size changes; parsed gradients are cached per wire string.

`<LinearGradient>` mirrors `expo-linear-gradient` for React Native ports:

```html
<LinearGradient colors="rgba(0,0,0,.55), transparent" start="0.5, 0" end="0.5, 1" locations="0, 1" class="scrim"/>
<LinearGradient :colors="$ringColors" :start="$ringStart" :end="$ringEnd"/>
```

`colors` (list or comma string), `start`/`end` (`{x, y}` or `[x, y]` box
fractions, default top-center → bottom-center) and `locations` (fractions)
compile to the same native gradient; children render on top like `<View>`.

### React Native parity primitives

* `<Shimmer baseColor gradientColor duration enabled>` — skeleton sweep
  matching Zé Chat's native shimmer (highlight strip 1.25× the width,
  stops 0/.35/.5/.65/1, default `gradientColor` `#FFFFFF59`, 1200 ms).
  `baseColor` is the background; children render above the sweep; it clips
  to `border-radius` and pauses when detached, hidden or off screen (one
  shared Choreographer callback for every shimmer).
* `<Image blurRadius="28">` (and `filter: blur()` on images) blurs the
  decoded bitmap once, like React Native: every API level, opaque edges,
  no per-frame GPU work (σ = the radius in dp).
* `text-shadow: x y <blur> <color>` maps the blur to Android's
  `setShadowLayer` radius exactly like React Native `textShadowRadius`.
* Modal/BottomSheet scrims: `backdropColor="rgba(0,0,0,.28)"`.

### Filters and backdrop

`filter` color functions are composed at compile time into one 4×5 color
matrix. On Android 12+ blur and the matrix run as a GPU `RenderEffect`
(blur σ = the CSS length; edges fade like browsers); before API 31 the color
matrix uses a hardware layer and `blur()` is a no-op with a one-time
`PamNative` log warning.

`backdrop-filter` (Android 12+, on container nodes) re-records what is painted
behind the element every frame it draws — ancestor backgrounds and earlier
siblings along the ancestor chain, referencing their existing render nodes —
applies the blur/color matrix on the GPU and clips it to `border-radius`.
Limits: `SurfaceView` video is not captured (use the default texture-backed
`<Video>`), siblings painted later in z-order are ignored, and before API 31
it is a no-op (keep a translucent `background-color` as the fallback). Cost:
one GPU blur of the element's area per frame while it is visible — prefer it
for headers/sheets, not list rows.

iOS currently paints background colors and the first outer shadow; gradients,
extra/inset shadows, filters and backdrops log a one-time debug diagnostic
there.

## Transforms and motion

| Property | Values | Where |
| --- | --- | --- |
| `transform` | `translate()`, `translateX/Y()` (lengths or % of the own box), `translate3d(x, y, 0)`, `scale()`, `scaleX/Y()`, `rotate()`/`rotateZ()` (`deg`, `rad`, `grad`, `turn`), non-skewing `matrix()`, `none` (skew/3D → —) | A i |
| `translate`, `scale`, `rotate` | individual transform properties | A i |
| `transform-origin` | keywords and percentages | A |
| `transition` (+ `-property`, `-duration`, `-timing-function`, `-delay`) | native implicit animation on the UI thread with per-property durations, delays and easings (`linear`, `ease*`, `cubic-bezier()`, `steps()`, `spring(mass stiffness damping)`); see [Animations](animations.md) | A i |
| `@keyframes` + `<Animated keyframes="…">` | opacity/translate/scale/rotate keyframes run natively | A i |
| `animation*` properties | — (attach keyframes with `<Animated>`) | — |

## Typography

| Property | Values | Where |
| --- | --- | --- |
| `font` | `[style] [variant] [weight] <size>[/<line-height>] <family>` | A i |
| `font-family` | first family of the list; `@font-face` with packaged `asset://` TTF/OTF | A i |
| `font-size` | lengths, `rem`, `em` (= `rem` for font-size), keywords `xx-small`…`xxx-large` | A i |
| `font-weight` | `100`–`900`, `normal`, `bold`, `bolder`, `lighter` | A i |
| `font-style` | `normal`, `italic`, `oblique` | A i |
| `font-variant-numeric`, `font-variant(-caps/-ligatures)`, `font-feature-settings` | `tabular-nums`, `lining-nums`, `oldstyle-nums`, `slashed-zero`, `small-caps`, ligatures, `"tag" on/off/N` | A |
| `line-height` | `<length>`, unitless multiplier, `%`, `em`, `normal` | A i |
| `letter-spacing` | `<length>`, `em`, `normal` | A i |
| `text-align` | `left`/`start`, `center`, `right`/`end`, `justify` | A (justify API 26+), i |
| `text-transform` | `none`, `uppercase`, `lowercase`, `capitalize` | A i |
| `text-decoration`, `text-decoration-line` | `none`, `underline`, `line-through`, both (solid, text color) | A i |
| `text-shadow` | one shadow `x y [blur] [color]`, `none` | A |
| `text-overflow` | `ellipsis`, `clip` | A i |
| `-webkit-line-clamp`, `line-clamp` | `<integer>` | A i |
| `white-space`, `text-wrap` | `nowrap`/`pre` → one line; `normal`, `pre-wrap`, `pre-line`, `balance`, `pretty` | A i |
| `word-break`, `overflow-wrap`, `word-wrap` | `normal`, `break-word`, `anywhere`, `break-all`, `keep-all` | A i |
| `hyphens` | `none`, `manual`, `auto` | A |
| `direction` | `ltr`, `rtl` | A i |
| `color`, `caret-color`, `::placeholder { color }`, `::selection { color }` | colors | A i |
| `user-select` | `none`, `text`, `all`, `auto` | A i |
| `vertical-align`, `text-indent`, `writing-mode` (non-default) | — | — |

Text properties (`color`, font, `line-height`, `letter-spacing`,
`text-align`, `text-transform`, `font-feature-settings`, `text-shadow`) inherit
through containers, like CSS.

## Units and functions

`px`, `dp`, `pt` (all equal to device-independent pixels), `sp`, `rem` (16),
`em` (the element's font size), `%`, `vw`, `vh`, `vmin`, `vmax`, `dvh`/`svh`/
`lvh` (and `*vw`, `*vmin`, `*vmax` variants, all equal to the window
viewport), `env(safe-area-inset-*, <fallback>)`, `calc()`, `min()`, `max()`,
`clamp()`, `var(--name, <fallback>)`. Expressions are compiled to bytecode and
evaluated once per render with the window metrics; there is no per-frame
style work.

## Colors

Named colors, `transparent`, `#rgb`, `#rgba`, `#rrggbb`, `#rrggbbaa`,
`rgb()`/`rgba()` (comma and space syntax), `hsl()`/`hsla()`, `hwb()`, `lab()`,
`lch()`, `oklab()`, `oklch()`, `color(srgb | srgb-linear | display-p3 …)`,
`color-mix(in srgb | srgb-linear | oklab | oklch, …)`. All colors resolve to
ARGB at compile time (out-of-gamut values are clipped to sRGB).
`currentColor` → — (use the same `var()` as `color`).

## Selectors and cascade

* Type (`Text`), class, id, attribute (`[a]`, `=`, `~=`, `|=`, `^=`, `$=`,
  `*=`) and `*` selectors; descendant and child (`>`) combinators.
* `:not(<simple selectors>)`, `:is()`, `:where()`, `:matches()` (expanded at
  compile time).
* States: `:pressed`, `:active`, `:focus`, `:focus-visible`, `:hover`,
  `:disabled`, `:checked`, `:selected`, `:loading`, `:error`, `:empty`;
  comma-separated state lists are supported.
* Pseudo-elements: `::placeholder` and `::selection` (color only).
* Sibling combinators (`+`, `~`), structural pseudo-classes (`:first-child`,
  `:last-child`, `:nth-child()` …) and other pseudo-elements → — (bind a class
  from the loop instead).
* Cascade: `!important`, specificity, then source order. Repeated
  declarations inside one rule act as fallbacks (the last valid one wins).
* Custom properties: declared in `:root` (global) or inside a rule (visible
  to that rule's declarations), with `var()` fallbacks.

## At-rules

* `@import "relative.css";` (inside the Composer project).
* `@font-face` with packaged fonts.
* `@media`: `width`/`height` (`min-`/`max-`, comparison and range syntax),
  `orientation`, `prefers-color-scheme`, `prefers-reduced-motion`, `pointer`,
  `display-mode` and PAM device features; `and`, `or`, `,`, `not`, `only`,
  `screen`/`all`/`print`; `em`/`rem` breakpoints. `:root` variables resolve
  inside query blocks.
* `@container [name] (<condition>)` with the same condition grammar.
* `@supports (<declaration>)`, `not`, `and`, `or`, `selector()` — evaluated
  at compile time against the native compiler.
* `@layer`, `@keyframes`, PAM `@tokens` and `@recipe`.
* CSS nesting → —.

## No-op properties

Accepted for shared web/native CSS and ignored because they have no native
meaning: `cursor`, `-webkit-tap-highlight-color`, `touch-action`,
`will-change`, `contain`, `isolation`, font smoothing, `text-rendering`,
`appearance`, `outline*`, `scroll-behavior`, `overscroll-behavior*`,
`-webkit-overflow-scrolling`, `content-visibility`, `backface-visibility`,
`resize`, `accent-color`, `color-scheme`, `-webkit-box-orient`,
`unicode-bidi`, `tab-size`, `font-kerning`, `text-align-last`, scrollbar
properties.

## Performance contract

* Values are compiled once; the renderer receives typed integers/floats.
* Each property has a `StyleRenderCost` (`Composite`, `Paint`, `Layout`) in
  `StylePropertyCatalog`. Only layout keys mark the Rust layout tree dirty;
  paint-only changes (colors, shadows, transforms, opacity) never trigger a
  relayout.
* Transitions and keyframes animate on the native UI thread.
* Gradients, shadow lists and filter matrices travel as compact strings,
  are decoded once per distinct value (LRU-cached), and their shaders/masks
  are rebuilt only when the painted size changes. Animating gradients is not
  supported; animate `opacity`/transforms of a gradient layer instead.
