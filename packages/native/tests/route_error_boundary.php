<?php

declare(strict_types=1);

use Pam\Native\App;
use Pam\Native\Component;
use Pam\Native\Diagnostics\RuntimeError;
use Pam\Native\Diagnostics\RuntimeErrorPhase;
use Pam\Native\Element;
use Pam\Native\EventKind;
use Pam\Native\Internal\RuntimeSupervisor;
use Pam\Native\Navigation\Navigator;
use Pam\Native\Navigation\RouteContext;
use Pam\Native\Navigation\Router;
use Pam\Native\PropKey;
use Pam\Native\Renderable;
use Pam\Native\Routing\Route;
use Pam\Native\UI\Text;

$assert ??= static function (bool $condition, string $message): void {
    if (!$condition) throw new RuntimeException($message);
};

final class RouteBoundaryProbe extends Component
{
    /** @var list<string> Labels whose render throws. */
    public static array $failing = [];
    public static bool $failMount = false;
    public static int $renders = 0;

    public function __construct(private readonly string $label = 'probe')
    {
    }

    public function mount(): void
    {
        if (self::$failMount) throw new RuntimeException('mount failed');
    }

    public function render(): Renderable
    {
        self::$renders++;
        if (in_array($this->label, self::$failing, true)) throw new RuntimeException("{$this->label} render failed");

        return Text::make("ok {$this->label}");
    }
}

final class RouteBoundaryFallback extends Component
{
    public static int $created = 0;

    public function __construct(public readonly string $message, public readonly Closure $retry)
    {
        self::$created++;
    }

    public function render(): Renderable
    {
        return Text::make("boundary {$this->message}");
    }
}

/** @return list<string> */
$texts = static function (Element $element) use (&$texts): array {
    $found = [];
    $text = $element->properties()[PropKey::Text->value] ?? null;
    if (is_string($text)) $found[] = $text;
    foreach ($element->children() as $child) {
        if ($child instanceof Element) $found = [...$found, ...$texts($child)];
    }

    return $found;
};
$settle = static function (Navigator $navigator): Element {
    $host = $navigator->render()->toElement();
    ($host->events()[EventKind::AnimationComplete->value])('');

    return $navigator->render()->toElement();
};
$reported = [];
$subscription = App::onError(static function (Throwable $error, RuntimeError $report) use (&$reported): void {
    $reported[] = [$error->getMessage(), $report->phase, $report->fatal()];
});
$retryFor = null;
$contexts = [];
$boundary = static function (Throwable $error, RouteContext $route, Closure $retry) use (&$retryFor, &$contexts): Renderable {
    $retryFor = $retry;
    $contexts[] = $route;

    return new RouteBoundaryFallback($error->getMessage(), $retry);
};

// Route-level boundary: only the failing route is replaced; root keeps working.
$navigator = Router::stack('home')
    ->route('home', static fn (): Renderable => new RouteBoundaryProbe('home'))
    ->route('detail', static fn (RouteContext $route): Renderable => new RouteBoundaryProbe('detail'))
    ->errorBoundary('detail', $boundary)
    ->build();
$settle($navigator);
RouteBoundaryProbe::$failing = ['detail'];
$navigator->push('detail', ['id' => 7]);
$tree = $texts($settle($navigator));
$assert(
    in_array('boundary detail render failed', $tree, true) && !in_array('ok detail', $tree, true),
    'A route boundary must render in place of the failing route: '.json_encode($tree),
);
$assert(
    $contexts !== [] && $contexts[0]->name === 'detail' && $contexts[0]->integer('id') === 7,
    'The boundary receives the failing route context.',
);
$assert(
    $reported !== [] && $reported[0][0] === 'detail render failed'
        && $reported[0][1] === RuntimeErrorPhase::Other && $reported[0][2] === false,
    'A caught route error is still reported once, as non-fatal.',
);
$createdBefore = RouteBoundaryFallback::$created;
$reportedBefore = count($reported);
$settle($navigator);
$assert(
    RouteBoundaryFallback::$created === $createdBefore && count($reported) === $reportedBefore,
    'While failed, the boundary content is reused and the route is not retried or reported again.',
);

// Retry clears the error and renders the route again.
RouteBoundaryProbe::$failing = [];
$assert($retryFor instanceof Closure, 'The boundary receives a retry closure.');
$retryFor();
$tree = $texts($settle($navigator));
$assert(
    in_array('ok detail', $tree, true) && !in_array('boundary detail render failed', $tree, true),
    'retry() must clear the error and re-render the route: '.json_encode($tree),
);

// Without any boundary the error propagates as before.
$plain = Router::stack('home')
    ->route('home', static fn (): Renderable => new RouteBoundaryProbe('plain'))
    ->build();
RouteBoundaryProbe::$failing = ['plain'];
$propagated = false;
try {
    $plain->render()->toElement();
} catch (RuntimeException $error) {
    $propagated = $error->getMessage() === 'plain render failed';
}
RouteBoundaryProbe::$failing = [];
$assert($propagated, 'Without a boundary a route error must propagate unchanged.');

// Global default boundary, mount and factory failures; route-level wins.
$globalCalls = 0;
Route::errorBoundary(static function (Throwable $error, RouteContext $route, Closure $retry) use (&$globalCalls): Renderable {
    $globalCalls++;

    return Text::make("global {$route->name}: {$error->getMessage()}");
});
try {
    RouteBoundaryProbe::$failMount = true;
    $mounting = Router::stack('home')
        ->route('home', static fn (): Renderable => new RouteBoundaryProbe('mounting'))
        ->build();
    $tree = $texts($mounting->render()->toElement());
    RouteBoundaryProbe::$failMount = false;
    $assert(
        in_array('global home: mount failed', $tree, true),
        'Route::errorBoundary() must catch mount() failures of a route: '.json_encode($tree),
    );

    $factory = Router::stack('home')
        ->route('home', static fn (): Renderable => throw new LogicException('factory failed'))
        ->build();
    $tree = $texts($factory->render()->toElement());
    $assert(in_array('global home: factory failed', $tree, true), 'The boundary must catch route creation failures.');

    RouteBoundaryProbe::$failing = ['own', 'declared'];
    $own = Router::stack('home')
        ->route('home', static fn (): Renderable => new RouteBoundaryProbe('own'))
        ->errorBoundary('home', static fn (Throwable $error): Renderable => Text::make('own boundary'))
        ->build();
    $callsBefore = $globalCalls;
    $tree = $texts($own->render()->toElement());
    $assert(
        in_array('own boundary', $tree, true) && $globalCalls === $callsBefore,
        'A route-level boundary wins over the global one.',
    );

    // Route::stack() + PendingRoute::errorBoundary() plumbing.
    $declared = Route::stack('boundaryStack', 'home', static function (): void {
        Route::screen('home', static fn (): Renderable => new RouteBoundaryProbe('declared'))
            ->errorBoundary(static fn (Throwable $error, RouteContext $route): Renderable => Text::make("pending {$route->name}"));
    });
    $tree = $texts($declared->render()->toElement());
    $assert(in_array('pending home', $tree, true), 'PendingRoute::errorBoundary() must reach the navigator.');
    RouteBoundaryProbe::$failing = [];

    $bad = Router::stack('home')
        ->route('home', static fn (): Renderable => throw new LogicException('x'))
        ->errorBoundary('home', static fn (): mixed => 'not renderable')
        ->build();
    $rejected = false;
    try {
        $bad->render()->toElement();
    } catch (LogicException $error) {
        $rejected = str_contains($error->getMessage(), 'must return a Renderable');
    }
    $assert($rejected, 'A boundary that does not return a Renderable is rejected.');
} finally {
    RouteBoundaryProbe::$failing = [];
    RouteBoundaryProbe::$failMount = false;
    Route::errorBoundary(null);
    App::offError($subscription);
    RuntimeSupervisor::reset();
}

$invalid = false;
try {
    Router::stack('home')
        ->route('home', static fn (): Renderable => Text::make('x'))
        ->errorBoundary('missing', $boundary)
        ->build();
} catch (InvalidArgumentException) {
    $invalid = true;
}
$assert($invalid, 'Error boundaries must target registered routes.');
$assert(Route::globalErrorBoundary() === null, 'Route::errorBoundary(null) removes the global boundary.');

echo "ROUTE_ERROR_BOUNDARY_OK\n";
