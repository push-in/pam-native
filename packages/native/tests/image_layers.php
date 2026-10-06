<?php

declare(strict_types=1);

use Pam\Native\System\ImageEditor;

if (!class_exists(ImageEditor::class)) {
    spl_autoload_register(static function (string $class): void {
        if (!str_starts_with($class, 'Pam\\Native\\')) return;
        $path = __DIR__.'/../src/'.str_replace('\\', '/', substr($class, strlen('Pam\\Native\\'))).'.php';
        if (is_file($path)) require $path;
    });
}
$imageLayers = new ReflectionMethod(ImageEditor::class, 'imageLayers');
$normalized = json_decode($imageLayers->invoke(null, [['path' => 'stickers/a.gif', 'x' => -1, 'y' => 2, 'width' => 2, 'rotation' => 99]]), true);
if ($normalized !== [['path' => 'stickers/a.gif', 'x' => 0, 'y' => 1, 'width' => 1, 'height' => 0, 'rotation' => M_PI * 2]]) {
    // JSON integer/float representation is intentionally irrelevant.
    if ($normalized != [['path' => 'stickers/a.gif', 'x' => 0, 'y' => 1, 'width' => 1, 'height' => 0, 'rotation' => M_PI * 2]]) {
        throw new RuntimeException('Image layers must normalize bounds and preserve aspect by default.');
    }
}
foreach ([['path' => '../outside.png'], ['path' => '/private/a.png'], ['path' => 'a.png', 'width' => NAN], ['path' => 'a.png', 'height' => INF]] as $bad) {
    try {
        $imageLayers->invoke(null, [$bad]);
        throw new RuntimeException('Unsafe image layer accepted.');
    } catch (InvalidArgumentException) {
    }
}
try {
    $imageLayers->invoke(null, array_fill(0, 81, ['path' => 'a.png']));
    throw new RuntimeException('Unbounded image layer list accepted.');
} catch (InvalidArgumentException) {
}
echo "IMAGE_LAYERS_OK private-paths finite-geometry bounded-list aspect-default\n";
