<?php

declare(strict_types=1);

// Lets each architecture v2 test run alone (php tests/arch_v2_*.php) or from run.php.
if (!function_exists('Pam\Native\signal')) {
    spl_autoload_register(static function (string $class): void {
        if (!str_starts_with($class, 'Pam\\Native\\')) {
            return;
        }
        $path = __DIR__.'/../src/'.str_replace('\\', '/', substr($class, strlen('Pam\\Native\\'))).'.php';
        if (is_file($path)) {
            require $path;
        }
    });
    require_once __DIR__.'/../src/functions.php';
}
if (!function_exists('pam_native_call')) {
    function pam_native_call(int $requestId, string $module, string $method, string $payload): void
    {
        $GLOBALS['pamArchV2NativeCalls'][] = [$requestId, $module, $method, $payload];
    }
}

return static function (bool $condition, string $message): void {
    if (!$condition) {
        throw new RuntimeException($message);
    }
};
