<?php

declare(strict_types=1);

use Pam\Native\App;
use Pam\Native\Appearance;
use Pam\Native\AppearanceMode;
use Pam\Native\Component;
use Pam\Native\Element;
use Pam\Native\EventKind;
use Pam\Native\Internal\CompiledTemplateNode;
use Pam\Native\Internal\Runtime;
use Pam\Native\Internal\ScopedStyleCompiler;
use Pam\Native\Internal\TemplateCompiler;
use Pam\Native\Internal\TemplateRenderer;
use Pam\Native\Internal\Wire;
use Pam\Native\ModuleResultStatus;
use Pam\Native\Modules\NativeModules;
use Pam\Native\Modules\NativeModuleTransport;
use Pam\Native\PropKey;
use Pam\Native\UI\Text;
use Pam\Native\UserInterfaceAppearance;

/** @var Closure(bool, string): void $assert */

$appearanceEnvironment = static function (?string $mode, ?string $systemDark, ?string $system): void {
    foreach ([
        'PAM_APPEARANCE_MODE' => $mode,
        'PAM_SYSTEM_DARK' => $systemDark,
        'PAM_SYSTEM_APPEARANCE' => $system,
    ] as $name => $value) {
        putenv($value === null ? $name : $name.'='.$value);
    }
};
$appearanceStyles = json_encode(ScopedStyleCompiler::compile(
    'Text { color: #000000; } @media (prefers-color-scheme: dark) { Text { color: #ffffff; } }',
    'AppearanceFirstFrame.pam',
), JSON_THROW_ON_ERROR);
$appearanceTemplate = TemplateCompiler::compile('<Column><Text>Scheme</Text></Column>');
$appearanceTextColor = static function () use ($appearanceTemplate, $appearanceStyles): mixed {
    $styled = new CompiledTemplateNode(
        kind: $appearanceTemplate->kind,
        name: $appearanceTemplate->name,
        attributes: [...$appearanceTemplate->attributes, '__pamStyles' => $appearanceStyles],
        source: $appearanceTemplate->source,
        line: $appearanceTemplate->line,
        column: $appearanceTemplate->column,
        value: $appearanceTemplate->value,
    );
    $styled->children = $appearanceTemplate->children;

    return TemplateRenderer::render($styled, null, [])->children()[0]->properties()[PropKey::TextColor->value] ?? null;
};
$appearanceDimensions = static function (int $appearance, ?int $mode, ?int $system): void {
    $values = ['width' => 400.0, 'height' => 800.0, 'density' => 2.0, 'appearance' => $appearance];
    if ($mode !== null) {
        $values['appearanceMode'] = $mode;
    }
    if ($system !== null) {
        $values['systemAppearance'] = $system;
    }
    Runtime::dispatchEvent(0, EventKind::Dimensions->value, Wire::map($values));
};

// A persisted Dark override on a light system: the host exports the effective
// scheme before PHP starts, so the very first frame resolves dark CSS without
// waiting for any dimensions event.
$appearanceEnvironment('3', '1', '1');
Runtime::shutdown();
$assert(
    Appearance::mode() === AppearanceMode::Dark
        && Appearance::current() === UserInterfaceAppearance::Dark
        && Appearance::isDark()
        && Appearance::system() === UserInterfaceAppearance::Light
        && App::appearance() === UserInterfaceAppearance::Dark
        && $appearanceTextColor() === 0xFFFFFFFF,
    'Boot metrics must deliver the persisted Dark override synchronously to PHP and prefers-color-scheme before the first frame.',
);

$appearanceEnvironment('1', '1', null);
Runtime::shutdown();
$assert(
    Appearance::mode() === AppearanceMode::System
        && Appearance::current() === UserInterfaceAppearance::Dark
        && Appearance::system() === UserInterfaceAppearance::Dark
        && $appearanceTextColor() === 0xFFFFFFFF,
    'A dark system must render dark CSS in the first frame when the app follows the system.',
);

$appearanceEnvironment(null, null, null);
Runtime::shutdown();
$assert(
    Appearance::mode() === AppearanceMode::System
        && Appearance::current() === UserInterfaceAppearance::Light
        && $appearanceTextColor() === 0xFF000000,
    'Hosts that export no appearance must keep the light System default.',
);

$appearanceCalls = [];
$appearancePending = [];
$appearanceRecord = static function (string $module, string $method, string $payload, Closure $complete) use (&$appearanceCalls, &$appearancePending): void {
    $appearanceCalls[] = [$module, $method, Wire::decodeMap($payload)];
    $appearancePending[] = $complete;
};

$appearanceRoot = new class extends Component {
    public static int $mounts = 0;

    public function mount(): void
    {
        self::$mounts++;
    }

    public function render(): Element
    {
        return Text::make(Appearance::isDark() ? 'dark' : 'light');
    }
};
$appearanceEnvironment('1', '0', '1');
Runtime::shutdown();
NativeModules::useTransport(new class ($appearanceRecord) implements NativeModuleTransport {
    public function __construct(private readonly Closure $record)
    {
    }

    public function invoke(int $requestId, string $module, string $method, string $payload, Closure $complete): void
    {
        ($this->record)($module, $method, $payload, $complete);
    }
});
$appearanceChanges = [];
Appearance::onChange(static function (UserInterfaceAppearance $appearance, AppearanceMode $mode) use (&$appearanceChanges): void {
    $appearanceChanges[] = [$appearance, $mode];
});
App::run($appearanceRoot);
$appearanceLightFrame = Runtime::lastFrame();

Appearance::set(AppearanceMode::Dark);
$assert(
    Appearance::mode() === AppearanceMode::Dark
        && Appearance::current() === UserInterfaceAppearance::Dark
        && $appearanceTextColor() === 0xFFFFFFFF
        && Runtime::lastFrame() !== $appearanceLightFrame
        && $appearanceRoot::$mounts === 1
        && $appearanceCalls === [['appearance', 'set', ['mode' => AppearanceMode::Dark->value]]]
        && $appearanceChanges === [[UserInterfaceAppearance::Dark, AppearanceMode::Dark]],
    'Appearance::set must restyle synchronously without remounting, persist through the native module and notify listeners once.',
);

// A dimensions event raised before the host received the write must not
// revert the optimistic Dark override.
$appearanceDimensions(UserInterfaceAppearance::Light->value, AppearanceMode::System->value, UserInterfaceAppearance::Light->value);
$assert(
    Appearance::mode() === AppearanceMode::Dark
        && Appearance::current() === UserInterfaceAppearance::Dark
        && count($appearanceChanges) === 1,
    'Stale host metrics must not override a pending appearance write.',
);
foreach ($appearancePending as $complete) {
    $complete(ModuleResultStatus::Success, '');
}
$appearancePending = [];
$appearanceDimensions(UserInterfaceAppearance::Dark->value, AppearanceMode::Dark->value, UserInterfaceAppearance::Light->value);
$assert(
    Appearance::current() === UserInterfaceAppearance::Dark && count($appearanceChanges) === 1,
    'A confirming host event must not emit a duplicate change.',
);

Appearance::set(AppearanceMode::System);
$assert(
    Appearance::mode() === AppearanceMode::System
        && Appearance::current() === UserInterfaceAppearance::Light
        && $appearanceChanges[1] === [UserInterfaceAppearance::Light, AppearanceMode::System],
    'Returning to System must resolve the last known system appearance immediately.',
);
foreach ($appearancePending as $complete) {
    $complete(ModuleResultStatus::Success, '');
}
$appearancePending = [];

// The operating system switches to dark while the app follows it.
$appearanceDimensions(UserInterfaceAppearance::Dark->value, AppearanceMode::System->value, UserInterfaceAppearance::Dark->value);
$assert(
    Appearance::current() === UserInterfaceAppearance::Dark
        && $appearanceTextColor() === 0xFFFFFFFF
        && $appearanceRoot::$mounts === 1
        && $appearanceChanges[2] === [UserInterfaceAppearance::Dark, AppearanceMode::System],
    'Runtime system theme changes must restyle and notify without remounting.',
);

// Hosts that predate the appearance contract report only the system scheme.
Appearance::set(AppearanceMode::Light);
foreach ($appearancePending as $complete) {
    $complete(ModuleResultStatus::Success, '');
}
$appearancePending = [];
$appearanceDimensions(UserInterfaceAppearance::Dark->value, null, null);
$assert(
    Appearance::current() === UserInterfaceAppearance::Light
        && Appearance::system() === UserInterfaceAppearance::Dark,
    'A PHP-side override must survive metrics from hosts without an appearance contract.',
);

$appearanceSubscription = Appearance::onChange(static function (): void {
    throw new LogicException('Unsubscribed appearance listener was called.');
});
Appearance::unsubscribe($appearanceSubscription);
Appearance::set(AppearanceMode::Dark);
$assert(Appearance::current() === UserInterfaceAppearance::Dark, 'Unsubscribed listeners must not run.');

NativeModules::useTransport(null);
$appearanceEnvironment(null, null, null);
Runtime::shutdown();
