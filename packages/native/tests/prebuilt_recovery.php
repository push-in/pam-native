<?php

declare(strict_types=1);

use Pam\Native\Internal\PamPhpCompiler;
use Pam\Native\Internal\PamPhpRegistry;
use Pam\Native\Internal\TemplateRenderer;

if (!class_exists(PamPhpCompiler::class)) {
    spl_autoload_register(static function (string $class): void {
        $prefix = 'Pam\\Native\\';
        if (!str_starts_with($class, $prefix)) return;
        $path = __DIR__.'/../src/'.str_replace('\\', '/', substr($class, strlen($prefix))).'.php';
        if (is_file($path)) require $path;
    });
}

$assertPrebuiltRecovery = static function (bool $condition, string $message): void {
    if (!$condition) throw new RuntimeException($message);
};
$recoveryRoot = sys_get_temp_dir().'/pam-prebuilt-recovery-'.bin2hex(random_bytes(6));
mkdir($recoveryRoot.'/src', 0o755, true);
file_put_contents($recoveryRoot.'/composer.json', "{}\n");
$removeRecoveryTree = static function (string $directory) use (&$removeRecoveryTree): void {
    foreach (new FilesystemIterator($directory, FilesystemIterator::SKIP_DOTS) as $entry) {
        if ($entry->isDir()) $removeRecoveryTree($entry->getPathname());
        else unlink($entry->getPathname());
    }
    rmdir($directory);
};

try {
    foreach (['EmptyClass', 'TruncatedClass', 'EmptyTemplate', 'TruncatedTemplate', 'Valid', 'Retry'] as $name) {
        file_put_contents($recoveryRoot.'/src/'.$name.'.pam.php', '<?php' . "\n"
            .'namespace Pam\\Native\\Tests\\PrebuiltRecovery;' . "\n"
            .'final class '.$name.' extends \\Pam\\Native\\Component {}' . "\n?>\n"
            .'<template><View><Text>'.$name.'</Text></View></template>');
    }
    PamPhpCompiler::prebuild($recoveryRoot, $recoveryRoot.'/src');
    $recoveryComponents = PamPhpCompiler::compileDirectory($recoveryRoot.'/src', $recoveryRoot.'/.cache');
    $recoveryPackPath = $recoveryRoot.'/'.PamPhpCompiler::PREBUILT_DIRECTORY.'/'.PamPhpCompiler::PREBUILT_PACK;
    $recoveryPack = file_get_contents($recoveryPackPath);
    $recoveryIndexLength = unpack('V', substr($recoveryPack, 4, 4))[1];
    $recoveryIndex = json_decode(substr($recoveryPack, 8, $recoveryIndexLength), true, flags: JSON_THROW_ON_ERROR);
    $recoveryExpected = [];
    $recoveryByName = [];
    foreach ($recoveryComponents as $component) {
        $name = substr($component->className, strrpos($component->className, '\\') + 1);
        $recoveryByName[$name] = $component;
        $key = basename($component->classFile, '.class.php');
        foreach (['class', 'template'] as $part) {
            [$offset, $length] = $recoveryIndex['components'][$key][$part];
            $file = dirname($component->classFile).'/'.$key.'.'.$part.'.php';
            $contents = substr($recoveryPack, 8 + $recoveryIndexLength + $offset, $length);
            $recoveryExpected[$file] = $contents;
            if (!is_dir(dirname($file))) mkdir(dirname($file), 0o755, true);
            // Files survive a previous launch/reboot, including the empty PHP
            // class observed on-device. Sources and signed pack remain intact.
            $damaged = match ([$name, $part]) {
                ['EmptyClass', 'class'], ['EmptyTemplate', 'template'] => '',
                ['TruncatedClass', 'class'], ['TruncatedTemplate', 'template'] => substr($contents, 0, 16),
                default => $contents,
            };
            file_put_contents($file, $damaged);
            if ($name === 'EmptyClass' && $part === 'class' && function_exists('opcache_compile_file')) {
                @opcache_compile_file($file);
            }
        }
    }
    $validFile = $recoveryByName['Valid']->classFile;
    touch($validFile, 1_600_000_000);
    clearstatcache(true, $validFile);
    $validStat = stat($validFile);
    PamPhpRegistry::discover($recoveryRoot.'/src', $recoveryRoot.'/.cache');
    foreach ($recoveryByName as $name => $component) {
        if ($name === 'Retry') continue;
        $instance = new ($component->className)();
        $element = TemplateRenderer::render($component->template, $instance, []);
        $assertPrebuiltRecovery($element->children()[0]->properties()[\Pam\Native\PropKey::Text->value] === $name, 'Recovered template must render its original text.');
        foreach (['class', 'template'] as $part) {
            $file = substr($component->classFile, 0, -strlen('.class.php')).'.'.$part.'.php';
            $assertPrebuiltRecovery(file_get_contents($file) === $recoveryExpected[$file], 'Recovered '.$name.' '.$part.' must match the pack byte-for-byte.');
        }
    }
    clearstatcache(true, $validFile);
    $assertPrebuiltRecovery(fileinode($validFile) === $validStat['ino'] && filemtime($validFile) === $validStat['mtime'], 'A valid cached class must be reused without rewriting.');

    $retryFile = $recoveryByName['Retry']->classFile;
    file_put_contents($retryFile, '');
    file_put_contents($recoveryPackPath, substr($recoveryPack, 0, 8 + $recoveryIndexLength));
    $readFailed = false;
    try {
        PamPhpCompiler::materializePrebuilt($retryFile);
    } catch (RuntimeException $error) {
        $readFailed = str_contains($error->getMessage(), 'is truncated');
    }
    $assertPrebuiltRecovery($readFailed && file_get_contents($retryFile) === '', 'A truncated pack must fail explicitly without publishing a partial replacement.');
    file_put_contents($recoveryPackPath, $recoveryPack);
    $retryInstance = new ($recoveryByName['Retry']->className)();
    $assertPrebuiltRecovery(file_get_contents($retryFile) === $recoveryExpected[$retryFile], 'A failed read must preserve pending recovery for the next attempt.');

    // Materialization is consumed once; no size check or pack read per render.
    rename($recoveryPackPath, $recoveryPackPath.'.unavailable');
    foreach ($recoveryByName as $component) PamPhpCompiler::materializePrebuilt($component->classFile);
    $assertPrebuiltRecovery((glob($recoveryRoot.'/.cache/*') ?: []) === [], 'Recovery must use the existing pack without recompiling sources.');
    $assertPrebuiltRecovery((glob(dirname($validFile).'/*.tmp') ?: []) === [], 'Atomic recovery must not leave temporary files.');
} finally {
    $removeRecoveryTree($recoveryRoot);
}

echo "PREBUILT_RECOVERY_OK empty+truncated=class,template valid=reused failed-read=retry no-recompile no-per-render-read\n";
