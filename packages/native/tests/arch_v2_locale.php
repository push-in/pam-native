<?php

declare(strict_types=1);

use Pam\Native\AppState;
use Pam\Native\Composables\SharedSource;
use Pam\Native\Composables\Sources;
use Pam\Native\EventKind;
use Pam\Native\Internal\Runtime;
use Pam\Native\Internal\Wire;
use Pam\Native\ModuleResultStatus;
use Pam\Native\NativeOperation;

use function Pam\Native\effectScope;
use function Pam\Native\useLocale;
use function Pam\Native\useTimezone;
use function Pam\Native\watch;

$assert = require __DIR__.'/arch_v2_bootstrap.php';

/** Request id of the last DeviceInfo call sent to the host. */
$lastDeviceCall = static function (): ?int {
    if (class_exists('TestDiagnostics', false) && TestDiagnostics::$typedCall !== null) {
        $call = TestDiagnostics::$typedCall;

        return $call['operation'] === NativeOperation::DeviceInfo->value ? $call['requestId'] : null;
    }
    foreach (array_reverse($GLOBALS['pamArchV2TypedCalls'] ?? []) as [$id, $operation]) {
        if ($operation === NativeOperation::DeviceInfo->value) return $id;
    }

    return null;
};
$answer = static function (int $requestId, string $locale, string $zone): void {
    Runtime::dispatchModuleResult($requestId, ModuleResultStatus::Success->value, Wire::map([
        'width' => 400.0, 'height' => 800.0, 'density' => 2.0, 'appearance' => 1, 'appState' => 1,
        'timeZone' => $zone, 'locale' => $locale,
    ]));
};

Sources::reset();
$defaultZone = date_default_timezone_get();

$beforeSubscribe = $lastDeviceCall();
$values = [];
$scope = effectScope(static function () use (&$values): void {
    $values['locale'] = useLocale();
    $values['zone'] = useTimezone();
});
$locale = $values['locale'];
$zone = $values['zone'];
$assert($lastDeviceCall() === $beforeSubscribe, 'Subscribing must not ask the host for DeviceInfo (lazy until read).');
$assert(
    SharedSource::active() === ['device' => 2],
    'useLocale() and useTimezone() must share one device source without a plugin: '.json_encode(SharedSource::active()),
);
$assert(
    !$locale->known && $locale->value === 'en' && $locale->device === '' && !$zone->known && $zone->value === $defaultZone,
    'Before the host answers, the locale is the neutral fallback and the zone is PHP\'s default.',
);

// The host answers DeviceInfo: values follow, reactively.
$seen = [];
$watcher = watch(static fn (): string => $locale->value.'|'.$zone->value, static function (string $next) use (&$seen): void {
    $seen[] = $next;
});
$first = $lastDeviceCall();
$assert($first !== null && $first !== $beforeSubscribe, 'The first read must ask the host for DeviceInfo.');
$zone->value;
$assert($lastDeviceCall() === $first, 'Later reads must not ask again.');
$answer($first, 'pt-BR', 'America/Sao_Paulo');
$assert(
    $locale->known && $locale->value === 'pt-BR' && $locale->language === 'pt' && $locale->region === 'BR'
        && $locale->device === 'pt-BR'
        && $zone->value === 'America/Sao_Paulo' && $zone->now()->getTimezone()->getName() === 'America/Sao_Paulo',
    'useLocale()/useTimezone() must follow the device: '.json_encode($locale->toArray()),
);

// Back in the foreground the host is asked again (the user may have changed the language).
Runtime::dispatchEvent(0, EventKind::AppState->value, (string) AppState::Background->value);
$assert($lastDeviceCall() === $first, 'Backgrounding must not ask the host.');
Runtime::dispatchEvent(0, EventKind::AppState->value, (string) AppState::Active->value);
$second = $lastDeviceCall();
$assert($second !== null && $second !== $first, 'Returning to the foreground must ask DeviceInfo again.');
$answer($second, 'es-419', 'America/Mexico_City');
$assert($locale->value === 'es-419' && $locale->language === 'es' && $locale->region === '419' && $zone->value === 'America/Mexico_City', 'A new device locale must reach readers.');
$watcher->dispose();
$assert(
    $seen === ['pt-BR|America/Sao_Paulo', 'es-419|America/Mexico_City'],
    'Readers must be notified of each change: '.json_encode($seen),
);

// Unmount releases both sources; a late answer is ignored.
Runtime::dispatchEvent(0, EventKind::AppState->value, (string) AppState::Background->value);
Runtime::dispatchEvent(0, EventKind::AppState->value, (string) AppState::Active->value);
$late = $lastDeviceCall();
$scope->dispose();
$assert(SharedSource::active() === [], 'Disposing must release the locale sources.');
$answer($late, 'fr-FR', 'Europe/Paris');
$assert(Sources::device()->signal->get()?->locale === 'es-419', 'A DeviceInfo answer after the last consumer left must be ignored.');

Sources::reset();

echo "ARCH_V2_LOCALE_OK system-only reactive foreground-refresh shared no-leak\n";
