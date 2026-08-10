# PAM components

PAM supports two authoring styles over one renderer. Normal `.php` components
build an explicit `Element` tree and remain the default starting point.
`.pam` components are the recommended single-file syntax for teams that prefer
Laravel, Vue, or React-style composition.

Both styles become the same `Renderable -> Element -> PNT1/PNP1` render plan.
There is no WebView, JavaScript runtime, virtual DOM, or second native bridge.

## Register components

Register one or more source directories before running the root component:

```php
use App\Screens\Home;
use Pam\Native\App;

require __DIR__.'/vendor/autoload.php';

App::components(__DIR__.'/src', __DIR__.'/.pam-native/components');
App::run(App::make(Home::class, ['userName' => 'Taylor']));
```

Discovery recursively compiles `*.pam` and legacy `*.pam.php` files. PHP code and templates
are cached separately, while all expressions are parsed by PAM's restricted
expression engine. Templates never use `eval` and cannot call global PHP
functions.

## Single-file component

```php
<?php

declare(strict_types=1);

namespace App\Components;

use Pam\Native\Attributes\State;
use Pam\Native\Component;

final class ProfileCard extends Component
{
    #[State]
    public bool $following = false;

    public function __construct(
        public string $name,
        public ?string $subtitle = null,
        public bool $elevated = false,
    ) {
    }

    public function toggleFollowing(): void
    {
        $this->following = !$this->following;
        $this->emit('changed', $this->following);
    }
}
?>

<template>
    <Column :class="['card', 'p-4', 'elevation-2' => $elevated]">
        <Row class="items-center justify-between">
            <Column>
                <Text>{{ $name }}</Text>
                <Text p-if="$subtitle" class="text-muted">
                    {{ $subtitle }}
                </Text>
            </Column>

            <Slot name="action" />
        </Row>

        <Button @press="toggleFollowing">
            {{ $following ? 'Following' : 'Follow' }}
        </Button>

        <Slot />
    </Column>
</template>
```

Constructor-promoted public properties are component props. Required
parameters are required props; PHP defaults define optional props. PAM updates
mutable public props on stable child instances. A changed private or readonly
constructor prop intentionally remounts the child instead of mutating it.

## Composition

Any discovered class can be used by its short class name:

```xml
<ProfileCard
    :name="$user->name"
    subtitle="PAM developer"
    :elevated="true"
    @changed="followingChanged"
>
    <template #action>
        <Button @press="openProfile">Open</Button>
    </template>

    <Text>Default slot content</Text>
</ProfileCard>
```

The template syntax includes:

- `{{ expression }}` interpolation;
- `:prop="expression"` and `:class="expression"` bindings;
- `@press="method"` native events and `@event="method"` component events;
- `bind:value="$property"` and `bind:checked="$property"` two-way bindings;
- `p-if`, `p-else-if`, and `p-else`;
- `p-for="$item in $items"` for arrays and `Traversable` values;
- `p-for="$number in $count"` for one-based repetition from `1` to `$count`;
- `<Slot />`, `<Slot name="..."/>`, and `<template #name>`;
- `key` for stable identity in repeated or reordered children.

Expressions support component properties, local loop values, public component
methods, arrays, comparisons, boolean operators, ternaries, and
right-associative null coalescing with `??`. Business logic stays in PHP
methods rather than in markup.

An integer `p-for` source repeats the element that many times and exposes the
current one-based number. Zero and negative integers render nothing:

```xml
<Column p-for="$number in $count" :key="$number">
    <Text>Item {{ $number }}</Text>
    <Text>Any subtree can be repeated.</Text>
</Column>
```

`p-for` syntax is validated while the component template is compiled. Invalid
indexed forms such as `$item, $index in $items` fail the build; use the loop
value itself, enrich the presented item with an integer index, or use an integer
source when an explicit one-based number is needed.

`GestureDetector` must resolve to exactly one native child for every state.
Wrap layered content in a `View`, or use a complete `p-if`/`p-else-if`/`p-else`
chain when the child type changes. The compiler rejects zero-child and
multi-child detector trees before they reach a device.

`v-if`, `v-else-if`, `v-else`, and `v-for` remain accepted temporarily when
migrating existing components. They are deprecated aliases; new code and
editor metadata use the distinctive `p-*` PAM directives.

A component still declares one root in source, but that root may use `p-if`.
When its condition is false, PAM emits an inert invisible placeholder that
consumes no flex space. Multiple rendered roots remain an error.

## Native scoped CSS

A component may place one `<style scoped>` block after `</template>`. PAM
compiles this native-safe CSS subset into typed element properties when it
compiles the component. No CSS parser, WebView, selector engine, or style
recalculation exists in the application runtime.

Shared tokens, typography, tag defaults, and reusable classes belong in the
conventional application stylesheet `src/app.css`. PAM prepends it to every
component automatically:

```css
/* src/app.css */
:root {
    --ink-muted: #5C5C55;
}

@font-face {
    font-family: "Space Grotesk";
    src: url("asset://assets/fonts/SpaceGrotesk-Regular.ttf");
    font-weight: 400;
}

Text {
    font-family: "Space Grotesk";
}
```

`src/app.css` may itself use relative `@import` statements to split tokens,
fonts, and components into smaller files. Imports are expanded from the CSS
file that declares them when the component is compiled. They may be nested,
must stay inside the nearest Composer project, and participate in cache
invalidation. No stylesheet or selector engine is shipped in the app.

```php
<template>
    <Column class="profile">
        <Text class="profile-name">{{ $name }}</Text>
        <Text p-if="$bio !== ''" class="profile-bio">{{ $bio }}</Text>
    </Column>
</template>

<style scoped>
    .profile {
        padding: 0 16px;
        margin-top: 9px;
    }

    .profile-name {
        font-weight: 700;
        font-size: 15px;
    }

    .profile-bio {
        margin-top: 1px;
        color: var(--ink-muted);
        font-weight: 400;
        font-size: 13.5px;
        line-height: 18px;
    }
</style>
```

Here `--ink-muted`, the font faces, and the default `Text` family are declared
once in `src/app.css`. The local component sheet stays focused on its semantic
layout and overrides global rules when necessary.

The cascade is deterministic and follows useful CSS specificity: tag rules are
the base, matching class rules win over tags and follow stylesheet source
order, and an authored PAM attribute wins last. Reordering `class="card
selected"` to `class="selected card"` never changes the result. Both `class`
and `:class` participate. Component styles are isolated and cannot leak into a
child component's template.

Text color, font family/size/weight/style, letter spacing, line height,
alignment, and case inherit through `Column`, `Row`, and other native layout
containers, including across nested `.pam.php` component templates. Inherited
CSS travels as private render context and never becomes a constructor prop or
component variant; typed components therefore receive only attributes authored
on their tag. A child that changes only `font-weight` keeps the inherited
logical family and resolves the matching packaged `@font-face`.
Before the first native mount, the Rust layout engine reads each selected TTF
or OTF face directly from the extracted application assets and caches its
normalized glyph advances. Intrinsic widths, wrapping, accessibility font
scaling, and flex centering therefore use the metrics of the font Android and
iOS actually render. PAM retains a half-point subpixel guard around the ideal
advance sum because platform hinting can round a native text run fractionally
wider; this keeps the native widget's wrapping decision consistent with the
height reserved by the engine. A missing or unsupported face falls back to PAM's
allocation-free generic estimator; layout never waits for a native measurement
round trip and does not visibly correct itself after mount.
Android converts retained logical frames to physical pixels by rounding their
absolute start and end edges, then deriving width and height from those edges.
This preserves a shared physical center for icon-and-text controls at
fractional densities and guarantees that adjacent flex siblings meet on the
same rounded boundary.
`SafeAreaView` resolves stable Android system-bar and display-cutout insets
against the actual screen-space bounds of each safe-area view. A classic
decor-fitted view receives no duplicate padding, an edge-to-edge view receives
the full overlapping inset, and nested, mixed or bottom-bar layouts receive
only the edges they physically overlap. Transient gesture/button bars and
rotation therefore do not move interactive content underneath system chrome.
Absolute descendants hosted through layout-only rows or columns use the
materialized safe-area parent's measured content box after native padding is
applied. Their authored size and bottom/right offsets therefore remain intact
above persistent app bars on gesture and three-button Android navigation.
Conservative auto-width text frames follow the relevant flex alignment axis:
`align-items`/`align-self` in columns and `justify-content` in rows. Explicitly
sized or growing text keeps normal start alignment unless `text-align` is
authored. `text-align` accepts the familiar CSS values `left`, `center`, and
`right`, plus the logical aliases `start` and `end`.

`Input keyboardType` accepts the concise PAM values `text`, `email`, `number`,
`phone`, `decimal`, and `url`. React Native-compatible aliases are also valid:
`default`, `email-address`, `number-pad`, `numeric`, `phone-pad`,
`decimal-pad`, `ascii-capable`, `ascii-capable-number-pad`,
`numbers-and-punctuation`, `name-phone-pad`, `twitter`, `web-search`, and
`visible-password`.
On Android, `autoFocus="true"` focuses inputs added by a reactive render and
explicitly requests the software keyboard once the view and window are ready.
Retained inputs under conditionally visible ancestors retry autofocus when that
ancestor becomes visible, including subtrees controlled by `p-if`.
Set `showSoftInputOnFocus="false"` when focus is required without opening the
keyboard.

`pam-native-format` removes an empty `<style scoped>` block automatically, so
components that rely only on `src/app.css` keep no placeholder markup.

Template expressions support safe numeric arithmetic with conventional
precedence: `+`, `-`, `*`, `/`, integer `%`, and parentheses. For example,
`:height="72 + $bottomSpacing"` stays typed and is evaluated without `eval`.
PHP `.` concatenation accepts scalar, null, and `Stringable` operands, so
`'@'.$username` is valid without allowing array-to-string warnings. PHP `??`
returns the first present non-null value and safely handles missing nested
array or property paths. Templates may compose the pure helpers `trim`,
`ltrim`, `rtrim`, `strlen`, `mb_strlen`, `substr`, `mb_substr`, `strtolower`,
`strtoupper`, `mb_strtolower`, `mb_strtoupper`, `count`, and `in_array`.
Every other bare PHP function remains unavailable; application behavior stays
in public component methods and expressions never use `eval`.

Supported CSS covers PAM's common native layout and paint contracts:

- dimensions, min/max dimensions, `aspect-ratio`, position edges and `z-index`;
- `flex`, growth/shrink/direction, native `flex-wrap`, `gap`, independent
  `column-gap`/`row-gap`, alignment and justification;
- padding and margin edges, standard one-to-four-value shorthands, and
  `padding-inline/block` plus `margin-inline/block`;
- `inset`, `inset-inline/block`, individual position edges, `transform`
  (`translateX/Y`, `scale/X/Y`, and `rotate`), and compositor
  `translation-x/y`;
- border width/color/radii/style plus `border` and directional
  `border-top/right/bottom/left` `<width> solid <color>` shorthands, including
  `border: none`;
- background colors (including `background: none`), text color, percentage or
  numeric opacity, `box-shadow`, elevation, overflow and visibility;
- font family stacks, size/weight/style, letter/line spacing, text alignment,
  decoration and case;
- `object-fit`, `box-sizing: border-box`, and `aspect-ratio: 16 / 9`.

Plain numbers and `px`, `dp`, or `pt` all represent PAM logical points. `rem`
uses a stable native root of 16 logical points.
Percentages are supported for width, height, max-width, and max-height.
Intrinsic flex measurement applies `min-width` and `min-height` before sizing
and placing each line, including `flex-wrap` rows, so auto-sized controls keep
the same logical minimum on every device density.
`align-items: baseline` and `align-self: baseline` are supported in templates
and scoped CSS, along with typed `Align::Baseline` and the `items-baseline`
utility. Horizontal flex rows align text and input baselines; wrapped rows
resolve the baseline independently per line. On a vertical flex axis,
`baseline` follows the cross-axis start edge as defined by the native fallback.
They are also supported for absolute `left`, `top`, `right`, and `bottom`
offsets. `border-radius` accepts the standard one-to-four circular-radius
shorthand; elliptical slash syntax is intentionally rejected.
An absolutely positioned child with both insets on an axis left as `auto`
keeps its CSS flex static position: the parent's `justify-content` controls
the main axis and `align-items` or the child's `align-self` controls the cross
axis. This centers tab indicators and layered logos without calculating
device-specific offsets.
Directional border colors are accepted for familiar CSS authoring; Android
and iOS currently paint one shared native border color, so the last authored
directional color applies to every edge.
`border-style` accepts `solid`, `dashed`, and `dotted`. Patterned styles apply
to uniform borders and preserve the authored width, color, and corner radius;
directional per-edge widths continue to use solid native edge painting.
`box-shadow` accepts one native shadow in standard CSS order:
`x-offset y-offset [blur-radius] [spread-radius] [color]`, or `none`.
Multiple comma-separated shadows and inset shadows are rejected explicitly.
`StatusBar` templates accept both PAM's `color`/`appearance` names and the
familiar `backgroundColor`/`barStyle` aliases, including `animated` and
`translucent`.
Android resolves those properties from the active retained stack route; hidden
routes no longer override the system UI of the visible screen.
On Android 15 and newer, the active `StatusBar` color also remains authoritative
when it differs from the root view background and when either value changes in
the same render commit. PAM paints that color over exactly the status-bar safe
inset in the edge-to-edge host; the screen root cannot bleed through the system
icon surface, and application content below the inset remains untouched.
`appearance="dark"` requests dark system icons on every supported Android
version, including Android 15 and newer where edge-to-edge is enforced by the
platform. Use it over a light status-bar background; `appearance="light"`
keeps light icons for dark backgrounds.
Set `navigationBarHidden="true"` to enter Android immersive navigation mode
without hiding the status bar. System navigation remains transiently available
with an edge swipe, and the bottom safe-area inset updates after the bar hides
so application chrome does not retain an empty system-navigation strip.

Interactive templates accept either `enabled` or the familiar inverse
`disabled` attribute, including bound expressions. Disabled native controls
stop pointer activation and expose the disabled state to platform
accessibility services.

CSS custom properties are declared in `:root`, may reference one another, and
support nested fallbacks such as `var(--brand, var(--fallback, rebeccapurple))`.
Unknown variables without a fallback, cycles, and excessive expansion depth
fail compilation instead of producing a device-only visual error.

Stylesheet colors use web ordering and support the complete CSS named-color
set, `transparent`, `#RGB`, `#RGBA`, `#RRGGBB`, `#RRGGBBAA`, comma or
space-separated `rgb()`/`rgba()`, and `hsl()`/`hsla()` with alpha. They compile
directly to the protocol's ARGB integer:

```css
.glass {
    color: rgb(255 255 255 / 92%);
    background-color: #FFFFFF29;
    border: 1px solid hsl(140deg 45% 35% / 35%);
}
```

For backward compatibility, an eight-digit color written directly as a PAM
attribute remains `#AARRGGBB`. Use a stylesheet for CSS `#RRGGBBAA`; direct
attributes also accept named colors, `transparent`, `#RGB`, `#RGBA`, and CSS
color functions.
`:root` is reserved for component-local `--custom-properties`. Selectors are a
native tag, `.class`, or a comma-separated combination of those forms.
Packaged fonts use compile-time `@font-face` declarations with one safe
`url(asset://…ttf|otf)` source plus numeric `font-weight` and optional
`font-style`; the selected asset becomes the native `fontFamily` property
before rendering.
All `asset://` URLs are relative to the PAM project root. For example,
`asset://assets/brand.png` and
`url(asset://assets/fonts/Brand-Regular.ttf)` address files under the
project's `assets/` directory; never add the runtime's internal `pam/` bundle
prefix yourself.
Unsupported web-only properties such as `box-shadow`, nested selectors, media
queries, descendant selectors, and unknown variables fail the build instead of
silently producing a different native layout.

Press feedback is native and can combine opacity with a bounded scale while
preserving the element's authored transform:

```xml
<Pressable pressedOpacity="0.9" pressedScale="0.92" on:press="send">
    <AppIcon name="send" />
</Pressable>
```

The equivalent fluent API is
`Pressable::make($content)->pressedOpacity(0.9)->pressedScale(0.92)`.
Decorative descendants such as `Image`, `Text`, and icon components do not
become native touch targets merely because they render ripple styling. Unless
they declare their own event handler, a pointer anywhere inside them continues
to the enclosing `Pressable`.

Rounded clipping uses the platform compositor rather than a browser mask:

```xml
<Pressable class="avatar">
    <Image class="avatar-image" :source="$avatar" />
</Pressable>

<style scoped>
    .avatar,
    .avatar-image {
        width: 38px;
        height: 38px;
    }

    .avatar {
        overflow: hidden;
        border-radius: 19px;
    }
</style>
```

On Android, PAM retains and reuses a native rounded clip path until the bounds
or radii change. On iOS, the same property maps to the view layer's bounds
mask. Removing `overflow: hidden` restores visible overflow dynamically.

Inline properties remain useful for dynamic values and isolated exceptions:

```xml
<Text class="title" :textColor="$selected ? '#1B7A4E' : '#5C5C55'">
    {{ $label }}
</Text>
```

## Modal presentation sizing

On Android, a `StatusBar` rendered inside an active `Modal` configures the
modal dialog window as well as the host activity. Full-screen modal content can
therefore declare its own background color, light/dark icon appearance,
visibility and translucency without inheriting the dialog theme's defaults.

`ModalPresentation::Dialog` preserves the intrinsic width and height authored
on its single content card and centers that card over the native backdrop. Use
an explicit card width (and optional maximum width) plus intrinsic content
height for compact confirmations. Dialog presentation does not turn the card
into a full-screen page. Percentage widths are resolved against the modal
viewport before vertical intrinsic measurement, so wrapped copy and action rows
produce the same compact centered card on Android and iOS. Explicit height,
minimum/maximum constraints and edge-pinned portal content remain authoritative.

`ModalPresentation::FullScreen` continues to fill the available window, while
`ModalPresentation::Sheet` fills the width and uses its selected snap-point
height. Choose the presentation from the intended interaction instead of
compensating with platform-specific inner padding.

```php
Modal::make(
    Column::make(/* title, copy and actions */)->width(320),
    presentation: ModalPresentation::Dialog,
);
```

Android contact reads likewise treat `offset: 0` as the first provider row;
subsequent offsets start exactly at the requested contact. This makes the
default `Contacts::all()` call and explicit paginated reads use the same
zero-based contract on Android and iOS.

## SMS drafts

Use `Sms::isAvailable()` before presenting an invite action when the product
requires an SMS app. `Sms::compose()` opens the platform composer with the
recipients and body already filled; it never sends a message automatically.

```php
use Pam\Native\System\Sms;

Sms::isAvailable(function (bool $available): void {
    if (!$available) {
        return;
    }

    Sms::compose(
        ['+5511999990000'],
        'Vem conversar comigo no Zé Chat!',
    );
});
```

Android uses an `ACTION_SENDTO` intent constrained to the `smsto:` scheme, so
the chooser cannot offer unrelated share targets. iOS presents
`MFMessageComposeViewController`. Applications should provide an unavailable
state because emulators, tablets and devices without a configured messaging
service can legitimately return `false`.

## Declarative scroll views

`ScrollView` accepts one or many direct children in `.pam.php` templates. PAM
builds a native `Row` content container when `horizontal` is true and a native
`Column` otherwise. This preserves the natural size of compact items instead
of stretching a lone child to the viewport and lets `p-for` or conditional
branches render any number of children safely:

```xml
<ScrollView horizontal="true" showsHorizontalScrollIndicator="false">
    <Pressable
        p-for="$story in $stories"
        :key="$story->id"
        width="66"
    >
        <Image :source="$story->avatar" width="66" height="66" />
        <Text>{{ $story->name }}</Text>
    </Pressable>
</ScrollView>
```

The explicit PHP API remains intentionally lower-level:
`Scroll::make($content)` receives exactly one content element. Use a `Row` or
`Column` yourself when authoring an imperative element tree.

Declarative scroll views accept `decelerationRate="normal"` (`0.985`) and
`decelerationRate="fast"` (`0.9`) aliases as well as numeric values from `0`
through `1`. Numeric values outside that range are clamped before encoding.

Observed logical offsets can be restored without controlling the scroll on
every render:

```xml
<ScrollView
    anchorToEnd="true"
    :scrollTargetOffset="$savedOffset"
    scrollTargetAlignment="center"
    :scrollRequest="$restoreGeneration"
>
    <!-- timeline -->
</ScrollView>
```

`scrollRequest` is tokenized and runs only when its value changes. A non-empty
`scrollTargetTestId` has priority, a non-negative `scrollTargetOffset` restores
that logical offset, and omitting both targets preserves the scroll-to-end
behavior. The same request and target properties work on `VirtualizedList`,
`VirtualGrid`, and `SectionList`; virtual lists resolve offscreen targets from
their retained layout frames before asking the platform list to scroll.
`scrollTargetAlignment` accepts `start` (default), `center`, or `end` and aligns
the identified descendant within the visible viewport on both axes and both
platforms. Edge targets are clamped to the available scroll range.

File and media picker cancellation is a normal control-flow result. `Files::pick()`
invokes its callback with `null`, while `Files::pickMany()` invokes its callback
with an empty list. Platform back/cancel actions do not throw native-module
exceptions; actual picker/import failures still do.
Camera cancellation follows the same contract and invokes the
`MediaCapture::capture()` callback with `null`.

## Format PAM components

The Composer package installs an official formatter:

```bash
vendor/bin/pam-native-format src
vendor/bin/pam-native-format --check src
```

It accepts any number of files or directories, recursively discovers
`*.pam.php`, preserves the PHP block for Pint/PHP-CS-Fixer, formats templates
and scoped styles deterministically, and migrates legacy `v-*` directives to
`p-*`. `--check` makes CI fail when a component needs formatting.

## Responsive grid and flex

`Grid` is the explicit rich-content grid and defaults to 12 columns. `Row` and
`Column` remain normal flex containers unless a positive `columns` value is
set. A grid row uses 12 columns by convention, accepts any rich PAM
element as a child, wraps automatically, and measures every row from its
tallest child. It works inside scroll views and reflows when the window,
orientation, split-screen width, or foldable posture changes.

```xml
<Grid gutterX="16" gutterY="16">
    <Column span="12" spanSm="6" spanMd="4">
        <Image :source="$photo->url" aspectRatio="1" />
        <Text>{{ $photo->title }}</Text>
    </Column>

    <ProfileCard
        p-for="$profile in $profiles"
        :key="$profile->id"
        span="12"
        spanSm="6"
        spanLg="3"
    />
</Grid>
```

Every direct `Grid` child is a complete retained PAM subtree, so cells may
contain `Image`, `Pressable`, inputs, custom components, nested flex containers,
or another grid. Use `Grid` when the complete layout should stay mounted.

For large rich datasets, use `VirtualizedList` or `VirtualGrid`. Their cells
are real component trees, but Android materializes only the visible and
prefetched window through `RecyclerView`; recycled cells release their native
views while preserving stable keyed identity and event routing:

On Android, retained virtualized routes automatically remount any released
visible cell when the route becomes visible again. Applications do not need to
change list keys after navigation, theme, or other appearance updates merely
to recover recycled native subtrees.

```php
use Pam\Native\UI\{Column, Image, Pressable, Text, VirtualGrid};

$cells = array_map(
    fn (Photo $photo) => Pressable::make(
        Column::make(
            Image::make($photo->url),
            Text::make($photo->title),
        ),
    )
        ->key((string) $photo->id)
        ->onPress(fn () => $this->open($photo->id)),
    $this->photos,
);

return VirtualGrid::make(2, ...$cells)
    ->estimatedRowHeight(224)
    ->prefetch(6)
    ->onEndReached(fn () => $this->loadNextPage(), 0.35);
```

`estimatedRowHeight` controls prefetch distance and is the fallback for a cell
without an authored main-axis size. An explicit cell `height` (or `width` in a
horizontal list) remains authoritative, so one recycled list can render
different media aspect ratios without measuring them on the UI thread.
`rowHeight` remains a compatibility alias.

The native list viewport and every recycled holder are paint boundaries.
Scrolling media cannot draw over screen headers, adjacent rows, or tab bars;
`overflow` remains available to control clipping inside the cell itself.
Layout-only updates also recover an empty recycled holder with a complete cell
bind, so changing `rowHeight`, prefetch, or clipping during the first layout
cannot leave visible rich cells blank. When a retained Android window returns
from the background, PAM also remounts any visible holder whose native subtree
was released; applications do not need to mutate list data or force a scroll.
The recovery attempt is bounded per holder and item identity, so intentionally
empty conditional rows do not keep the renderer active after layout settles
while released populated cells can still recover.

`FlatList` remains source-compatible for lightweight string arrays.

The responsive values are mobile-first. A missing value inherits the closest
smaller breakpoint:

| Suffix | Active from |
| --- | ---: |
| base | `0dp` |
| `Sm` | `600dp` |
| `Md` | `840dp` |
| `Lg` | `1200dp` |
| `Xl` | `1600dp` |

Grid properties:

- `columns`: activates grid layout and defines its track count;
- `span`, `spanSm`, `spanMd`, `spanLg`, `spanXl`: occupied columns;
- `offset`, and its responsive variants: empty columns before the item;
- `order`, and its responsive variants: visual order without changing data;
- `gutterX` and `gutterY`: independent horizontal and vertical spacing;
- `gap`: fallback used when a directional gutter is omitted.

The same API is available as utility classes:

```xml
<Row class="grid-12 gutter-x-4 gutter-y-4">
    <Column class="col-12 col-sm-6 col-md-4">...</Column>
    <Column class="col-6 col-lg-3 offset-lg-1 order-md-2">...</Column>
</Row>
```

Use regular flex whenever tracks are unnecessary:

```xml
<Row class="items-center gap-3">
    <Column flexGrow="1">...</Column>
    <Button @press="save">Save</Button>
</Row>
```

The tree API exposes the identical features through `Style`:

```php
$grid = Row::make(...$cards)->style(new Style(
    gridColumns: 12,
    gridColumnGap: 16,
    gridRowGap: 16,
));

$card = Column::make(...)->style(new Style(
    gridSpan: 12,
    gridSpanSm: 6,
    gridSpanLg: 3,
));
```

## Lifecycle

Override only the hooks a component needs:

```php
public function boot(): void {}
public function mount(): void {}
public function rendered(): void {}
public function attached(): void {}
public function resumed(): void {}
public function updated(string $property): void {}
public function paused(): void {}
public function unmount(): void {}
```

`boot` runs once per instance. `mount` runs on its first render, `rendered`
runs after each render pass, and `attached` runs after the first native commit.
App state drives `resumed` and `paused`. Stable constructor prop changes invoke
`updated`; removing a component invokes `unmount`.

`#[State]` marks local reactive state for tooling. Existing `Restorable`
components continue to control process recreation persistence. The marker does
not introduce proxies or a second state store: normal PHP properties remain
the source of truth.

## Keep the tree API

The tag convention is optional. A project, package, screen, or individual
component can always override `render()` and return a normal element tree:

```php
final class Checkout extends Component
{
    public function render(): Element
    {
        return Screen::make(
            Column::make(
                Text::make('Checkout'),
                Button::make('Pay')->onPress($this->pay(...)),
            ),
        );
    }
}
```

Tree components and `.pam` components can render each other because both
use the same component lifecycle, element identities, Rust diff engine, binary
protocol, and Android UI-thread commit queue.

## Media progress

Declarative `MediaPlayer` progress handlers receive the same typed arguments as
the programmatic `MediaPlayer::onProgress()` API:

```xml
<MediaPlayer source="$video" on:mediaProgress="trackProgress" />
```

On Android, `http://` and `https://` sources are opened as network media URLs.
Provider and local sources keep their context-aware handling through
`content://`, `file://`, and `android.resource://` URIs.

`autoPlay` is reactive: changing it to `true` starts a prepared player and
changing it to `false` pauses playback without resetting the current position.
This makes a bound boolean suitable for custom play/pause controls.

Set `thumbnail` to avoid an empty surface while a video prepares. The Android
renderer keeps the poster in front of the video surface until the first frame
is actually rendered:

```xml
<MediaPlayer source="$video" thumbnail="$poster" autoPlay />
```

```php
public function trackProgress(float $currentTime, float $duration): void
{
    $this->progress = $duration > 0.0 ? $currentTime / $duration : 0.0;
}
```

The template runtime decodes the native wire map before invoking the handler.
A single-parameter handler remains available for applications that intentionally
consume the raw event payload.

## Native drawing canvas

`DrawingCanvas` layers a native freehand surface over an aspect-fit image:

```xml
<DrawingCanvas
    class="editor-canvas"
    :source="$previewSource"
    :value="$drawing"
    brushColor="#FF40C463"
    brushWidth="6"
    drawingMode="brush"
    :undoRequest="$undoRequest"
    :clearRequest="$clearRequest"
    on:change="updateDrawing"
/>
```

`drawingMode` accepts `brush` or `eraser`. `undoRequest` and `clearRequest`
are monotonically increasing command tokens. The native surface coalesces
touch samples and redraws locally during the gesture, then emits `change` once
when the stroke finishes. Its versioned JSON document is bounded to 256
strokes and 2,048 points per stroke; coordinates and widths are normalized to
the displayed image content so preview and export remain aligned at different
screen or image sizes.

Pass the emitted string to `System\ImageEditor::render(drawing: $drawing)` to
flatten it before crop, rotation, filters, resize, and JPEG encoding on the
native worker.

`ImageEditor::render()` also accepts `textLayers`, a bounded list of positioned
text maps for social-media compositions. Coordinates are normalized from `0`
to `1`, rotation uses radians, scale is clamped from `0.25` to `4`, and
`style_type` uses `ImageTextLayerStyle` (`1` plain, `2` filled card, `3`
translucent card):

```php
ImageEditor::render(
    source: $source,
    cropRatio: ImageCropRatio::Story,
    filter: ImageFilterType::Original,
    quarterTurns: 0,
    flipHorizontal: false,
    overlayText: '',
    callback: $done,
    textLayers: [[
        'color' => '#101318',
        'rotation' => 0.0,
        'scale' => 1.0,
        'style_type' => 2,
        'text' => 'Resposta',
        'x' => 0.5,
        'y' => 0.4,
    ]],
);
```

## Generators

```bash
pam make:screen Orders
pam make:component MetricCard
```

These commands generate `src/Screens/Orders.pam` and
`src/Components/MetricCard.pam` without overwriting existing files. Legacy
`.pam.php` components remain supported throughout the 1.x compatibility line.
`pam init --template mobile` still starts with the explicit PHP tree so the
lowest-level model is always visible and available.

## Switch sizing

On Android, the layout engine and native view give a `Switch` without explicit
dimensions a React Native-compatible intrinsic footprint of `46.5 × 27` logical
pixels. Explicit `width` or `height` still wins, and a parent `max-width` or
`max-height` constraint is respected.
Track and thumb colors remain configurable through `trackColorFalse`,
`trackColorTrue`, and `thumbColor`.
