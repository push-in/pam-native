<?php

declare(strict_types=1);

/*
 * Runs inside PAM's Android/iOS PHP runtime (scripts/android-php-runtime-test.sh
 * pushes the SDK sources and executes this file with the runtime's libphp).
 * The runtime has no ext-mbstring: the SDK bootstrap must define every mb_*
 * function, and each recorded ext-mbstring result must come out unchanged.
 */

spl_autoload_register(static function (string $class): void {
    $prefix = 'Pam\\Native\\';
    if (str_starts_with($class, $prefix)) {
        $path = __DIR__.'/../../src/'.str_replace('\\', '/', substr($class, strlen($prefix))).'.php';
        if (is_file($path)) {
            require $path;
        }
    }
});

$failures = [];
if (extension_loaded('mbstring')) {
    $failures[] = 'this runtime unexpectedly has ext-mbstring; the polyfill would not be exercised';
}
require __DIR__.'/../../src/Polyfill/mbstring.php';

foreach (Pam\Native\Polyfill\Mbstring::FUNCTIONS as $function) {
    if (!function_exists($function)) {
        $failures[] = "{$function}() is not defined";
    }
}

$checked = 0;
foreach (require __DIR__.'/../Fixtures/mbstring_expectations.php' as [$function, $arguments, $expected]) {
    if ($expected === null) {
        continue;
    }
    set_error_handler(static fn (): bool => true);
    try {
        $actual = $function(...$arguments);
    } catch (Throwable $error) {
        $actual = $error::class.': '.$error->getMessage();
    } finally {
        restore_error_handler();
    }
    $checked++;
    if ($actual !== $expected) {
        $failures[] = sprintf('%s(%s) returned %s, expected %s', $function, json_encode($arguments, JSON_INVALID_UTF8_SUBSTITUTE), var_export($actual, true), var_export($expected, true));
    }
}

// What community packages do with message text (pam-native-calls Calls::text()).
$message = 'Chamada de vídeo perdida 📹 — toque para retornar ao João';
$preview = mb_strlen($message) > 24 ? rtrim(mb_substr($message, 0, 23)).'…' : $message;
if ($preview !== 'Chamada de vídeo perdid…' || mb_str_split('ação')[2] !== 'ã'
    || mb_convert_encoding('Ação', 'ISO-8859-1') !== "A\xE7\xE3o" || mb_strtoupper('joão') !== 'JOÃO') {
    $failures[] = 'message preview helpers do not match ext-mbstring';
}

try {
    mb_strlen('x', 'SJIS-nope');
    $failures[] = 'an unknown encoding did not raise ValueError';
} catch (ValueError) {
}

if ($failures !== []) {
    echo "PAM_MBSTRING_RUNTIME_FAILED\n".implode("\n", $failures)."\n";
    exit(1);
}
printf("PAM_MBSTRING_RUNTIME_OK php=%s os=%s functions=%d expectations=%d\n", PHP_VERSION, PHP_OS, count(Pam\Native\Polyfill\Mbstring::FUNCTIONS), $checked);
