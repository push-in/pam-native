<?php

declare(strict_types=1);

$assert = require __DIR__.'/arch_v2_bootstrap.php';
foreach (['Pam\\Native\\I18n\\I18n', 'Pam\\Native\\Support\\Format', 'Pam\\Native\\Support\\DateStyle', 'Pam\\Native\\Support\\RelativeTimeStyle'] as $legacy) {
    $missing = false;
    try { class_exists($legacy); }
    catch (\Pam\Native\Plugin\PluginException $error) {
        $missing = str_contains($error->getMessage(), 'composer require pushinbr/pam-native-i18n');
    }
    $assert($missing, $legacy.' must name the restoring package.');
}
require __DIR__.'/i18n.php';
require __DIR__.'/arch_v2_locale.php';
echo "I18N_CORE_WITHOUT_PLUGIN_OK shims=4 helpers=10 system-locale=ok\n";
