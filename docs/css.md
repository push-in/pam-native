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
| `box-shadow` | one outer shadow `x y [blur] [spread] [color]`, `none` (multiple / `inset` → —) | A i |
| `elevation` | Android elevation | A |

## Backgrounds and effects

| Property | Values | Where |
| --- | --- | --- |
| `background`, `background-color` | any color, `none`, `transparent` (gradients/`url()` → —; use `<ImageBackground>` or `<Canvas>`) | A i |
| `filter` | `blur(<length>)`, `none` (other functions → —) | A (API 31+) |
| `backdrop-filter` | `none` only | — |
| `mix-blend-mode`, `clip-path`, `mask` | `normal` / `none` only | — |

## Transforms and motion

| Property | Values | Where |
| --- | --- | --- |
| `transform` | `translate()`, `translateX/Y()` (lengths or % of the own box), `translate3d(x, y, 0)`, `scale()`, `scaleX/Y()`, `rotate()`/`rotateZ()` (`deg`, `rad`, `grad`, `turn`), non-skewing `matrix()`, `none` (skew/3D → —) | A i |
| `translate`, `scale`, `rotate` | individual transform properties | A i |
| `transform-origin` | keywords and percentages | A |
| `transition` (+ `-property`, `-duration`, `-timing-function`, `-delay: 0`) | native implicit property animation on the UI thread (`linear`, `ease`, `ease-in`, `ease-out`, `ease-in-out`, `cubic-bezier()` ≈ ease-in-out) | A i |
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
