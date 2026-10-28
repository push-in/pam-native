<?php

declare(strict_types=1);

spl_autoload_register(static function (string $class): void {
    if (!str_starts_with($class, 'RoutesApp\\')) return;
    $path = __DIR__.'/src/'.str_replace('\\', '/', substr($class, strlen('RoutesApp\\'))).'.php';
    if (is_file($path)) require $path;
});
