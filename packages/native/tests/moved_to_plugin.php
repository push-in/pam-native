<?php

declare(strict_types=1);

use Pam\Native\Plugin\MovedToPlugin;
use Pam\Native\Plugin\PluginException;

// The 1.46 aliases promised for removal in 1.47 are gone; the old
// names are plain unknown classes (no core shim, no PluginException).
foreach ([
    'Pam\\Native\\System\\Location', 'Pam\\Native\\LocationPosition', 'Pam\\Native\\System\\Contacts',
    'Pam\\Native\\System\\Sms', 'Pam\\Native\\System\\Sensors', 'Pam\\Native\\SensorType',
    'Pam\\Native\\System\\AudioRecorder', 'Pam\\Native\\System\\ImageEditor', 'Pam\\Native\\ImageCropRatio',
    'Pam\\Native\\System\\Notifications', 'Pam\\Native\\System\\MediaLibrary', 'Pam\\Native\\System\\IncomingShares',
    'Pam\\Native\\System\\Biometrics', 'Pam\\Native\\MediaAsset', 'Pam\\Native\\PushMessage',
    'Pam\\Native\\Notifications\\Person', 'Pam\\Native\\NotificationImportance',
] as $legacy) {
    $assert(!class_exists($legacy) && !enum_exists($legacy), "{$legacy} must no longer be shipped by the core.");
}
$assert(
    !is_file(__DIR__.'/../src/MediaAsset.php') && !is_file(__DIR__.'/../src/System/Location.php'),
    'The removed shims must not ship in the core package.',
);

// Still kept: the core routes <WebView> and Route::drawer() through these
// names, and pam-native-toast's compat test asserts the toast aliases.
foreach ([
    'Pam\\Native\\System\\Toast' => 'pushinbr/pam-native-toast',
    'Pam\\Native\\System\\ToastType' => 'pushinbr/pam-native-toast',
    'Pam\\Native\\System\\ToastPosition' => 'pushinbr/pam-native-toast',
    'Pam\\Native\\UI\\DrawerLayoutAndroid' => 'pushinbr/pam-native-drawer',
    'Pam\\Native\\Navigation\\DrawerNavigator' => 'pushinbr/pam-native-drawer',
    'Pam\\Native\\Navigation\\DrawerRouter' => 'pushinbr/pam-native-drawer',
    'Pam\\Native\\Navigation\\NavigationDrawerItem' => 'pushinbr/pam-native-drawer',
    'Pam\\Native\\Navigation\\DrawerType' => 'pushinbr/pam-native-drawer',
    'Pam\\Native\\Navigation\\DrawerPosition' => 'pushinbr/pam-native-drawer',
    'Pam\\Native\\Navigation\\DrawerBackBehavior' => 'pushinbr/pam-native-drawer',
    'Pam\\Native\\Navigation\\DrawerKeyboardDismissMode' => 'pushinbr/pam-native-drawer',
    'Pam\\Native\\Navigation\\DrawerStatusBarAnimation' => 'pushinbr/pam-native-drawer',
    'Pam\\Native\\Routing\\DrawerRegistrar' => 'pushinbr/pam-native-drawer',
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
