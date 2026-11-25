<?php

declare(strict_types=1);

use Pam\Native\Component;
use Pam\Native\Element;
use Pam\Native\Internal\DependencyTracker;
use Pam\Native\Navigation\Navigator;
use Pam\Native\NodeKind;
use Pam\Native\PropKey;
use Pam\Native\Renderable;
use Pam\Native\UI\BottomSheet;
use Pam\Native\UI\Screen;
use Pam\Native\UI\Text;

$assert = require __DIR__.'/arch_v2_bootstrap.php';

final class NavigationClosingSheetScreen extends Component
{
    public bool $visible = true;
    public string $label = 'initial';
    #[\Pam\Native\Attributes\Transient]
    public int $renders = 0;

    public function render(): Renderable
    {
        ++$this->renders;

        return Screen::make(BottomSheet::make(Text::make($this->label), visible: $this->visible));
    }
}

$sheetVisibility = static function (Element $element) use (&$sheetVisibility): array {
    $values = $element->kind === NodeKind::Modal
        ? [$element->properties[PropKey::Visible->value]]
        : [];
    foreach ($element->children as $child) {
        $values = [...$values, ...$sheetVisibility($child)];
    }

    return $values;
};

$source = new NavigationClosingSheetScreen();
$navigator = new Navigator(
    initialRoute: 'source',
    routes: [
        'source' => static fn () => $source,
        'target' => static fn () => Screen::make(Text::make('target')),
    ],
    handleSystemBack: false,
);
$assert($sheetVisibility($navigator->render()->toElement()) === [true], 'The source sheet starts open.');

// Template handlers mark their scope dirty without advancing the global
// epoch. Closing a sheet and navigating in that same event must commit its
// final state before the outgoing route becomes frozen below the target.
$epoch = DependencyTracker::epoch();
$source->visible = false;
DependencyTracker::markDirty($source);
$navigator->push('target');
$assert(
    $sheetVisibility($navigator->render()->toElement()) === [false],
    'Closing a sheet and pushing in one event must hide the retained native modal.',
);
$assert(DependencyTracker::epoch() === $epoch, 'Closing a sheet before navigation must not invalidate unrelated screens.');

$rendersAfterPush = $source->renders;
$source->label = 'hidden change';
DependencyTracker::markDirty($source);
$navigator->render()->toElement();
$assert($source->renders === $rendersAfterPush, 'The outgoing route stays frozen after its final close state is committed.');

$navigator->pop();
$assert($sheetVisibility($navigator->render()->toElement()) === [false], 'Returning to the source does not reopen its dismissed sheet.');
$assert($source->renders > $rendersAfterPush, 'The source resumes rendering when it becomes active again.');

// Replacing a retained tab parks its sheet below the new route. The close
// still has to be committed before that parked route freezes.
$retained = new NavigationClosingSheetScreen();
$navigator = new Navigator(
    initialRoute: 'source',
    routes: [
        'source' => static fn () => $retained,
        'target' => static fn () => Screen::make(Text::make('target')),
    ],
    handleSystemBack: false,
    keepAliveRoutes: ['source'],
);
$navigator->render()->toElement();
$retained->visible = false;
DependencyTracker::markDirty($retained);
$navigator->replace('target');
$assert($sheetVisibility($navigator->render()->toElement()) === [false], 'Close plus replace hides the departing retained tab sheet.');
$navigator->render()->toElement()->events()[\Pam\Native\EventKind::AnimationComplete->value]('');
$assert($sheetVisibility($navigator->render()->toElement()) === [false], 'The parked tab keeps the closed snapshot after transition completion.');

// Merely covering a route must not dismiss a sheet the app left open.
$open = new NavigationClosingSheetScreen();
$navigator = new Navigator(
    initialRoute: 'source',
    routes: [
        'source' => static fn () => $open,
        'target' => static fn () => Screen::make(Text::make('target')),
    ],
    handleSystemBack: false,
);
$navigator->render()->toElement();
$navigator->push('target');
$assert($sheetVisibility($navigator->render()->toElement()) === [true], 'A sheet intentionally left open is retained while covered.');
$navigator->pop();
$assert($sheetVisibility($navigator->render()->toElement()) === [true], 'Revealing the source preserves an intentionally open sheet.');

// A deeper retained screen must not be refreshed when a different route
// departs. Only the newly blurred route receives the final snapshot.
$deeper = new NavigationClosingSheetScreen();
$navigator = new Navigator(
    initialRoute: 'source',
    routes: [
        'source' => static fn () => $deeper,
        'middle' => static fn () => Screen::make(Text::make('middle')),
        'target' => static fn () => Screen::make(Text::make('target')),
    ],
    handleSystemBack: false,
    keepAliveRoutes: ['source'],
);
$navigator->render()->toElement();
$navigator->push('middle');
$navigator->render()->toElement();
$deeperRenders = $deeper->renders;
$deeper->visible = false;
DependencyTracker::markDirty($deeper);
$navigator->push('target');
$assert($sheetVisibility($navigator->render()->toElement()) === [true], 'Unrelated inactive routes retain their existing frozen snapshot.');
$assert($deeper->renders === $deeperRenders, 'Navigating from another route does not render deeper inactive screens.');

echo "NAVIGATION_SHEET_CLOSE_OK close-push=flushed close-replace=flushed hidden=frozen open=preserved\n";
