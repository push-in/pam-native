<?php

declare(strict_types=1);

use Pam\Native\Dom\Document;
use Pam\Native\UI\Text;
use Pam\Native\UI\View;

spl_autoload_register(static function (string $class): void {
    $prefix = 'Pam\\Native\\';
    if (str_starts_with($class, $prefix)) {
        $path = __DIR__.'/../src/'.str_replace('\\', '/', substr($class, strlen($prefix))).'.php';
        if (is_file($path)) {
            require $path;
        }
    }
});

$rows = [];
for ($index = 0; $index < 5_000; $index++) {
    $rows[] = Text::make("Row {$index}")
        ->id("row-{$index}")
        ->class('row', $index % 2 === 0 ? 'even' : 'odd')
        ->data('index', (string) $index);
}

$started = hrtime(true);
$document = Document::from(View::make(...$rows)->id('benchmark'));
$indexMs = (hrtime(true) - $started) / 1_000_000;

$started = hrtime(true);
for ($iteration = 0; $iteration < 2_000; $iteration++) {
    $document->querySelector('#row-'.($iteration % 5_000));
    $document->querySelector('.odd[data-index="'.(($iteration * 2 + 1) % 5_000).'"]');
}
$queryMs = (hrtime(true) - $started) / 1_000_000;

$started = hrtime(true);
$document->transaction(static function (Document $dom): void {
    $dom->all('.even')->addClass('selected')->style('opacity', 0.95);
});
$mutationMs = (hrtime(true) - $started) / 1_000_000;

$indexBudget = (float) (getenv('PAM_DOM_INDEX_MS') ?: 500);
$queryBudget = (float) (getenv('PAM_DOM_QUERY_MS') ?: 500);
$mutationBudget = (float) (getenv('PAM_DOM_MUTATION_MS') ?: 5_000);

fwrite(STDOUT, json_encode([
    'nodes' => 5_001,
    'indexedQueries' => 4_000,
    'batchedMutations' => 5_000,
    'indexMs' => round($indexMs, 3),
    'queryMs' => round($queryMs, 3),
    'mutationMs' => round($mutationMs, 3),
    'peakMemoryMiB' => round(memory_get_peak_usage(true) / 1_048_576, 2),
], JSON_THROW_ON_ERROR).PHP_EOL);

if ($indexMs > $indexBudget || $queryMs > $queryBudget || $mutationMs > $mutationBudget) {
    fwrite(STDERR, 'Visual DOM performance budget exceeded.'.PHP_EOL);
    exit(1);
}
