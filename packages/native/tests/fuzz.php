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

mt_srand(0x50414D);
$encoder = new TreeEncoder();
for ($frame = 0; $frame < 1_000; $frame++) {
    $children = [];
    $count = mt_rand(0, 128);
    for ($index = 0; $index < $count; $index++) {
        $identity = mt_rand(0, 255);
        $children[] = Text::make("F{$frame}:{$identity}")
            ->key("item:{$identity}:{$index}");
    }
    $encoded = $encoder->encode(Column::make(...$children)->key('fuzz-root'));
    if ($encoded['frame'] !== null && !str_starts_with($encoded['frame'], 'PN')) {
        throw new RuntimeException("Invalid encoded frame at iteration {$frame}.");
    }
}

fwrite(STDOUT, "Pam Native deterministic tree fuzz passed (1,000 frames).\n");
