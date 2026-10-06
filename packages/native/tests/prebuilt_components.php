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
        && count(glob($prebuiltRoot.'/'.PamPhpCompiler::PREBUILT_DIRECTORY.'/*.expressions.php') ?: []) === 1
        && !str_contains($prebuiltText, $prebuiltRoot),
    'Prebuilt components must be written with project-relative identities and compiled expressions.',
);
exec('cp -R '.escapeshellarg($prebuiltRoot).' '.escapeshellarg($prebuiltCopy));
$installedCache = $prebuiltCopy.'/.cache';
$installedComponents = PamPhpCompiler::compileDirectory($prebuiltCopy.'/src', $installedCache);
$installedComponent = $installedComponents[0] ?? null;
$assert(
    $installedComponent !== null
        && str_starts_with($installedComponent->classFile, $prebuiltCopy.'/'.PamPhpCompiler::PREBUILT_DIRECTORY.'/')
        && (glob($installedCache.'/*') ?: []) === []
        && $installedComponent->template->children[0]->source === $prebuiltCopy.'/src/Components/Badge.pam.php',
    'A relocated bundle must boot from its prebuilt components without compiling them.',
);
$installedElement = TemplateRenderer::render($installedComponent->template, null, ['active' => true, 'label' => 'Ok']);
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
foreach ([$prebuiltRoot, $prebuiltCopy] as $prebuiltDirectory) {
    exec('rm -rf '.escapeshellarg($prebuiltDirectory));
}
