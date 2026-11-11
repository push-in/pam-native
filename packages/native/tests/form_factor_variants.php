<?php

declare(strict_types=1);

use Pam\Native\EventKind;
use Pam\Native\Internal\PamPhpCompiler;
use Pam\Native\Internal\PamPhpRegistry;
use Pam\Native\Internal\Runtime;
use Pam\Native\Internal\Wire;
use Pam\Native\Testing\Mounted;

use function Pam\Native\Testing\mount;

require_once __DIR__.'/../src/Testing/functions.php';

$assert ??= static function (bool $condition, string $message): void {
    if (!$condition) throw new RuntimeException($message);
};

// `Name.tv.pam.php` is another template for the class of `Name.pam.php`.
$assert(
    PamPhpCompiler::variantOf('/app/src/Home.tv.pam.php') === 'tv'
        && PamPhpCompiler::variantOf('/app/src/Home.tablet.pam') === 'tablet'
        && PamPhpCompiler::variantOf('/app/src/Home.pam.php') === null
        && PamPhpCompiler::variantOf('/app/src/Tv.pam.php') === null,
    'Variant files are named Name.tv.pam.php, Name.phone.pam.php or Name.tablet.pam.php.',
);

$variantCache = sys_get_temp_dir().'/pam-variant-cache-'.getmypid();
PamPhpRegistry::discover(__DIR__.'/Fixtures/Variants', $variantCache);
$variantMetrics = static function (string $device): void {
    Runtime::dispatchEvent(0, EventKind::Dimensions->value, Wire::map([
        'width' => 960.0, 'height' => 540.0, 'density' => 2.0, 'fontScale' => 1.0,
        'deviceType' => $device, 'inputMode' => $device === 'tv' ? 'remote' : 'touch',
    ]));
};
$variantMetrics('phone');
$card = mount(\VariantsApp\VariantCard::class);
$assert($card instanceof Mounted && $card->text() === 'phone Night Shift', 'A phone renders the base template: '.$card->text());
$variantMetrics('tv');
$card->render();
$assert($card->text() === 'tv Night Shift', 'A TV renders the .tv.pam.php variant with the same state: '.$card->text());
$card->unmount();
$variantMetrics('phone');
foreach (glob($variantCache.'/*') ?: [] as $cached) {
    unlink($cached);
}
@rmdir($variantCache);

$orphan = sys_get_temp_dir().'/pam-variant-orphan-'.getmypid();
mkdir($orphan);
file_put_contents($orphan.'/Lonely.tv.pam.php', "<template><Text>x</Text></template>\n");
$orphanError = '';
try {
    PamPhpCompiler::compileDirectory($orphan, $orphan.'/cache');
} catch (RuntimeException $error) {
    $orphanError = $error->getMessage();
}
$assert(str_contains($orphanError, 'Lonely.tv.pam.php needs its component beside it: Lonely.pam.php'), 'A variant without its component is a clear error: '.$orphanError);
foreach (glob($orphan.'/cache/*') ?: [] as $cached) {
    unlink($cached);
}
@rmdir($orphan.'/cache');
unlink($orphan.'/Lonely.tv.pam.php');
rmdir($orphan);
