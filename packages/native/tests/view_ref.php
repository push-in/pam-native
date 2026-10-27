<?php

declare(strict_types=1);

use Pam\Native\Internal\Wire;
use Pam\Native\ModuleResultStatus;
use Pam\Native\Modules\NativeModules;
use Pam\Native\Modules\NativeModuleTransport;
use Pam\Native\System\ViewRef;
use Pam\Native\ViewMeasurement;

$viewRefCalls = [];
$viewRefReply = null;
NativeModules::useTransport(new class ($viewRefCalls, $viewRefReply) implements NativeModuleTransport {
    public function __construct(private array &$calls, private ?array &$reply)
    {
    }

    public function invoke(int $requestId, string $module, string $method, string $payload, Closure $complete): void
    {
        $this->calls[] = [$module, $method, Wire::decodeMap($payload)];
        [$status, $body] = $this->reply ?? [ModuleResultStatus::Success, ''];
        $complete($status, $body);
    }
});

$done = [];
$viewRefReply = null;
ViewRef::of('composer')->focus(static function (bool $ok, string $error) use (&$done): void { $done[] = [$ok, $error]; });
ViewRef::of('composer')->setSelection(3);
ViewRef::of('composer')->setSelection(1, 4);
ViewRef::of('composer')->clear();
ViewRef::of('composer')->blur();
$methods = array_map(static fn (array $call): string => $call[0].'.'.$call[1], $viewRefCalls);
if ($methods !== ['view-ref.focus', 'view-ref.setSelection', 'view-ref.setSelection', 'view-ref.clear', 'view-ref.blur']
    || ($viewRefCalls[0][2]['ref'] ?? '') !== 'composer'
    || (int) ($viewRefCalls[1][2]['start'] ?? -1) !== 3 || (int) ($viewRefCalls[1][2]['end'] ?? -1) !== 3
    || (int) ($viewRefCalls[2][2]['start'] ?? -1) !== 1 || (int) ($viewRefCalls[2][2]['end'] ?? -1) !== 4
    || $done !== [[true, '']]) {
    throw new RuntimeException('ViewRef input commands must reach the view-ref module with the ref and a caret/selection: '.json_encode([$viewRefCalls, $done]));
}

$viewRefReply = [ModuleResultStatus::Failure, 'No mounted view has nativeRef gone.'];
ViewRef::of('gone')->focus(static function (bool $ok, string $error) use (&$done): void { $done[] = [$ok, $error]; });
if (($done[1] ?? null) !== [false, 'No mounted view has nativeRef gone.']) {
    throw new RuntimeException('A ViewRef command on an unmounted ref reports the error: '.json_encode($done));
}

$measured = [];
$viewRefReply = [ModuleResultStatus::Success, Wire::map([
    'x' => 10.0, 'y' => 40.0, 'width' => 200.0, 'height' => 120.0,
    'pageX' => 10.0, 'pageY' => 64.0, 'windowX' => 10.0, 'windowY' => 88.0,
])];
ViewRef::of('card')->measure(static function (?ViewMeasurement $frame, string $error) use (&$measured): void { $measured[] = $frame; });
ViewRef::of('card')->measureInWindow(static function (?ViewMeasurement $frame, string $error) use (&$measured): void { $measured[] = $frame; });
[$parentFrame, $windowFrame] = $measured + [null, null];
if (!$parentFrame instanceof ViewMeasurement || $parentFrame->x !== 10.0 || $parentFrame->y !== 40.0 || $parentFrame->pageY !== 64.0
    || !$windowFrame instanceof ViewMeasurement || $windowFrame->x !== 10.0 || $windowFrame->y !== 88.0 || $windowFrame->width !== 200.0
    || !$windowFrame->containsWindowPoint(100.0, 100.0) || $windowFrame->containsWindowPoint(100.0, 300.0)) {
    throw new RuntimeException('measure() maps the parent frame and measureInWindow() the window frame: '.json_encode($measured));
}
$viewRefReply = [ModuleResultStatus::Failure, 'The view card has not been laid out.'];
ViewRef::of('card')->measureInWindow(static function (?ViewMeasurement $frame, string $error) use (&$measured): void { $measured[] = [$frame, $error]; });
if (($measured[2] ?? null) !== [null, 'The view card has not been laid out.']) {
    throw new RuntimeException('A failed measurement passes null and the error.');
}

$viewRefReply = null;
$before = count($viewRefCalls);
ViewRef::of('bubble', within: 'row-42')->measureInWindow(static fn () => null);
$scoped = $viewRefCalls[$before][2] ?? [];
if (($scoped['ref'] ?? '') !== 'bubble' || ($scoped['within'] ?? '') !== 'row-42' || array_key_exists('within', $viewRefCalls[0][2])) {
    throw new RuntimeException('A ref repeated by list rows is addressed within its row: '.json_encode($scoped));
}
try {
    ViewRef::of('bubble', within: 'row 42');
    throw new RuntimeException('An invalid within ref was accepted.');
} catch (InvalidArgumentException) {
}

$focusedAnswers = [];
$viewRefReply = [ModuleResultStatus::Success, Wire::map(['focused' => true])];
ViewRef::of('composer')->isFocused(static function (bool $focused) use (&$focusedAnswers): void { $focusedAnswers[] = $focused; });
$viewRefReply = [ModuleResultStatus::Failure, 'gone'];
ViewRef::of('composer')->isFocused(static function (bool $focused) use (&$focusedAnswers): void { $focusedAnswers[] = $focused; });
if ($focusedAnswers !== [true, false]) {
    throw new RuntimeException('isFocused() answers true only for a focused mounted input.');
}

foreach ([
    static fn () => ViewRef::of('bad ref'),
    static fn () => ViewRef::of(''),
    static fn () => ViewRef::of('composer')->setSelection(-1),
    static fn () => ViewRef::of('composer')->setSelection(5, 2),
] as $bad) {
    try {
        $bad();
        throw new RuntimeException('An invalid ViewRef request was accepted.');
    } catch (InvalidArgumentException) {
    }
}
// Ordering: a view call made inside a handler reaches native after the
// render of that handler (the new value/layout), not before it.
$viewRefOrder = [];
\Pam\Native\Internal\Runtime::boot(static function () use (&$viewRefOrder): \Pam\Native\Element {
    $viewRefOrder[] = 'render';

    return \Pam\Native\UI\Text::make('composer');
});
NativeModules::useTransport(new class ($viewRefOrder) implements NativeModuleTransport {
    public function __construct(private array &$order)
    {
    }

    public function invoke(int $requestId, string $module, string $method, string $payload, Closure $complete): void
    {
        $this->order[] = $module.'.'.$method;
        $complete(ModuleResultStatus::Success, '');
    }
});
$viewRefOrder = [];
NativeModules::call('probe', 'tick', [], static function () use (&$viewRefOrder): void {
    ViewRef::of('composer')->setSelection(4);
    $viewRefOrder[] = 'handler-done';
});
if (array_slice($viewRefOrder, 0, 4) !== ['probe.tick', 'handler-done', 'render', 'view-ref.setSelection']) {
    throw new RuntimeException('ViewRef calls run after the handler\'s commit: '.json_encode($viewRefOrder));
}
\Pam\Native\Internal\Runtime::shutdown();
NativeModules::useTransport(null);
echo "VIEW_REF_OK focus blur clear setSelection isFocused measure measureInWindow\n";
