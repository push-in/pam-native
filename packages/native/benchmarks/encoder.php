<?php

declare(strict_types=1);

use Pam\Native\Internal\TreeEncoder;
use Pam\Native\Component;
use Pam\Native\View;
use Pam\Native\UI\Column;
use Pam\Native\UI\Screen;
use Pam\Native\UI\Text;

require dirname(__DIR__).'/../../examples/showcase/vendor/autoload.php';

const NODE_COUNT = 250;
const ITERATIONS = 2_000;

$children = [];
for ($index = 1; $index < NODE_COUNT; $index++) {
    $children[] = Text::make("Static row {$index}")->key("row-{$index}");
}

$tree = Screen::make(
    Column::make(...$children)->key('content'),
);

$started = hrtime(true);
$bytes = 0;
$encoder = new TreeEncoder();
for ($iteration = 0; $iteration < ITERATIONS; $iteration++) {
    $encoded = $encoder->encode($tree);
    $bytes += strlen($encoded['frame'] ?? '');
}
$elapsed = hrtime(true) - $started;

printf(
    "memoized-noop: nodes=%d iterations=%d total_ms=%.3f ns_per_encode=%d output_bytes=%d\n",
    NODE_COUNT,
    ITERATIONS,
    $elapsed / 1_000_000,
    intdiv($elapsed, ITERATIONS),
    $bytes,
);

$templatePath = sys_get_temp_dir().'/pam-native-benchmark-views';
if (!is_dir($templatePath)) {
    mkdir($templatePath, 0o755, true);
}
file_put_contents(
    $templatePath.'/counter.pam',
    '<Screen><Column class="p-4 gap-2"><Text>Count: {{ $count }}</Text>'
    .'<Button on:press="increment">Increment</Button></Column></Screen>',
);
View::configure($templatePath);
$component = new class extends Component {
    private int $count = 0;

    public function render(): View
    {
        return View::make('counter');
    }

    public function increment(): void
    {
        $this->count++;
    }
};
$component->toElement();
$started = hrtime(true);
for ($iteration = 0; $iteration < ITERATIONS; $iteration++) {
    $component->toElement();
}
$elapsed = hrtime(true) - $started;

printf(
    "cached-tags: nodes=4 iterations=%d total_ms=%.3f ns_per_render=%d\n",
    ITERATIONS,
    $elapsed / 1_000_000,
    intdiv($elapsed, ITERATIONS),
);

$staticRows = [];
for ($index = 2; $index < NODE_COUNT; $index++) {
    $staticRows[] = Text::make("Static row {$index}")->key("static-row-{$index}");
}
$staticContent = Column::make(...$staticRows)->key('static-content');
$encoder = new TreeEncoder();
$started = hrtime(true);
$bytes = 0;
for ($iteration = 0; $iteration < ITERATIONS; $iteration++) {
    $next = Screen::make(
        Text::make($iteration % 2 === 0 ? 'A' : 'B')->key('dynamic-value'),
        $staticContent,
    );
    $encoded = $encoder->encode($next);
    $bytes += strlen($encoded['frame'] ?? '');
}
$elapsed = hrtime(true) - $started;

printf(
    "property-patch: nodes=%d iterations=%d total_ms=%.3f ns_per_encode=%d output_bytes=%d\n",
    NODE_COUNT,
    ITERATIONS,
    $elapsed / 1_000_000,
    intdiv($elapsed, ITERATIONS),
    $bytes,
);
