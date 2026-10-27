<?php

declare(strict_types=1);

/*
 * Long sessions keep a bounded heap:
 *  - the tree encoder forgets the identities of removed nodes (every route,
 *    keyed row and sheet ever mounted stayed in its identity memo and the
 *    app died with "Allowed memory size ... exhausted (while running
 *    Runtime::flush)" after ~45 min);
 *  - a retained element keeps the subtrees of its newest paths only;
 *  - a mount() that throws releases what it already owned, so retried
 *    renders of an error boundary do not stack listeners.
 */

use Pam\Native\App;
use Pam\Native\Component;
use Pam\Native\Events\Subscription;
use Pam\Native\Internal\RuntimeSupervisor;
use Pam\Native\Internal\TreeEncoder;
use Pam\Native\Navigation\RouteContext;
use Pam\Native\Navigation\Router;
use Pam\Native\Renderable;
use Pam\Native\Routing\Route;
use Pam\Native\UI\Column;
use Pam\Native\UI\Row;
use Pam\Native\UI\Text;

$assert ??= static function (bool $condition, string $message): void {
    if (!$condition) throw new RuntimeException($message);
};

// ---- TreeEncoder identity memo ---------------------------------------------
$screen = static fn (string $key, int $rows): Column => Column::make(
    Text::make('Inbox'),
    Column::make(...array_map(
        static fn (int $row): Row => Row::make(Text::make("{$key} row {$row}"))->key("{$key}-row-{$row}"),
        range(1, $rows),
    ))->key('screen-'.$key),
);
$encoder = new TreeEncoder();
$model = null;
$sizes = [];
for ($frame = 0; $frame < 300; $frame++) {
    // Inbox, then a new route instance (new keys) on top, then back.
    $tree = $frame % 2 === 0
        ? $screen('inbox', 5)
        : Column::make(Text::make('Inbox'), $screen('route-'.$frame, 20));
    $encoded = $encoder->encode($tree);
    $reference = EncodedTreeModel::full((string) (new TreeEncoder())->encode($tree)['frame']);
    if ($encoded['full']) {
        $model = EncodedTreeModel::full((string) $encoded['frame']);
    } elseif ($encoded['frame'] !== null) {
        $model->apply($encoded['frame']);
    }
    $assert($model->root === $reference->root && $model->nodes == $reference->nodes, "Frame {$frame}: patched tree differs after identities were forgotten.");
    $sizes[] = $encoder->identityMemoSizes();
}
$last = $sizes[array_key_last($sizes)];
$assert($last['identities'] <= 60 && $last['paths'] <= 30 && $last['placements'] <= 60, 'Identity memo must follow the live tree: '.json_encode($last));
$assert($sizes[299] === $sizes[99], 'Identity memo must not grow across navigations: '.json_encode([$sizes[99], $sizes[299]]));

// ---- retained element under new route instances ------------------------------
// A remembered screen returns the same element; each push mounts it under a
// new route key. Only the newest paths keep an encoded subtree.
$retained = Column::make(...array_map(static fn (int $row): Row => Row::make(Text::make("bot {$row}"))->key("bot-{$row}"), range(1, 30)))->key('bots');
$encoder = new TreeEncoder();
for ($push = 0; $push < 60; $push++) {
    $encoder->encode(Column::make(Text::make('Inbox'), Column::make($retained)->key('route-'.$push)));
    $encoder->encode(Column::make(Text::make('Inbox')));
}
$cache = (new ReflectionProperty(TreeEncoder::class, 'subtreeCache'))->getValue($encoder);
$assert(count($cache[$retained] ?? []) <= 2, 'A retained element must not keep a subtree per path it was ever shown at: '.count($cache[$retained] ?? []));

// ---- mount() failure releases owned resources --------------------------------
final class MemoryBoundsFailingMount extends Component
{
    public static int $active = 0;

    public function mount(): void
    {
        ++self::$active;
        $this->own(Subscription::from(static function (): void {
            --MemoryBoundsFailingMount::$active;
        }));

        throw new RuntimeException('mount failed');
    }

    public function render(): Renderable
    {
        return Text::make('never');
    }
}

$subscription = App::onError(static function (): void {
});
Route::errorBoundary(static fn (Throwable $error, RouteContext $route): Renderable => Text::make('boundary'));
try {
    $navigator = Router::stack('home')->route('home', static fn (): Renderable => new MemoryBoundsFailingMount())->build();
    for ($render = 0; $render < 5; $render++) {
        $navigator->render()->toElement();
    }
    $assert(MemoryBoundsFailingMount::$active === 0, 'A mount() that throws must release what it owned: '.MemoryBoundsFailingMount::$active.' left.');
} finally {
    Route::errorBoundary(null);
    App::offError($subscription);
    RuntimeSupervisor::reset();
}

fwrite(STDOUT, 'MEMORY_BOUNDS_OK identities='.$last['identities']."\n");
