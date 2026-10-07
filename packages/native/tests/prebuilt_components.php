<?php

declare(strict_types=1);

use Pam\Native\Internal\PamPhpCompiler;
use Pam\Native\Internal\TemplateRenderer;

// Components precompiled into the bundle (build time) are relocatable and
// fingerprint-validated: an installed copy at another path boots without
// compiling; an edited component (hot reload) compiles as before.
$prebuiltRoot = sys_get_temp_dir().'/pam-native-prebuilt-'.getmypid();
$prebuiltCopy = $prebuiltRoot.'-installed';
foreach ([$prebuiltRoot, $prebuiltCopy] as $prebuiltDirectory) {
    if (is_dir($prebuiltDirectory)) {
        exec('rm -rf '.escapeshellarg($prebuiltDirectory));
    }
}
mkdir($prebuiltRoot.'/src/Components', 0o755, true);
file_put_contents($prebuiltRoot.'/composer.json', "{}\n");
file_put_contents($prebuiltRoot.'/src/app.css', "Text { font-size: 13px; }\n");
$prebuiltSource = <<<'PAM'
<?php

declare(strict_types=1);

namespace Pam\Native\Tests\Prebuilt;

use Pam\Native\Component;

final class Badge extends Component
{
    public function __construct(public string $label = 'New', public bool $active = true)
    {
    }
}
?>

<template>
    <View :class="['badge', 'badge-active' => $active]">
        <Text class="badge-label">{{ $label }}</Text>
    </View>
</template>

<style scoped>
    .badge { padding: 4px; }
    .badge-active { background-color: #1B7A4E; }
    .badge-label { color: #FFFFFF; }
</style>
PAM;
file_put_contents($prebuiltRoot.'/src/Components/Badge.pam.php', $prebuiltSource);
$prebuiltCount = PamPhpCompiler::prebuild($prebuiltRoot, $prebuiltRoot.'/src');
$prebuiltFiles = glob($prebuiltRoot.'/'.PamPhpCompiler::PREBUILT_DIRECTORY.'/*') ?: [];
$prebuiltText = implode("\n", array_map(static fn (string $file): string => (string) file_get_contents($file), $prebuiltFiles));
$assert(
    $prebuiltCount === 1
        && $prebuiltFiles === [$prebuiltRoot.'/'.PamPhpCompiler::PREBUILT_DIRECTORY.'/'.PamPhpCompiler::PREBUILT_PACK]
        && str_starts_with($prebuiltText, 'PNC1')
        && !str_contains($prebuiltText, $prebuiltRoot),
    'Prebuilt components must be written as one pack file with project-relative identities.',
);
exec('cp -R '.escapeshellarg($prebuiltRoot).' '.escapeshellarg($prebuiltCopy));
$installedCache = $prebuiltCopy.'/.cache';
$installedComponents = PamPhpCompiler::compileDirectory($prebuiltCopy.'/src', $installedCache);
$installedComponent = $installedComponents[0] ?? null;
$assert(
    $installedComponent !== null
        && str_starts_with($installedComponent->classFile, $prebuiltCopy.'/'.PamPhpCompiler::PREBUILT_DIRECTORY.'/.')
        && !is_file($installedComponent->classFile)
        && (glob($installedCache.'/*') ?: []) === []
        && $installedComponent->template->children[0]->source === $prebuiltCopy.'/src/Components/Badge.pam.php',
    'A relocated bundle must boot from its prebuilt components without compiling them.',
);
$installedElement = TemplateRenderer::render($installedComponent->template, null, ['active' => true, 'label' => 'Ok']);
PamPhpCompiler::materializePrebuilt($installedComponent->classFile);
require $installedComponent->classFile;
$assert(
    class_exists('Pam\\Native\\Tests\\Prebuilt\\Badge', false)
        && count(glob(dirname($installedComponent->classFile).'/*') ?: []) === 2
        && (glob($installedCache.'/*') ?: []) === [],
    'Prebuilt class and template files must be written from the pack on first use only.',
);
$assert(
    $installedElement->domClasses() === ['badge', 'badge-active'],
    'Prebuilt templates must render like freshly compiled ones.',
);
file_put_contents(
    $prebuiltCopy.'/src/Components/Badge.pam.php',
    str_replace('padding: 4px;', 'padding: 6px;', $prebuiltSource),
);
$editedComponents = PamPhpCompiler::compileDirectory($prebuiltCopy.'/src', $installedCache);
$assert(
    str_starts_with($editedComponents[0]->classFile, $installedCache.'/'),
    'An edited component must ignore its stale prebuilt copy and compile into the writable cache.',
);
// A read-only bundle (an iOS app) writes its component files under the
// runtime state directory instead, keeping only the current pack's files.
$readOnlyCopy = $prebuiltRoot.'-readonly';
$readOnlyState = $prebuiltRoot.'-state';
exec('rm -rf '.escapeshellarg($readOnlyCopy).' '.escapeshellarg($readOnlyState));
exec('cp -R '.escapeshellarg($prebuiltRoot).' '.escapeshellarg($readOnlyCopy));
mkdir($readOnlyState.'/prebuilt-components/stale-pack', 0o755, true);
chmod($readOnlyCopy.'/'.PamPhpCompiler::PREBUILT_DIRECTORY, 0o555);
$previousState = getenv('PAM_NATIVE_STATE_DIR');
putenv('PAM_NATIVE_STATE_DIR='.$readOnlyState);
try {
    $readOnlyComponent = PamPhpCompiler::compileDirectory($readOnlyCopy.'/src', $readOnlyCopy.'-cache')[0] ?? null;
    $readOnlyElement = $readOnlyComponent === null ? null
        : TemplateRenderer::render($readOnlyComponent->template, null, ['active' => false, 'label' => 'Ro']);
    $assert(
        $readOnlyComponent !== null
            && str_starts_with($readOnlyComponent->classFile, $readOnlyState.'/prebuilt-components/')
            && !is_dir($readOnlyState.'/prebuilt-components/stale-pack')
            && $readOnlyElement?->domClasses() === ['badge']
            && (glob($readOnlyCopy.'/'.PamPhpCompiler::PREBUILT_DIRECTORY.'/.*', GLOB_ONLYDIR) ?: []) === [
                $readOnlyCopy.'/'.PamPhpCompiler::PREBUILT_DIRECTORY.'/.',
                $readOnlyCopy.'/'.PamPhpCompiler::PREBUILT_DIRECTORY.'/..',
            ],
        'A read-only bundle must write its prebuilt component files under the state directory.',
    );
} finally {
    putenv($previousState === false ? 'PAM_NATIVE_STATE_DIR' : 'PAM_NATIVE_STATE_DIR='.$previousState);
    chmod($readOnlyCopy.'/'.PamPhpCompiler::PREBUILT_DIRECTORY, 0o755);
    exec('rm -rf '.escapeshellarg($readOnlyCopy).' '.escapeshellarg($readOnlyCopy.'-cache').' '.escapeshellarg($readOnlyState));
}
// A staged application bundle (manifest.sha256 at its root, written by the
// CLI with the pack) boots from the pack's own listing of its components:
// no walk of the source tree, no read or fingerprint of each source.
$packIndex = (static function (string $pack): array {
    $bytes = (string) file_get_contents($pack);
    $length = unpack('V', substr($bytes, 4, 4))[1];

    return json_decode(substr($bytes, 8, $length), true, 32, JSON_THROW_ON_ERROR);
})($prebuiltRoot.'/'.PamPhpCompiler::PREBUILT_DIRECTORY.'/'.PamPhpCompiler::PREBUILT_PACK);
$assert(
    ($packIndex['sources'] ?? null) === ['src/Components/Badge.pam.php'],
    'The prebuilt pack must list every component source of the bundle.',
);
$stagedCopy = $prebuiltRoot.'-staged';
exec('rm -rf '.escapeshellarg($stagedCopy));
exec('cp -R '.escapeshellarg($prebuiltRoot).' '.escapeshellarg($stagedCopy));
file_put_contents($stagedCopy.'/manifest.sha256', "staged\n");
chmod($stagedCopy.'/src/Components/Badge.pam.php', 0o000);
try {
    $stagedComponent = PamPhpCompiler::compileDirectory($stagedCopy.'/src', $stagedCopy.'/.cache')[0] ?? null;
    $assert(
        $stagedComponent !== null
            && $stagedComponent->className === 'Pam\\Native\\Tests\\Prebuilt\\Badge'
            && str_starts_with($stagedComponent->classFile, $stagedCopy.'/'.PamPhpCompiler::PREBUILT_DIRECTORY.'/.')
            && (glob($stagedCopy.'/.cache/*') ?: []) === [],
        'A staged bundle must boot from its pack listing without reading its component sources.',
    );
} finally {
    chmod($stagedCopy.'/src/Components/Badge.pam.php', 0o644);
    exec('rm -rf '.escapeshellarg($stagedCopy));
}
foreach ([$prebuiltRoot, $prebuiltCopy] as $prebuiltDirectory) {
    exec('rm -rf '.escapeshellarg($prebuiltDirectory));
}
