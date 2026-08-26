<?php

declare(strict_types=1);

use Pam\Native\App;
use Pam\Native\Dom\Document;
use Pam\Native\Dom\MutationRecord;
use Pam\Native\MotionPreset;
use Pam\Native\PropKey;
use Pam\Native\Internal\TemplateCompiler;
use Pam\Native\Internal\TemplateRenderer;
use Pam\Native\Internal\TreeEncoder;
use Pam\Native\TemplateRegistry;
use Pam\Native\UI\Button;
use Pam\Native\UI\Text;
use Pam\Native\UI\View;

/** @var Closure(bool, string): void $assert */

$document = App::document(
    View::make(
        View::make(
            Text::make('Ada')->id('profile-name')->class('label', 'primary')->data('role', 'title'),
            Button::make('Follow')->class('action')->data('state', 'idle'),
        )->id('profile')->class('card'),
        Text::make('Footer')->class('label'),
    )->id('app')->class('screen'),
);

$assert($document instanceof Document, 'App::document must create a visual document.');
$assert($document->root()->id() === 'app', 'The visual document must expose its root.');
$assert($document->id('profile-name')->closest('.card')?->id() === 'profile', 'closest() must traverse visual ancestors.');
$otherDocument = Document::from(View::make(Text::make('Other')->id('other')));
$assert(!$document->root()->contains($otherDocument->id('other')), 'contains() must not alias handles from another document.');
$assert($document->querySelector('View.card > Text.primary')?->id() === 'profile-name', 'Child selectors must match the right-most result.');
$assert($document->querySelector('.card Button.action[data-state="idle"]') !== null, 'Compound descendant selectors must match data attributes.');
$assert(count($document->all('.label')) === 2, 'Class indexes must return every matching element.');
$assert($document->snapshot()->nodeCount === 5, 'DevTools snapshots must expose bounded document metrics.');
$assert(count($document->all('.label')->filter(static fn ($element, $index): bool => $index === 0)) === 1, 'Collections must support typed filtering.');

$observed = [];
$observer = $document->observe(static function (MutationRecord $record) use (&$observed): void {
    $observed[] = $record;
});
$document->transaction(static function (Document $dom): void {
    $dom->all('.label')->addClass('visible')->style('opacity', 0.8);
    $dom->id('profile-name')->text('Grace')->data('role', 'heading');
    $dom->id('profile')->append(Text::make('New')->id('new-child')->class('label'));
});
$assert(count($observed) === 1 && $observed[0]->version === 1, 'A transaction must emit one coalesced mutation record.');

$assert(count($document->all('.visible')) === 2, 'Collection mutations must be applied atomically.');
$assert($document->id('profile-name')->dataset()['role'] === 'heading', 'Dataset mutations must update selector indexes.');
$assert($document->id('profile-name')->matches('[data-role="heading"]'), 'Mutated data must be queryable immediately.');
$assert($document->id('new-child')->parent()?->id() === 'profile', 'append() must preserve parent relationships.');
$assert($document->id('profile-name')->style()->get(PropKey::Opacity) === 0.8, 'Typed styles must remain readable.');

$document->id('new-child')->before(Text::make('Before')->id('before-child'));
$document->id('new-child')->after(Text::make('After')->id('after-child'));
$assert($document->id('before-child')->nextSibling()?->id() === 'new-child', 'before() must preserve sibling order.');
$assert($document->id('after-child')->previousSibling()?->id() === 'new-child', 'after() must preserve sibling order.');

$document->id('new-child')->classList()->toggle('selected');
$document->id('new-child')->classList()->replace('selected', 'active');
$assert($document->id('new-child')->matches('.active'), 'classList mutations must update class indexes.');
$document->id('new-child')->animate(MotionPreset::FadeIn)->pauseAnimation()->resumeAnimation();
$document->id('new-child')->observeResize(static function (array $measurement): void {})->observeIntersection(static function (mixed $entry): void {});
$assert($document->id('new-child')->measure() === null, 'measure() must avoid a synchronous bridge round-trip before native layout reports.');

$snapshot = $document->toElement();
try {
    $document->transaction(static function (Document $dom): void {
        $dom->id('profile')->append(Text::make('Rollback')->id('rollback'));
        throw new RuntimeException('rollback fixture');
    });
} catch (RuntimeException $error) {
    $assert($error->getMessage() === 'rollback fixture', 'Transactions must rethrow their original error.');
}
$assert($document->querySelector('#rollback') === null && $document->toElement() === $snapshot, 'Failed transactions must restore the exact immutable tree.');

$imported = Document::from($document->toElement());
$imported->root()->append(Text::make('Imported')->id('imported-child'));
$assert($imported->id('imported-child')->connected(), 'Imported documents must allocate a collision-free identity namespace.');

$invalidSelectorRejected = false;
try {
    $document->all('#before-child, #after-child');
} catch (InvalidArgumentException) {
    $invalidSelectorRejected = true;
}
$assert($invalidSelectorRejected, 'Unsupported selector syntax must fail loudly.');
$observer->disconnect();

TemplateRegistry::styleResolver(static fn (string $class): array => []);
$templateElement = TemplateRenderer::render(
    TemplateCompiler::compile('<View id="template-root" class="shell featured" data-screen="home"><Text id="headline">Hello</Text></View>'),
    null,
    [],
);
TemplateRegistry::reset();
$templateDocument = Document::from($templateElement);
$assert(
    $templateDocument->id('template-root')->matches('.shell.featured[data-screen="home"]')
        && $templateDocument->id('headline')->parent()?->id() === 'template-root',
    'Declarative id, class, and data-* attributes must feed the visual DOM.',
);

$encoder = new TreeEncoder();
$encoder->encode($document->toElement());
$document->id('profile')->prepend(Text::make('Stable')->id('stable-child'));
$domPatch = $encoder->encode($document->toElement());
$assert(
    $domPatch['frame'] !== null && str_starts_with($domPatch['frame'], 'PNP1'),
    'Visual DOM mutations must cross the existing Rust/native pipeline as incremental patches.',
);
