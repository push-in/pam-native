<?php

declare(strict_types=1);

use Pam\Native\Plugin\MovedToPlugin;
use Pam\Native\Plugin\PluginException;

// 1.46: features moved out of the core keep a deprecated alias for one minor.
// Without the plugin, the old name fails with the exact composer require.
foreach ([
    'Pam\\Native\\System\\Location' => 'pushinbr/pam-native-location',
    'Pam\\Native\\LocationPosition' => 'pushinbr/pam-native-location',
    'Pam\\Native\\System\\Contacts' => 'pushinbr/pam-native-contacts',
    'Pam\\Native\\System\\Sms' => 'pushinbr/pam-native-contacts',
    'Pam\\Native\\System\\Sensors' => 'pushinbr/pam-native-sensors',
    'Pam\\Native\\SensorType' => 'pushinbr/pam-native-sensors',
    'Pam\\Native\\System\\AudioRecorder' => 'pushinbr/pam-native-audio',
    'Pam\\Native\\System\\ImageEditor' => 'pushinbr/pam-native-media-editor',
    'Pam\\Native\\ImageCropRatio' => 'pushinbr/pam-native-media-editor',
] as $legacy => $package) {
    $message = '';
    try {
        class_exists($legacy);
    } catch (PluginException $error) {
        $message = $error->getMessage();
    }
    $assert(
        str_contains($message, $legacy.' moved out of the PAM Native core into '.$package)
            && str_contains($message, 'composer require '.$package),
        "{$legacy} must point to {$package} when the plugin is missing.",
    );
}

foreach ([
    'Pam\\Native\\System\\Notifications' => 'pushinbr/pam-native-notifications',
    'Pam\\Native\\System\\MediaLibrary' => 'pushinbr/pam-native-media',
    'Pam\\Native\\System\\IncomingShares' => 'pushinbr/pam-native-share-extension',
    'Pam\\Native\\System\\Biometrics' => 'pushinbr/pam-native-auth',
    'Pam\\Native\\UI\\WebView' => 'pushinbr/pam-native-webview',
] as $legacy => $package) {
    if ($testPluginRoots !== []) continue;
    $message = '';
    try { class_exists($legacy); } catch (PluginException $error) { $message = $error->getMessage(); }
    $assert(str_contains($message, 'composer require '.$package), $legacy.' must name its plugin.');
}

// With the plugin installed the old name is the plugin class itself.
eval('namespace Pam\\Native\\MovedFixture; enum Ratio: int { case Square = 1; }');
MovedToPlugin::alias('Pam\\Native\\LegacyRatio', 'Pam\\Native\\MovedFixture\\Ratio', 'pushinbr/pam-native-fixture');
$assert(
    \Pam\Native\LegacyRatio::Square === \Pam\Native\MovedFixture\Ratio::Square
        && MovedToPlugin::aliased()['Pam\\Native\\LegacyRatio'] === 'pushinbr/pam-native-fixture',
    'A moved class must alias the plugin class so instanceof and enum cases keep working.',
);

echo "MOVED_TO_PLUGIN_OK aliases=".count(MovedToPlugin::aliased())."\n";
