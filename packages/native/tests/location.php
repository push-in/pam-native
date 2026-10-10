<?php

declare(strict_types=1);

use Pam\Native\Internal\Runtime;
use Pam\Native\Internal\Wire;
use Pam\Native\LocationError;
use Pam\Native\LocationPosition;
use Pam\Native\LocationServicesResult;
use Pam\Native\ModuleResultStatus;
use Pam\Native\System\Location;

// Location 1.35.0: last known position, the location services switch and typed failures.

$assert(
    LocationError::Permission->value === 1
        && LocationError::Disabled->value === 2
        && LocationError::Unavailable->value === 3
        && LocationError::Timeout->value === 4
        && LocationServicesResult::Enabled->value === 1
        && LocationServicesResult::Denied->value === 2
        && LocationServicesResult::Unavailable->value === 3,
    'Location enums must be sequential integers starting at 1.',
);

$failureCodes = [
    'permission: Location permission is required' => LocationError::Permission,
    'disabled: No enabled location provider' => LocationError::Disabled,
    'timeout: Timed out while obtaining location' => LocationError::Timeout,
    'unavailable: No last known location' => LocationError::Unavailable,
    // Runtimes before 1.35.0 sent bare English messages.
    'Location permission is required' => LocationError::Permission,
    'No enabled location provider' => LocationError::Disabled,
    'Timed out while obtaining location' => LocationError::Timeout,
    'Location request timed out.' => LocationError::Timeout,
    'kCLErrorDomain error 0' => LocationError::Unavailable,
    '' => LocationError::Unavailable,
];
foreach ($failureCodes as $failure => $expected) {
    $assert(
        LocationError::fromFailure((string) $failure) === $expected,
        "LocationError::fromFailure('{$failure}') must be {$expected->name}.",
    );
}
$assert(
    LocationError::detail('permission: Location permission is required') === 'Location permission is required'
        && LocationError::detail('No enabled location provider') === 'No enabled location provider'
        && LocationError::Disabled->code() === 'disabled'
        && LocationError::Timeout->failure('Timed out') === 'timeout: Timed out',
    'LocationError must strip and build the "<code>: <detail>" failure contract.',
);

$lastKnown = null;
$lastKnownRequest = Location::lastKnown(static function (LocationPosition $position) use (&$lastKnown): void {
    $lastKnown = $position;
});
$lastKnownCall = TestDiagnostics::$moduleCall;
$assert(
    $lastKnownCall !== null
        && $lastKnownCall['requestId'] === $lastKnownRequest
        && $lastKnownCall['module'] === 'location'
        && $lastKnownCall['method'] === 'lastKnown',
    'Location::lastKnown must emit its native module call.',
);
Runtime::dispatchModuleResult(
    $lastKnownRequest,
    ModuleResultStatus::Success->value,
    Wire::map(['latitude' => -22.9, 'longitude' => -43.2, 'accuracy' => 30.0, 'timestamp' => 1_785_000_000_000]),
);
$assert(
    $lastKnown instanceof LocationPosition && $lastKnown->latitude === -22.9 && $lastKnown->accuracy === 30.0,
    'Location::lastKnown must decode the native position.',
);
$lastKnownFailure = null;
Location::lastKnown(
    static function (LocationPosition $_): void {
        throw new RuntimeException('A missing last known position must not resolve.');
    },
    static function (string $message) use (&$lastKnownFailure): void {
        $lastKnownFailure = $message;
    },
);
Runtime::dispatchModuleResult(
    TestDiagnostics::$moduleCall['requestId'],
    ModuleResultStatus::Failure->value,
    'unavailable: No last known location',
);
$assert(
    $lastKnownFailure !== null && LocationError::fromFailure($lastKnownFailure) === LocationError::Unavailable,
    'Location::lastKnown failures must reach the failure callback with their code.',
);

$enabled = null;
Location::servicesEnabled(static function (bool $value) use (&$enabled): void {
    $enabled = $value;
});
$servicesCall = TestDiagnostics::$moduleCall;
$assert(
    $servicesCall['module'] === 'location' && $servicesCall['method'] === 'servicesEnabled',
    'Location::servicesEnabled must emit its native module call.',
);
Runtime::dispatchModuleResult($servicesCall['requestId'], ModuleResultStatus::Success->value, Wire::map(['enabled' => false]));
$assert($enabled === false, 'Location::servicesEnabled must decode the switch state.');
Location::servicesEnabled(static function (bool $value) use (&$enabled): void {
    $enabled = $value;
});
Runtime::dispatchModuleResult(TestDiagnostics::$moduleCall['requestId'], ModuleResultStatus::Failure->value, 'unavailable: x');
$assert($enabled === false, 'A failed services check must report the services as off.');

$servicesResults = [];
foreach ([1 => LocationServicesResult::Enabled, 2 => LocationServicesResult::Denied, 3 => LocationServicesResult::Unavailable, 9 => LocationServicesResult::Unavailable] as $wire => $expected) {
    Location::requestServices(static function (LocationServicesResult $result) use (&$servicesResults): void {
        $servicesResults[] = $result;
    });
    $requestCall = TestDiagnostics::$moduleCall;
    $assert(
        $requestCall['module'] === 'location' && $requestCall['method'] === 'requestServices',
        'Location::requestServices must emit its native module call.',
    );
    Runtime::dispatchModuleResult($requestCall['requestId'], ModuleResultStatus::Success->value, Wire::map(['result' => $wire]));
    $assert(end($servicesResults) === $expected, "requestServices result {$wire} must be {$expected->name}.");
}
Location::requestServices(static function (LocationServicesResult $result) use (&$servicesResults): void {
    $servicesResults[] = $result;
});
Runtime::dispatchModuleResult(TestDiagnostics::$moduleCall['requestId'], ModuleResultStatus::Failure->value, 'unavailable: No activity');
$assert(end($servicesResults) === LocationServicesResult::Unavailable, 'A failed requestServices must resolve Unavailable.');

$opened = null;
Location::openSettings(static function (bool $value) use (&$opened): void {
    $opened = $value;
});
$settingsCall = TestDiagnostics::$moduleCall;
$assert(
    $settingsCall['module'] === 'location' && $settingsCall['method'] === 'openSettings',
    'Location::openSettings must emit its native module call.',
);
Runtime::dispatchModuleResult($settingsCall['requestId'], ModuleResultStatus::Success->value, Wire::map(['opened' => true]));
$assert($opened === true, 'Location::openSettings must report whether the screen opened.');
Location::openSettings();
Runtime::dispatchModuleResult(TestDiagnostics::$moduleCall['requestId'], ModuleResultStatus::Failure->value, 'unavailable: x');

echo "LOCATION_OK lastKnown servicesEnabled requestServices openSettings typed-errors\n";
