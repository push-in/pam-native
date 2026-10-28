<?php

declare(strict_types=1);

use Pam\Native\App;
use Pam\Native\Appearance;
use Pam\Native\AppearanceMode;
use Pam\Native\AppState;
use Pam\Native\Composables\SharedSource;
use Pam\Native\Composables\Sources;
use Pam\Native\DeviceClass;
use Pam\Native\EventKind;
use Pam\Native\Internal\Runtime;
use Pam\Native\Internal\Wire;
use Pam\Native\ModuleResultStatus;
use Pam\Native\NetworkType;
use Pam\Native\System\Keyboard;
use Pam\Native\TextScalePolicy;
use Pam\Native\UserInterfaceAppearance;
use Pam\Native\WindowMetrics;

use function Pam\Native\computed;
use function Pam\Native\effectScope;
use function Pam\Native\useAppState;
use function Pam\Native\useColorScheme;
use function Pam\Native\useFontScale;
use function Pam\Native\useKeyboard;
use function Pam\Native\useNetwork;
use function Pam\Native\useSafeAreaInsets;
use function Pam\Native\useWindowDimensions;
use function Pam\Native\watch;

$assert = require __DIR__.'/arch_v2_bootstrap.php';

/** @return array{0: int, 1: string, 2: string}|null last native module call: request id, module, method */
$lastCall = static function (): ?array {
    if (class_exists('TestDiagnostics', false) && TestDiagnostics::$moduleCall !== null) {
        $call = TestDiagnostics::$moduleCall;

        return [$call['requestId'], $call['module'], $call['method']];
    }
    $calls = $GLOBALS['pamArchV2NativeCalls'] ?? [];
    $call = end($calls);

    return $call === false ? null : [$call[0], $call[1], $call[2]];
};
$count = static fn (string $class, string $property): int => count((new ReflectionProperty($class, $property))->getValue());

Sources::reset();
Appearance::resetRuntime();
Keyboard::resetRuntime();
TextScalePolicy::reset();
$appearanceBaseline = $count(Appearance::class, 'listeners');
$windowBaseline = Runtime::windowMetricsListeners();

$values = [];
$scope = effectScope(static function () use (&$values, $assert): void {
    $values['scheme'] = useColorScheme();
    $values['insets'] = useSafeAreaInsets();
    $values['window'] = useWindowDimensions();
    $values['font'] = useFontScale();
    $values['keyboard'] = useKeyboard();
    $values['app'] = useAppState();
    $values['network'] = useNetwork();
    // Several consumers of one kind share one platform subscription.
    useSafeAreaInsets();
    useWindowDimensions();
});
$watchCall = $lastCall();
$assert(
    SharedSource::active() === ['appearance' => 1, 'window' => 5, 'keyboard' => 1, 'appState' => 1, 'network' => 1]
        && Runtime::windowMetricsListeners() === $windowBaseline + 1
        && $count(Appearance::class, 'listeners') === $appearanceBaseline + 1,
    'Composables must share one subscription per kind: '.json_encode(SharedSource::active()),
);

// useColorScheme(): value/mode follow Appearance; set() accepts 'dark'|'light'|'system'.
$scheme = $values['scheme'];
$schemes = [];
$watcher = watch(static fn (): string => $scheme->value.'/'.$scheme->mode, static function (string $next) use (&$schemes): void {
    $schemes[] = $next;
});
$scheme->set('dark');
$scheme->set('system');
$scheme->set(AppearanceMode::Light);
$watcher->dispose();
$assert($schemes === ['dark/dark', 'light/system', 'light/light'] && $scheme->dark === false && $scheme->preference === AppearanceMode::Light, 'useColorScheme() must be reactive and settable: '.json_encode($schemes));
$badMode = false;
try {
    $scheme->set('sepia');
} catch (InvalidArgumentException) {
    $badMode = true;
}
$assert($badMode, 'useColorScheme()->set() must reject unknown modes.');

// Window metrics: insets, dimensions and font scale follow host updates, without polling.
$insets = $values['insets'];
$bottom = computed(static fn (): float => $insets->bottom);
Runtime::dispatchEvent(0, EventKind::Dimensions->value, Wire::map([
    'width' => 820.0, 'height' => 600.0, 'density' => 2.0, 'fontScale' => 1.8,
    'safeAreaTop' => 24.0, 'safeAreaBottom' => 34.0, 'safeAreaLeft' => 0.0, 'safeAreaRight' => 0.0,
]));
$window = $values['window'];
$font = $values['font'];
TextScalePolicy::set(maximum: 1.5, display: 1.2, displayFontSize: 20);
$assert(
    $insets->top === 24.0 && $bottom->value === 34.0 && $insets->toArray() === ['top' => 24.0, 'right' => 0.0, 'bottom' => 34.0, 'left' => 0.0],
    'useSafeAreaInsets() must follow the host window.',
);
$assert(
    $window->width === 820.0 && $window->landscape && $window->scale === 2.0 && $window->deviceClass === DeviceClass::Medium,
    'useWindowDimensions() must follow the host window.',
);
$assert($font->value === 1.8 && $font->effective === 1.5 && $font->display === 1.2 && $font->for(12.0) === 1.5, 'useFontScale() must apply TextScalePolicy caps.');
TextScalePolicy::reset();

// useKeyboard(): settled show/hide/resize.
$keyboard = $values['keyboard'];
Runtime::dispatchEvent(0, EventKind::KeyboardVisibility->value, '1;291.5;250');
$assert($keyboard->visible && $keyboard->height === 291.5 && $keyboard->durationMs === 250, 'useKeyboard() must report the settled keyboard.');
Runtime::dispatchEvent(0, EventKind::KeyboardVisibility->value, '0;0;200');
$assert(!$keyboard->visible && $keyboard->height === 0.0, 'useKeyboard() must report the keyboard hiding.');

// useAppState(): foreground/background transitions.
$app = $values['app'];
Runtime::dispatchEvent(0, EventKind::AppState->value, (string) AppState::Background->value);
$assert($app->background && !$app->active && $app->value === AppState::Background, 'useAppState() must follow backgrounding.');
Runtime::dispatchEvent(0, EventKind::AppState->value, (string) AppState::Active->value);
$assert($app->active, 'useAppState() must follow the return to the foreground.');

// useNetwork(): assumed online until the host pushes a status; pushes go through the device watch.
$network = $values['network'];
$assert($network->online && !$network->known && $network->type === null, 'useNetwork() must assume online until the first report.');
$assert($watchCall !== null && $watchCall[1] === 'device' && $watchCall[2] === 'watch', 'useNetwork() must start one device watch.');
Runtime::dispatchModuleResult($watchCall[0], ModuleResultStatus::Success->value, Wire::map(['subscription' => 9]));
$nextCall = $lastCall();
Runtime::dispatchModuleResult($nextCall[0], ModuleResultStatus::Success->value, Wire::map(['networkType' => NetworkType::None->value, 'expensiveNetwork' => false]));
$assert(!$network->online && !$network->value && $network->known && $network->type === NetworkType::None, 'useNetwork() must follow host status pushes.');

// Disposing the scope releases every subscription, including the native watch.
$scope->dispose();
$stopCall = $lastCall();
$assert(
    SharedSource::active() === []
        && Runtime::windowMetricsListeners() === $windowBaseline
        && $count(Appearance::class, 'listeners') === $appearanceBaseline
        && $stopCall !== null && $stopCall[1] === 'device' && $stopCall[2] === 'stop',
    'Disposing must release every composable subscription and stop the native device watch: '.json_encode(SharedSource::active()),
);

// Outside a scope a composable lives for the application (index.php globals).
$global = useColorScheme();
$assert(SharedSource::active() === ['appearance' => 1] && $global->value === 'light', 'A composable outside any scope must live as long as the app.');
$readOnly = false;
try {
    $global->value = 'dark';
} catch (LogicException) {
    $readOnly = true;
}
$assert($readOnly, 'Composable properties must be read-only.');
// Runtime::shutdown() (hot reload, tests) stops every shared source.
useKeyboard();
$assert(SharedSource::active() === ['appearance' => 1, 'keyboard' => 1], 'App-lifetime composables keep their sources until shutdown.');
Runtime::shutdown();
$assert(
    SharedSource::active() === [] && $count(Appearance::class, 'listeners') === 0
        && Runtime::windowMetricsListeners() === 0,
    'Runtime::shutdown() must reset SharedSource and release its platform listeners: '.json_encode(SharedSource::active()),
);
$fresh = useColorScheme();
$assert(SharedSource::active() === ['appearance' => 1] && $fresh->value === 'light', 'A composable after shutdown starts a fresh source.');
Sources::reset();
Appearance::resetRuntime();
Keyboard::resetRuntime();

echo "ARCH_V2_COMPOSABLES_OK scheme insets window font keyboard app-state network shared no-leak\n";
