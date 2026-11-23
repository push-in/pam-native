<?php

declare(strict_types=1);

use Pam\Native\App;
use Pam\Native\Internal\PamPhpCompiler;
use Pam\Native\PropKey;

// An iOS app runs its PHP straight from the signed app bundle, read-only on a
// device: discovery must not create <root>/.pam-native/components there
// (Zé Chat 2.0.0 crashed at launch with "Cannot create PAM component cache").
// With the prebuilt pack nothing is written into the bundle; without it the
// components compile into a writable per-build cache under the app's caches.
$readOnlyBase = sys_get_temp_dir().'/pam-native-readonly-bundle-'.getmypid();
$readOnlyHome = $readOnlyBase.'/home';
$readOnlyState = $readOnlyBase.'/state';
$readOnlyCaches = $readOnlyHome.'/Library/Caches/pam-native/components';
exec('chmod -R u+w '.escapeshellarg($readOnlyBase).' 2>/dev/null; rm -rf '.escapeshellarg($readOnlyBase));
mkdir($readOnlyHome.'/Library/Caches', 0o755, true);
mkdir($readOnlyState, 0o755, true);

// Stages a bundle the way the CLI does (copy, pack, manifest.sha256), then
// takes write permission away from every directory of it.
$stageReadOnlyBundle = static function (string $name, string $class, string $text, bool $pack) use ($readOnlyBase): string {
    $source = $readOnlyBase.'/'.$name.'-source';
    $bundle = $readOnlyBase.'/'.$name;
    mkdir($source.'/src/Components', 0o755, true);
    file_put_contents($source.'/composer.json', "{}\n");
    file_put_contents($source.'/src/Components/'.$class.'.pam.php', <<<PAM
<?php

declare(strict_types=1);

namespace Pam\\Native\\Tests\\ReadOnlyBundle;

use Pam\\Native\\Component;

final class {$class} extends Component
{
    public function __construct(public string \$title = '{$text}')
    {
    }
}
?>

<template>
    <Text>{{ \$title }}</Text>
</template>
PAM);
    if ($pack) {
        PamPhpCompiler::prebuild($source, $source.'/src');
    }
    exec('cp -R '.escapeshellarg($source).' '.escapeshellarg($bundle));
    file_put_contents($bundle.'/manifest.sha256', hash('sha256', $name)."\n");
    exec('find '.escapeshellarg($bundle).' -type d -exec chmod 0555 {} +');

    return $bundle;
};

$previousHome = getenv('HOME');
$previousState = getenv('PAM_NATIVE_STATE_DIR');
putenv('HOME='.$readOnlyHome);
putenv('PAM_NATIVE_STATE_DIR='.$readOnlyState);
// root ignores directory permissions: the bundle would stay writable.
$enforcesPermissions = !function_exists('posix_geteuid') || posix_geteuid() !== 0;
try {
    $packedBundle = $stageReadOnlyBundle('packed', 'PackedCard', 'packed', true);
    App::resetComponentDiscovery();
    $packedDiscovered = App::discoverConventionalComponents($packedBundle);
    $packedCard = App::make('Pam\\Native\\Tests\\ReadOnlyBundle\\PackedCard');
    $assert(
        $packedDiscovered
            && !file_exists($packedBundle.'/.pam-native')
            && !file_exists($readOnlyCaches)
            && $packedCard->toElement()->properties()[PropKey::Text->value] === 'packed',
        'A read-only bundle whose pack serves every component must boot without creating any component cache.',
    );

    if ($enforcesPermissions) {
        $plainBundle = $stageReadOnlyBundle('plain', 'FallbackCard', 'fallback', false);
        mkdir($readOnlyCaches.'/earlier-build', 0o755, true);
        App::resetComponentDiscovery();
        $plainDiscovered = App::discoverConventionalComponents($plainBundle);
        $fallbackCard = App::make('Pam\\Native\\Tests\\ReadOnlyBundle\\FallbackCard');
        $fallbackDirectories = glob($readOnlyCaches.'/*', GLOB_ONLYDIR) ?: [];
        $fallbackComponent = PamPhpCompiler::compileDirectory($plainBundle.'/src', $plainBundle.'/.pam-native/components')[0] ?? null;
        $assert(
            $plainDiscovered
                && !file_exists($plainBundle.'/.pam-native')
                && count($fallbackDirectories) === 1
                && !is_dir($readOnlyCaches.'/earlier-build')
                && $fallbackComponent !== null
                && dirname($fallbackComponent->classFile) === $fallbackDirectories[0]
                && is_file($fallbackComponent->classFile)
                && $fallbackCard->toElement()->properties()[PropKey::Text->value] === 'fallback',
            'Without a pack, a read-only bundle must compile into one stable cache under Library/Caches, dropping earlier builds.',
        );

        // No Library/Caches (Android, Linux): the runtime state directory.
        putenv('HOME='.$readOnlyBase.'/no-home');
        $stateComponent = PamPhpCompiler::compileDirectory($plainBundle.'/src', $plainBundle.'/.pam-native/components')[0] ?? null;
        $assert(
            $stateComponent !== null
                && str_starts_with($stateComponent->classFile, $readOnlyState.'/pam-native/components/')
                && basename(dirname($stateComponent->classFile)) === basename($fallbackDirectories[0])
                && !file_exists($plainBundle.'/.pam-native'),
            'Without Library/Caches, the fallback component cache must live under the runtime state directory.',
        );
    }
} finally {
    putenv($previousHome === false ? 'HOME' : 'HOME='.$previousHome);
    putenv($previousState === false ? 'PAM_NATIVE_STATE_DIR' : 'PAM_NATIVE_STATE_DIR='.$previousState);
    exec('chmod -R u+w '.escapeshellarg($readOnlyBase).'; rm -rf '.escapeshellarg($readOnlyBase));
}

echo 'READONLY_BUNDLE_COMPONENTS_OK '.($enforcesPermissions ? 'pack fallback state' : 'pack')."\n";
