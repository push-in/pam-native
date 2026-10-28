<?php

declare(strict_types=1);

use Pam\Native\App;
use Pam\Native\EventKind;
use Pam\Native\Internal\HandlerPurity;
use Pam\Native\Internal\Runtime;

// (App namespace: framework classes are never analyzed.)
// Template handlers inferred pure (static analysis, plain state) skip the
// render when the component's state did not change; handlers that write
// static state or call services keep re-rendering.
$assert ??= static function (bool $condition, string $message): void {
    if (!$condition) throw new RuntimeException($message);
};
if (!function_exists('pam_native_commit')) {
    function pam_native_commit(string $frame): bool
    {
        $GLOBALS['pamIdentityFrames'][] = $frame;

        return true;
    }
}
$purityDirectory = sys_get_temp_dir().'/pam-native-handler-purity-'.getmypid();
if (!is_dir($purityDirectory) && !mkdir($purityDirectory, 0o755, true) && !is_dir($purityDirectory)) {
    throw new RuntimeException('Cannot create the handler purity fixture directory.');
}
file_put_contents($purityDirectory.'/PurityProbe.pam.php', <<<'PAM'
<?php

declare(strict_types=1);

namespace PurityFixture;

use Pam\Native\Component;

final class PurityProbe extends Component
{
    public static int $renders = 0;

    public static int $global = 0;

    public int $count = 0;

    public function label(): string
    {
        self::$renders++;

        return 'n='.$this->count.' g='.self::$global.' p='.PurityService::$pings;
    }

    public function loaded(): void
    {
        $seen = $this->count > 0 ? 'again' : 'first';
        $this->describe($seen);
    }

    private function describe(string $seen): string
    {
        return strtoupper($seen);
    }

    public function bump(): void
    {
        $this->count++;
    }

    public function writeStatic(): void
    {
        self::$global++;
    }

    public function callService(): void
    {
        PurityService::ping();
    }
}
?>

<template>
    <Column on:layout="loaded">
        <Text>{{ label() }}</Text>
        <Pressable on:press="bump"><Text>bump</Text></Pressable>
        <Pressable on:press="writeStatic"><Text>static</Text></Pressable>
        <Pressable on:press="callService"><Text>service</Text></Pressable>
    </Column>
</template>
PAM);
if (!class_exists('PurityFixture\\PurityService', false)) {
    eval('namespace PurityFixture; final class PurityService { public static int $pings = 0; public static function ping(): void { self::$pings++; } }');
}
App::components($purityDirectory, $purityDirectory.'/.cache');
HandlerPurity::reset();
App::run(App::make('PurityFixture\\PurityProbe'));
$probe = 'PurityFixture\\PurityProbe';
$event = static function (string $method, int $kind): array {
    foreach ((new ReflectionProperty(Runtime::class, 'eventCallbacks'))->getValue() as $key => $callback) {
        if (!str_ends_with((string) $key, ':'.$kind)) {
            continue;
        }
        $statics = (new ReflectionFunction($callback))->getStaticVariables();
        if (($statics['method'] ?? null) instanceof ReflectionMethod && $statics['method']->name === $method) {
            return array_map('intval', explode(':', (string) $key));
        }
    }
    throw new RuntimeException("No {$method} handler.");
};
$renders = static function (string $method, int $kind = EventKind::Press->value) use ($event, $probe): int {
    $before = $probe::$renders;
    [$node, $eventKind] = $event($method, $kind);
    Runtime::dispatchEvent($node, $eventKind, $kind === EventKind::Layout->value ? \Pam\Native\Internal\Wire::map(['x' => 0.0, 'y' => 0.0, 'width' => 10.0, 'height' => 10.0]) : '');

    return $probe::$renders - $before;
};
$production = \Pam\Native\BuildConfiguration::mode() !== \Pam\Native\BuildMode::Development;
$loaded = $renders('loaded', EventKind::Layout->value);
$assert(
    $loaded === ($production ? 0 : 1),
    "An on:layout handler that touches no state is inferred pure: no render (dev renders once to verify), got {$loaded}.",
);
$previousMode = getenv('PAM_NATIVE_MODE');
putenv('PAM_NATIVE_MODE=production');
$productionLoaded = $renders('loaded', EventKind::Layout->value);
putenv($previousMode === false ? 'PAM_NATIVE_MODE' : 'PAM_NATIVE_MODE='.$previousMode);
$assert($productionLoaded === 0, "In production an inferred-pure handler with no state change renders nothing, got {$productionLoaded}.");
$assert($renders('bump') === 1, 'A pure handler that changes state still re-renders.');
$assert($renders('writeStatic') === 1, 'A handler writing static state read by the render keeps re-rendering.');
$assert($renders('callService') === 1, 'A handler calling a service keeps re-rendering.');
$assert(
    HandlerPurity::inferred(new ReflectionMethod($probe, 'loaded'))
        && !HandlerPurity::inferred(new ReflectionMethod($probe, 'writeStatic'))
        && !HandlerPurity::inferred(new ReflectionMethod($probe, 'callService')),
    'Purity inference: own private calls are pure; static writes and service calls are not.',
);
Runtime::shutdown();
$GLOBALS['pamIdentityFrames'] = [];
echo 'HANDLER_PURITY_OK inferred=0-render static=render service=render mode='.($production ? 'production' : 'development')."\n";
