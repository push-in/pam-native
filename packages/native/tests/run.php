<?php

declare(strict_types=1);

use Pam\Native\App;
use Pam\Native\AppState;
use Pam\Native\AnimationKind;
use Pam\Native\Component;
use Pam\Native\EventKind;
use Pam\Native\FontStyle;
use Pam\Native\Internal\Runtime;
use Pam\Native\Internal\TemplateCompiler;
use Pam\Native\Internal\TemplateRenderer;
use Pam\Native\Internal\TreeEncoder;
use Pam\Native\Internal\Wire;
use Pam\Native\MemoryPressure;
use Pam\Native\Modules\NativeModuleResult;
use Pam\Native\Modules\NativeModules;
use Pam\Native\NodeKind;
use Pam\Native\PointerEvents;
use Pam\Native\PositionType;
use Pam\Native\Plugin\PluginManager;
use Pam\Native\Plugin\PluginException;
use Pam\Native\PropKey;
use Pam\Native\Restorable;
use Pam\Native\State;
use Pam\Native\Style;
use Pam\Native\TemplateRegistry;
use Pam\Native\TextDecoration;
use Pam\Native\TextTransform;
use Pam\Native\Theme;
use Pam\Native\View;
use Pam\Native\WindowMetrics;
use Pam\Native\UI\Button;
use Pam\Native\UI\Column;
use Pam\Native\UI\CustomView;
use Pam\Native\UI\Input;
use Pam\Native\UI\Pressable;
use Pam\Native\UI\Screen;
use Pam\Native\UI\Text;
use Pam\Native\Tests\Fixtures\ExamplePluginProvider;

spl_autoload_register(static function (string $class): void {
    $prefix = 'Pam\\Native\\';

    if (!str_starts_with($class, $prefix)) {
        return;
    }

    $path = __DIR__.'/../src/'.str_replace('\\', '/', substr($class, strlen($prefix))).'.php';

    if (is_file($path)) {
        require $path;
    }
});

require __DIR__.'/Fixtures/ExamplePluginProvider.php';

final class TestDiagnostics
{
    /** @var list<string> */
    public static array $messages = [];

    /** @var array{requestId: int, module: string, method: string, payload: string}|null */
    public static ?array $moduleCall = null;
}

if (!function_exists('pam_native_error')) {
    function pam_native_error(string $message): void
    {
        TestDiagnostics::$messages[] = $message;
    }
}

if (!function_exists('pam_native_call')) {
    function pam_native_call(
        int $requestId,
        string $module,
        string $method,
        string $payload,
    ): void {
        TestDiagnostics::$moduleCall = [
            'requestId' => $requestId,
            'module' => $module,
            'method' => $method,
            'payload' => $payload,
        ];
    }
}

$stateDirectory = sys_get_temp_dir().'/pam-native-state-tests-'.getmypid();
putenv('PAM_NATIVE_STATE_DIR='.$stateDirectory);
if (is_file($stateDirectory.'/state.json')) {
    unlink($stateDirectory.'/state.json');
}

$assert = static function (bool $condition, string $message): void {
    if (!$condition) {
        throw new RuntimeException($message);
    }
};

foreach (NodeKind::cases() as $index => $kind) {
    $assert($kind->value === $index + 1, 'Node kinds must remain sequential protocol integers.');
}
foreach (PropKey::cases() as $index => $key) {
    $assert($key->value === $index + 1, 'Property keys must remain sequential protocol integers.');
}
foreach (EventKind::cases() as $index => $kind) {
    $assert($kind->value === $index + 1, 'Event kinds must remain sequential protocol integers.');
}
foreach ([
    AnimationKind::cases(),
    FontStyle::cases(),
    PointerEvents::cases(),
    PositionType::cases(),
    TextDecoration::cases(),
    TextTransform::cases(),
] as $cases) {
    foreach ($cases as $index => $case) {
        $assert(
            $case->value === $index + 1,
            $case::class.' values must remain sequential protocol integers.',
        );
    }
}

$tree = Screen::make(
    Column::make(
        Text::make('Pam Native')->key('title'),
        Button::make('Tap')->onPress(static function (): void {
        }),
        Input::make('value')->placeholder('Type here'),
    )->style(new Style(padding: 16.0, gap: 8.0)),
);

$first = (new TreeEncoder())->encode($tree);
$second = (new TreeEncoder())->encode($tree);
$firstFrame = $first['frame'];
$secondFrame = $second['frame'];

if ($firstFrame === null || $secondFrame === null) {
    throw new RuntimeException('Fresh encoders must produce complete tree frames.');
}

$assert($firstFrame === $secondFrame, 'Tree encoding must be deterministic.');
$assert(str_starts_with($firstFrame, 'PNT1'), 'Tree frame magic is missing.');
$assert(count($first['callbacks']) === 1, 'Event callback was not registered.');

$nativeContainer = CustomView::make(
    'community.container',
    ['axis' => 1],
    Text::make('Native child'),
);
$assert(
    count($nativeContainer->children()) === 1,
    'Custom native view containers must retain declarative children.',
);

$capturedParentVariants = null;
TemplateRegistry::reset();
TemplateRegistry::component(
    'VariantParent',
    static fn (array $props, array $children): \Pam\Native\Renderable =>
        Column::make(...$children),
);
TemplateRegistry::component(
    'VariantChild',
    static function (array $props) use (&$capturedParentVariants): \Pam\Native\Renderable {
        $capturedParentVariants = $props['__parentVariants'] ?? null;

        return Text::make('Child');
    },
);
$variantTemplate = TemplateCompiler::compile(
    '<VariantParent variant="outline" size="lg"><VariantChild /></VariantParent>',
);
TemplateRenderer::render($variantTemplate, null, []);
$assert(
    $capturedParentVariants === ['variant' => 'outline', 'size' => 'lg'],
    'Custom template components must inherit parent variant context.',
);
TemplateRegistry::reset();
TemplateRegistry::styleResolver(
    static fn (string $class): ?array => $class === 'w-1/2'
        ? [PropKey::WidthPercent->value => 50.0]
        : null,
);
$assert(
    TemplateRegistry::classProperties('w-1/2')
        === [PropKey::WidthPercent->value => 50.0],
    'Plugin utility resolvers must compile classes lazily.',
);
$assert(
    TemplateRegistry::classProperties('unknown-utility') === null,
    'Plugin utility resolvers must delegate unsupported classes.',
);
TemplateRegistry::reset();

$incremental = new TreeEncoder();
$initial = $incremental->encode(Text::make('A')->key('value'));
$unchanged = $incremental->encode(Text::make('A')->key('value'));
$patch = $incremental->encode(Text::make('B')->key('value'));
$initialFrame = $initial['frame'];
$patchFrame = $patch['frame'];

if ($initialFrame === null || $patchFrame === null) {
    throw new RuntimeException('Initial and changed trees must produce frames.');
}

$assert(str_starts_with($initialFrame, 'PNT1'), 'Initial tree frame is missing.');
$assert($unchanged['frame'] === null, 'An unchanged tree must not cross the native bridge.');
$assert(str_starts_with($patchFrame, 'PNP1'), 'Property update must use a patch frame.');
$assert(strlen($patchFrame) < strlen($initialFrame), 'Property patch must be smaller than the full tree.');

$structural = $incremental->encode(
    Column::make(
        Text::make('B')->key('value'),
        Text::make('New')->key('new'),
    )->key('content'),
);
$assert(
    $structural['frame'] !== null && str_starts_with($structural['frame'], 'PNP1'),
    'Structural changes after boot must use a patch frame.',
);

$wire = Wire::map([
    'name' => 'Pam',
    'count' => 42,
    'ratio' => 1.5,
    'enabled' => true,
]);
$assert(Wire::decodeMap($wire) === [
    'name' => 'Pam',
    'count' => 42,
    'ratio' => 1.5,
    'enabled' => true,
], 'Wire map round-trip failed.');

$counter = new class extends Component {
    public int $count = 0;

    public function render(): \Pam\Native\Element
    {
        return Screen::make(
            Button::make("Count: {$this->count}")
                ->key('counter')
                ->onPress(function (): void {
                    $this->count++;
                }),
        );
    }
};

App::run($counter);
$frame = Runtime::lastFrame();
$assert($frame !== null, 'App did not render its initial frame.');
$moduleResult = null;
$requestId = NativeModules::call(
    'community.echo',
    'echo',
    ['message' => 'fast'],
    static function (NativeModuleResult $result) use (&$moduleResult): void {
        $moduleResult = $result;
    },
);
$assert(
    TestDiagnostics::$moduleCall !== null
        && TestDiagnostics::$moduleCall['requestId'] === $requestId
        && TestDiagnostics::$moduleCall['module'] === 'community.echo'
        && Wire::decodeMap(TestDiagnostics::$moduleCall['payload']) === ['message' => 'fast'],
    'Public native module facade did not emit a typed bridge call.',
);
Runtime::dispatchModuleResult(
    $requestId,
    \Pam\Native\ModuleResultStatus::Success->value,
    Wire::map(['message' => 'fast']),
);
$assert(
    $moduleResult instanceof NativeModuleResult
        && $moduleResult->succeeded()
        && $moduleResult->values() === ['message' => 'fast'],
    'Public native module facade did not decode its result.',
);
$encoded = (new TreeEncoder())->encode($counter->render());
$eventKey = array_key_first($encoded['callbacks']);

if ($eventKey === null) {
    throw new RuntimeException('Counter callback is missing.');
}

[$nodeId, $eventKind] = array_map('intval', explode(':', $eventKey));
Runtime::dispatchEvent($nodeId, $eventKind, '');
$assert($counter->count === 1, 'Event did not update component state.');
$assert(Runtime::lastFrame() !== $frame, 'State update did not produce a new frame.');
Runtime::shutdown();

$lifecycleState = null;
$windowMetrics = null;
$memoryPressure = null;
App::onAppState(static function (AppState $state) use (&$lifecycleState): void {
    $lifecycleState = $state;
});
App::onDimensions(static function (WindowMetrics $metrics) use (&$windowMetrics): void {
    $windowMetrics = $metrics;
});
App::onMemoryPressure(static function (MemoryPressure $pressure) use (&$memoryPressure): void {
    $memoryPressure = $pressure;
});
App::run(Text::make('Lifecycle'));
Runtime::dispatchEvent(0, EventKind::AppState->value, (string) AppState::Background->value);
Runtime::dispatchEvent(0, EventKind::Dimensions->value, Wire::map([
    'width' => 412.0,
    'height' => 915.0,
    'density' => 3.0,
]));
Runtime::dispatchEvent(
    0,
    EventKind::MemoryPressure->value,
    (string) MemoryPressure::Critical->value,
);
$assert($lifecycleState === AppState::Background, 'App-state lifecycle event was not decoded.');
$assert(
    $windowMetrics instanceof WindowMetrics
        && $windowMetrics->width === 412.0
        && $windowMetrics->height === 915.0
        && $windowMetrics->density === 3.0,
    'Window metrics lifecycle event was not decoded.',
);
$assert(
    $memoryPressure === MemoryPressure::Critical,
    'Memory-pressure lifecycle event was not decoded.',
);
Runtime::shutdown();

State::set('test.value', ['count' => 7]);
State::resetCache();
$assert(State::get('test.value') === ['count' => 7], 'Atomic state persistence failed.');

$restorable = new class extends Component implements Restorable {
    public int $count = 0;

    public function stateKey(): string
    {
        return 'tests.counter';
    }

    public function restoreState(array $state): void
    {
        $this->count = is_int($state['count'] ?? null) ? $state['count'] : 0;
    }

    public function saveState(): array
    {
        return ['count' => $this->count];
    }

    public function render(): \Pam\Native\Element
    {
        return Button::make((string) $this->count)
            ->key('restorable')
            ->onPress(function (): void {
                $this->count++;
            });
    }
};
App::run($restorable);
$restorableFrame = (new TreeEncoder())->encode($restorable->render());
$restorableEvent = array_key_first($restorableFrame['callbacks']);
if ($restorableEvent === null) {
    throw new RuntimeException('Restorable component callback is missing.');
}
[$restorableNode, $restorableKind] = array_map('intval', explode(':', $restorableEvent));
Runtime::dispatchEvent($restorableNode, $restorableKind, '');
$assert($restorable->count === 1, 'Restorable component did not update.');
Runtime::shutdown();
State::resetCache();

$restored = new class extends Component implements Restorable {
    public int $count = 0;

    public function stateKey(): string
    {
        return 'tests.counter';
    }

    public function restoreState(array $state): void
    {
        $this->count = is_int($state['count'] ?? null) ? $state['count'] : 0;
    }

    public function saveState(): array
    {
        return ['count' => $this->count];
    }

    public function render(): \Pam\Native\Element
    {
        return Text::make((string) $this->count);
    }
};
App::run($restored);
$assert($restored->count === 1, 'Component state was not restored after runtime restart.');
Runtime::shutdown();

$templateDirectory = sys_get_temp_dir().'/pam-native-template-tests';

if (!is_dir($templateDirectory) && !mkdir($templateDirectory, 0o755, true) && !is_dir($templateDirectory)) {
    throw new RuntimeException('Cannot create the template test directory.');
}

file_put_contents(
    $templateDirectory.'/counter.pam',
    <<<'PAM'
<Screen>
    <Column class="p-4 gap-2 bg-white">
        <Text key="greeting">Hello, {{ $name }}</Text>
        <Input model="name" placeholder="Name" sync="debounced" />
        <Button key="increment" on:press="increment">Count: {{ $count }}</Button>
        <If condition="$details">
            <Text key="details">Template details</Text>
        </If>
    </Column>
</Screen>
PAM,
);
file_put_contents(
    $templateDirectory.'/panel.pam',
    <<<'PAM'
<Column class="card gap-2">
    <Text key="panel-title">{{ $props.title }}</Text>
    <Slot>
        <Text>Fallback</Text>
    </Slot>
</Column>
PAM,
);
file_put_contents(
    $templateDirectory.'/slots.pam',
    <<<'PAM'
<Screen>
    <Panel title="Metrics">
        <Text key="slot-content">Fast native content</Text>
    </Panel>
</Screen>
PAM,
);
file_put_contents(
    $templateDirectory.'/override.pam',
    <<<'PAM'
<Button>
    <Text>Plugin child</Text>
</Button>
PAM,
);
App::views($templateDirectory, $templateDirectory.'/cache');
App::theme(Theme::pamLab());
App::component('Panel', 'panel');
$assert(
    TemplateRegistry::classProperties('card') !== null,
    'Theme class tokens were not registered.',
);

$template = new class extends Component {
    private string $name = 'PHP';
    private int $count = 0;
    private bool $details = true;

    public function render(): View
    {
        return View::make('counter');
    }

    public function increment(): void
    {
        $this->count++;
    }

    public function count(): int
    {
        return $this->count;
    }

    /** @return array{name: string, details: bool} */
    public function templateState(): array
    {
        return ['name' => $this->name, 'details' => $this->details];
    }
};

App::run($template);
$templateRoot = $template->toElement();
$templateFrame = Runtime::lastFrame();
$templateEncoded = (new TreeEncoder())->encode($templateRoot);
$assert($templateFrame !== null, 'Tag template did not produce a native frame.');
$assert(count($templateEncoded['callbacks']) === 2, 'Template model and press callbacks are missing.');
$pressCallback = array_key_first(array_filter(
    $templateEncoded['callbacks'],
    static fn (string $key): bool => str_ends_with($key, ':1'),
    ARRAY_FILTER_USE_KEY,
));

if ($pressCallback === null) {
    throw new RuntimeException('Template press callback is missing.');
}

[$templateNode, $templateEvent] = array_map('intval', explode(':', $pressCallback));
Runtime::dispatchEvent($templateNode, $templateEvent, '');
$assert($template->count() === 1, 'Template event did not invoke its component method.');
Runtime::shutdown();

$slotView = View::make('slots')->toElement();
$assert(
    $slotView->children() !== [],
    'Component props and slots did not render native children.',
);

TemplateRegistry::component(
    'Button',
    static fn (array $_props, array $children, ?object $_scope): Pressable => Pressable::make(...$children),
);
$overriddenButton = View::make('override')->toElement();
$assert(
    $overriddenButton->kind() === NodeKind::Pressable
        && count($overriddenButton->children()) === 1,
    'Plugin tags must be able to override core tags and receive element children.',
);

$failing = new class extends Component {
    public function render(): \Pam\Native\Element
    {
        return Button::make('Fail')->onPress(static function (): void {
            throw new RuntimeException('Expected recoverable PHP error.');
        });
    }
};
App::run($failing);
$failingFrame = (new TreeEncoder())->encode($failing->render());
$failingEvent = array_key_first($failingFrame['callbacks']);
if ($failingEvent === null) {
    throw new RuntimeException('Failing callback is missing.');
}
[$failingNode, $failingKind] = array_map('intval', explode(':', $failingEvent));
Runtime::dispatchEvent($failingNode, $failingKind, '');
$assert(
    isset(TestDiagnostics::$messages[0])
        && str_starts_with(TestDiagnostics::$messages[0], "PAMERR1\n")
        && str_contains(TestDiagnostics::$messages[0], 'Expected recoverable PHP error.'),
    'Recoverable PHP errors must emit structured diagnostics.',
);
$assert(Runtime::lastFrame() !== null, 'A PHP callback error stopped the runtime.');
Runtime::shutdown();

$pluginProject = sys_get_temp_dir().'/pam-native-plugin-tests-'.getmypid();
$pluginPackage = $pluginProject.'/vendor/community/fixture';

if (
    !is_dir($pluginPackage)
    && !mkdir($pluginPackage, 0o755, true)
    && !is_dir($pluginPackage)
) {
    throw new RuntimeException('Cannot create the plugin fixture package.');
}
if (
    !is_dir($pluginProject.'/vendor/composer')
    && !mkdir($pluginProject.'/vendor/composer', 0o755, true)
    && !is_dir($pluginProject.'/vendor/composer')
) {
    throw new RuntimeException('Cannot create the Composer fixture directory.');
}

file_put_contents(
    $pluginProject.'/vendor/composer/installed.json',
    <<<'JSON'
{
    "packages": [
        {
            "name": "community/fixture",
            "version": "1.0.0",
            "install-path": "../community/fixture",
            "extra": {
                "pam-native": {
                    "plugin": "pam-native.plugin.json"
                }
            }
        }
    ]
}
JSON,
);
file_put_contents(
    $pluginPackage.'/pam-native.plugin.json',
    <<<'JSON'
{
    "version": 1,
    "protocol": 1,
    "pamNative": {
        "minimum": "0.1.0",
        "maximumExclusive": "0.2.0"
    },
    "php": {
        "provider": "Pam\\Native\\Tests\\Fixtures\\ExamplePluginProvider"
    }
}
JSON,
);

PluginManager::reset();
PluginManager::boot($pluginProject);
$assert(
    ExamplePluginProvider::$registered === 1
        && ExamplePluginProvider::$booted === 1
        && TemplateRegistry::factory('FixtureTag') !== null,
    'Composer plugin provider was not registered and booted exactly once.',
);
PluginManager::boot($pluginProject);
$assert(
    ExamplePluginProvider::$registered === 1 && ExamplePluginProvider::$booted === 1,
    'Plugin providers must be idempotent within a runtime.',
);
PluginManager::reset();
file_put_contents(
    $pluginProject.'/vendor/composer/installed.json',
    <<<'JSON'
{
    "packages": [
        {
            "name": "community/fixture",
            "version": "1.0.0",
            "install-path": "../community/fixture",
            "extra": {
                "pam-native": {
                    "plugin": "../outside.json"
                }
            }
        }
    ]
}
JSON,
);
$unsafeDescriptorRejected = false;

try {
    PluginManager::discover($pluginProject);
} catch (PluginException) {
    $unsafeDescriptorRejected = true;
}

$assert($unsafeDescriptorRejected, 'Plugin descriptor traversal must be rejected.');

echo "Pam Native PHP SDK tests passed.\n";
