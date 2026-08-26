# Visual DOM

PAM Native's Visual DOM is a typed retained-tree API for manipulating real
Android and iOS UI from PHP. It is deliberately not a browser emulator: it
adds element lookup, structural mutations, events and measurements to the
existing PAM Native element tree while navigation, storage and networking stay
in their existing subsystems.

## Start here

```php
<?php

use Pam\Native\App;
use Pam\Native\Dom\Document;
use Pam\Native\UI\Button;
use Pam\Native\UI\Text;
use Pam\Native\UI\View;

$document = App::document(
    View::make(
        Text::make('Loki')->id('title')->class('heading'),
        Button::make('Play')->id('play')->class('primary')->data('media-id', '42'),
    )->id('screen')->class('detail-screen'),
);

$document->id('play')->on(\Pam\Native\EventKind::Press, function () use ($document): void {
    $document->transaction(function (Document $dom): void {
        $dom->id('title')->text('Playing');
        $dom->all('.primary')->addClass('active')->style('opacity', 0.92);
    });
});

App::run($document);
```

`transaction()` coalesces all changes into one render request and one native
mutation batch. If its callback throws, PAM restores the exact immutable tree
and selector indexes before rethrowing the error.

## Declarative syntax

Normal template attributes populate the same document metadata:

```xml
<View id="movie" class="card featured" data-media-id="42">
    <Text id="movie-title">{{ $movie->title }}</Text>
    <Button class="action" @press="play">Play</Button>
</View>
```

`class` continues to drive the native CSS compiler and also becomes queryable.
`id` maps to the platform test identifier, so a single stable name works for
DOM lookup and UI automation. `data-*` is bounded PHP metadata and adds no
platform reflection overhead.

## Query API

```php
$title = $document->getElementById('movie-title'); // nullable
$title = $document->id('movie-title');             // throws if absent
$play = $document->querySelector('#movie > .action');
$cards = $document->querySelectorAll('View.card[data-media-id="42"]');

$play?->parent();
$play?->children();
$play?->previousSibling();
$play?->nextSibling();
$play?->closest('.card');
$play?->matches('.action');
$play?->contains($title);
```

Supported selectors are intentionally bounded and indexable: element type,
`#id`, `.class`, `[data-name]`, `[data-name="value"]`, descendant and direct
child (`>`) combinators. Unsupported browser selectors are rejected instead of
silently becoming an expensive scan. Compiled selectors are cached; right-most
ids, classes and types use document indexes.

## Mutations

```php
$card = $document->id('movie');
$card->append(Text::make('New'));
$card->prepend(Text::make('First'));
$card->replaceChildren(Text::make('Empty'));
$card->before(View::make());
$card->after(View::make());
$card->replaceWith(View::make());
$card->remove();

$card->classList()->add('selected');
$card->classList()->remove('loading');
$card->classList()->toggle('expanded');
$card->data('state', 'ready')->removeData('stale');
$card->text('Updated');
$card->style()->set('opacity', 0.8);
$card->style()->set(\Pam\Native\PropKey::Width, 320.0);
```

Handles keep a stable document identity. Calling a read or mutation method on
a detached handle fails clearly; `connected()` supports optional work. Inserted
subtrees receive fresh identities, preventing accidental aliasing.

## Events, animation and observation

```php
$button
    ->on(\Pam\Native\EventKind::Press, $play)
    ->animate(\Pam\Native\MotionPreset::ScaleIn, 180)
    ->pauseAnimation()
    ->resumeAnimation();

$subscription = $document->observe(
    fn (\Pam\Native\Dom\MutationRecord $record) => logChange($record->version),
    '.card',
);

$button->observeResize(function (array $frame) use ($button): void {
    inspect($button->measure()); // x, y, width, height from the UI thread
});
$button->observeIntersection($visibilityChanged);
$subscription->disconnect();
```

`measure()` returns the latest native resize observation, so it never blocks
PHP with a synchronous bridge round-trip. Focus uses the existing native
autofocus property; blur delegates to the existing keyboard authority. Resize,
intersection and animation frames remain native-driven.

## Performance contract

- DOM identities feed `TreeEncoder`, preserving mounted native views when
  siblings move or are inserted.
- Elements remain immutable; only changed ancestor paths are cloned.
- A transaction schedules one render and emits one coalesced mutation record.
- The existing binary protocol, Rust diff/layout engine and bounded Android/iOS
  UI-thread mutation queues remain the only rendering pipeline.
- No selector evaluation, PHP callback or bridge message runs per animation
  frame.
- Limits are explicit: 100,000 native nodes, 512-byte selectors, 16 selector
  compounds, 128-byte ids/classes and 4 KiB data values.

Use native virtualized lists for feeds instead of materializing thousands of
off-screen DOM children. Visual DOM is a precision tool, not a replacement for
component state, signals or recycler-backed lists.
