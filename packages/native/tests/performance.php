<?php

declare(strict_types=1);

use Pam\Native\Internal\TreeEncoder;
use Pam\Native\UI\Column;
use Pam\Native\UI\Text;

spl_autoload_register(static function (string $class): void {
    $prefix = 'Pam\\Native\\';
    if (str_starts_with($class, $prefix)) {
        $path = __DIR__.'/../src/'.str_replace('\\', '/', substr($class, strlen($prefix))).'.php';
        if (is_file($path)) {
            require $path;
        }
    }
});

$children = [];
for ($index = 0; $index < 1_000; $index++) {
    $children[] = Text::make("Row {$index}")->key("row:{$index}");
}
$tree = Column::make(...$children)->key('benchmark');
$encoder = new TreeEncoder();
$started = hrtime(true);
$encoder->encode($tree);
$firstMs = (hrtime(true) - $started) / 1_000_000;

$started = hrtime(true);
for ($iteration = 0; $iteration < 500; $iteration++) {
    $encoder->encode($tree);
}
$steadyMs = (hrtime(true) - $started) / 1_000_000 / 500;

$firstBudget = (float) (getenv('PAM_PERF_FIRST_FRAME_MS') ?: 40);
$steadyBudget = (float) (getenv('PAM_PERF_STEADY_FRAME_MS') ?: 1);
fwrite(STDOUT, json_encode([
    'nodes' => 1_001,
    'firstFrameMs' => round($firstMs, 3),
    'steadyFrameMs' => round($steadyMs, 3),
    'budgets' => ['firstFrameMs' => $firstBudget, 'steadyFrameMs' => $steadyBudget],
], JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES)."\n");

if ($firstMs > $firstBudget || $steadyMs > $steadyBudget) {
    fwrite(STDERR, "Pam Native performance budget exceeded.\n");
    exit(1);
}
