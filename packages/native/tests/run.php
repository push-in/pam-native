<?php

declare(strict_types=1);

use Pam\Native\App;
use Pam\Native\AppState;
use Pam\Native\AccessibilityRole;
use Pam\Native\AccessibilityCheckedState;
use Pam\Native\AccessibilityImportance;
use Pam\Native\AccessibilityLiveRegion;
use Pam\Native\ActivityIndicatorSize;
use Pam\Native\AnimationKind;
use Pam\Native\AnimationKeyframe;
use Pam\Native\BottomSheetKeyboardBehavior;
use Pam\Native\AsyncStatus;
use Pam\Native\AsyncValue;
use Pam\Native\Component;
use Pam\Native\Contact;
use Pam\Native\CaptureType;
use Pam\Native\DrawingMode;
use Pam\Native\EventKind;
use Pam\Native\FileReference;
use Pam\Native\FontStyle;
use Pam\Native\GestureComposition;
use Pam\Native\GestureDirection;
use Pam\Native\GestureEvent;
use Pam\Native\GestureState;
use Pam\Native\GestureType;
use Pam\Native\MediaType;
use Pam\Native\MediaAlbum;
use Pam\Native\MediaAsset;
use Pam\Native\MediaAssetPage;
use Pam\Native\MediaPickerType;
use Pam\Native\MediaCachePolicy;
use Pam\Native\MediaPriority;
use Pam\Native\NativeMenuItem;
use Pam\Native\PermissionDecision;
use Pam\Native\PermissionKind;
use Pam\Native\PermissionStatus;
use Pam\Native\PushEventType;
use Pam\Native\PushProvider;
use Pam\Native\ImageCachePolicy;
use Pam\Native\ImageErrorEvent;
use Pam\Native\ImageFit;
use Pam\Native\ImageLoadEvent;
use Pam\Native\ImageProgressEvent;
use Pam\Native\ImageResizeMethod;
use Pam\Native\InputAutoCapitalize;
use Pam\Native\InputAutofillImportance;
use Pam\Native\InputContentSizeEvent;
use Pam\Native\InputKeyEvent;
use Pam\Native\InputMode;
use Pam\Native\InputSelectionEvent;
use Pam\Native\InputSubmitBehavior;
use Pam\Native\InputTextAlignVertical;
use Pam\Native\KeyboardType;
use Pam\Native\IncomingShare;
use Pam\Native\Internal\Runtime;
use Pam\Native\Internal\PamPhpCompiler;
use Pam\Native\Internal\CssColor;
use Pam\Native\Internal\CompiledTemplateNode;
use Pam\Native\Internal\ScopedStyleCompiler;
use Pam\Native\Internal\ComponentLifecycle;
use Pam\Native\Internal\TemplateCompiler;
use Pam\Native\Internal\TemplateRenderer;
use Pam\Native\Internal\TreeEncoder;
use Pam\Native\Internal\Wire;
use Pam\Native\Tooling\PamFormatter;
use Pam\Native\KeyboardAvoidingBehavior;
use Pam\Native\MemoryPressure;
use Pam\Native\MotionPreset;
use Pam\Native\HapticFeedback;
use Pam\Native\Http\Http;
use Pam\Native\Http\HttpResponse;
use Pam\Native\Database\SQLite;
use Pam\Native\NativeOperation;
use Pam\Native\Forms\FormStatus;
use Pam\Native\Forms\NativeForm;
use Pam\Native\Forms\Attributes\Email;
use Pam\Native\Forms\Attributes\Matches;
use Pam\Native\Forms\Attributes\MaxLength;
use Pam\Native\Forms\Attributes\MinLength;
use Pam\Native\Forms\Attributes\Required;
use Pam\Native\Navigation\NavigationOperation;
use Pam\Native\Navigation\NavigationTransition;
use Pam\Native\Navigation\Navigator;
use Pam\Native\Navigation\Router;
use Pam\Native\Navigation\RouteContext;
use Pam\Native\Navigation\TabNavigator;
use Pam\Native\Navigation\TabPresentation;
use Pam\Native\ModalAnimationType;
use Pam\Native\ModalOrientation;
use Pam\Native\ModalPresentation;
use Pam\Native\Modules\NativeModuleResult;
use Pam\Native\Modules\NativeModules;
use Pam\Native\ModuleResultStatus;
use Pam\Native\NodeKind;
use Pam\Native\PointerEvents;
use Pam\Native\PressEvent;
use Pam\Native\ReturnKeyType;
use Pam\Native\RefreshIndicatorSize;
use Pam\Native\SafeAreaMode;
use Pam\Native\ScrollKeyboardDismissMode;
use Pam\Native\ScrollOverScrollMode;
use Pam\Native\PositionType;
use Pam\Native\Plugin\PluginManager;
use Pam\Native\Plugin\PluginException;
use Pam\Native\PropKey;
use Pam\Native\Restorable;
use Pam\Native\State;
use Pam\Native\Store\Attributes\Computed as StoreComputed;
use Pam\Native\Store\ActionPolicy;
use Pam\Native\System\MediaCapture;
use Pam\Native\Store\Store;
use Pam\Native\Store\StoreChangeKind;
use Pam\Native\Store\StoreMiddleware;
use Pam\Native\Store\Stores;
use Pam\Native\StatusBarAppearance;
use Pam\Native\System\Haptics;
use Pam\Native\System\IncomingShares;
use Pam\Native\System\Caches;
use Pam\Native\System\Clipboard;
use Pam\Native\System\Contacts;
use Pam\Native\System\DeviceInfo;
use Pam\Native\System\Sensors;
use Pam\Native\System\Location;
use Pam\Native\System\Files;
use Pam\Native\System\MediaLibrary;
use Pam\Native\LocationPosition;
use Pam\Native\System\AudioRecorder;
use Pam\Native\Storage\Storage;
use Pam\Native\AudioRecording;
use Pam\Native\SensorType;
use Pam\Native\Style;
use Pam\Native\TemplateRegistry;
use Pam\Native\TextDecoration;
use Pam\Native\TextBreakStrategy;
use Pam\Native\TextDataDetectorType;
use Pam\Native\TextEllipsizeMode;
use Pam\Native\TextHyphenationFrequency;
use Pam\Native\TextTransform;
use Pam\Native\Theme;
use Pam\Native\UserInterfaceAppearance;
use Pam\Native\View;
use Pam\Native\WindowMetrics;
use Pam\Native\UI\Button;
use Pam\Native\UI\BottomSheet;
use Pam\Native\UI\GestureDetector;
use Pam\Native\UI\ActivityIndicator;
use Pam\Native\UI\Animated;
use Pam\Native\UI\Column;
use Pam\Native\UI\CustomView;
use Pam\Native\UI\DrawingCanvas;
use Pam\Native\UI\FlatList;
use Pam\Native\UI\Input;
use Pam\Native\UI\InteractionRegion;
use Pam\Native\UI\Image;
use Pam\Native\UI\ImageBackground;
use Pam\Native\UI\KeyboardAvoidingView;
use Pam\Native\UI\Modal;
use Pam\Native\UI\MediaPlayer;
use Pam\Native\UI\Pressable;
use Pam\Native\UI\RefreshControl;
use Pam\Native\UI\SafeAreaView;
use Pam\Native\UI\Screen;
use Pam\Native\UI\Scroll;
use Pam\Native\UI\SectionList;
use Pam\Native\UI\StatusBar;
use Pam\Native\UI\Text;
use Pam\Native\UI\Toggle;
use Pam\Native\UI\VirtualGrid;
use Pam\Native\UI\VirtualizedList;
use Pam\Native\UI\WebView;
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

    /** @var array{requestId: int, operation: int, payload: string}|null */
    public static ?array $typedCall = null;
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

if (!function_exists('pam_native_call_typed')) {
    function pam_native_call_typed(
        int $requestId,
        int $operation,
        string $payload,
    ): void {
        TestDiagnostics::$typedCall = [
            'requestId' => $requestId,
            'operation' => $operation,
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

$assert(
    CssColor::parse('transparent') === 0x00000000
        && CssColor::parse('#123') === 0xFF112233
        && CssColor::parse('#1234') === 0x44112233
        && CssColor::parse('#11223344') === 0x44112233
        && CssColor::parse('rgb(255 0 128 / 50%)') === 0x80FF0080
        && CssColor::parse('hsl(120deg 100% 25%)') === 0xFF008000
        && CssColor::parse('rebeccapurple') === 0xFF663399,
    'CSS colors must support transparent, modern hex, functions and named colors.',
);

$compiledCss = ScopedStyleCompiler::compile(
    <<<'CSS'
    :root {
        --font-ui: "asset://assets/fonts/SpaceGrotesk-Regular.ttf";
        --ink: #101410;
    }

    @font-face {
        font-family: "Space Grotesk";
        src: url(var(--font-ui));
        font-weight: 400;
        font-style: normal;
    }

    Text, .body-copy {
        color: var(--ink);
        font-family: var(--font-ui);
        text-decoration: underline;
    }

    .card {
        width: 100%;
        height: 50%;
        max-width: 92%;
        padding: 8px 16px 12px;
        margin: 1px 2px 3px 4px;
        border: 1.5px solid #101410;
        border-bottom: 2px solid #101410;
        border-left: 3px solid #101410;
        border-left-color: #D6ECDD;
        border-radius: 28px 28px 0 0;
        inset: 5px 6px 7px 8px;
        flex-wrap: wrap;
        translation-y: 28px;
    }

    .percent-position {
        left: 4%;
        top: 10%;
        right: 5%;
        bottom: 2%;
    }
    CSS,
    'ScopedStyleCompilerTest.pam.php',
);
$assert(
    $compiledCss['tags']['Text']['textColor'] === 0xFF101410
        && $compiledCss['classes']['body-copy']['fontFamily']
            === 'asset://assets/fonts/SpaceGrotesk-Regular.ttf'
        && $compiledCss['classes']['body-copy']['textDecoration'] === 'underline',
    'Scoped CSS must resolve variables in tag and class rules.',
);
$assert(
    $compiledCss['fonts']['Space Grotesk'][0] === [
        'source' => 'asset://assets/fonts/SpaceGrotesk-Regular.ttf',
        'weight' => '400',
        'style' => 'normal',
    ],
    'Scoped CSS must compile packaged @font-face aliases without a runtime registry.',
);
$assert(
    $compiledCss['classes']['card'] === [
        'widthPercent' => '100',
        'heightPercent' => '50',
        'maxWidthPercent' => '92',
        'paddingTop' => '8',
        'paddingRight' => '16',
        'paddingBottom' => '12',
        'paddingLeft' => '16',
        'marginTop' => '1',
        'marginRight' => '2',
        'marginBottom' => '3',
        'marginLeft' => '4',
        'borderWidth' => '1.5',
        'borderColor' => 0xFF101410,
        'borderBottomWidth' => '2',
        'borderLeftWidth' => '3',
        'borderColor' => 0xFFD6ECDD,
        'borderTopLeftRadius' => '28',
        'borderTopRightRadius' => '28',
        'borderBottomRightRadius' => '0',
        'borderBottomLeftRadius' => '0',
        'top' => '5',
        'right' => '6',
        'bottom' => '7',
        'left' => '8',
        'flexWrap' => 'wrap',
        'translationY' => '28',
    ],
    'Scoped CSS must compile percentages and native box shorthands exactly.',
);
$assert(
    $compiledCss['classes']['percent-position'] === [
        'leftPercent' => '4',
        'topPercent' => '10',
        'rightPercent' => '5',
        'bottomPercent' => '2',
    ],
    'Scoped CSS must compile percentage position offsets exactly.',
);
$forwardVariableCss = ScopedStyleCompiler::compile(
    '.label { color: var(--label); } :root { --label: #334455; }',
    'ForwardVariable.pam.php',
);
$assert(
    $forwardVariableCss['classes']['label']['textColor'] === 0xFF334455,
    'Scoped CSS variables must be independent from declaration order.',
);
$modernCss = ScopedStyleCompiler::compile(
    <<<'CSS'
    :root {
        --fallback-ink: var(--missing-ink, rgb(12 34 56 / 75%));
        --space: 0.5rem;
    }

    Text { color: red; }
    .later { color: #1234; }
    .earlier { color: hsl(120 100% 25%); }
    .later {
        color: var(--fallback-ink);
        background: none;
        aspect-ratio: 16 / 9;
        padding-inline: var(--space) 1rem;
        margin-block: 2px 3px;
        inset-inline: 4px 5px;
        border: none;
        font-weight: bold;
        font-family: "Space Grotesk", sans-serif;
        opacity: 50%;
        transform: translateX(3px) translateY(-2dp) scale(1.25) rotate(0.5turn);
        object-fit: scale-down;
        visibility: visible;
        box-sizing: border-box;
    }
    CSS,
    'ModernCss.pam.php',
);
$assert(
    $modernCss['classes']['later'] === [
        'textColor' => 0xBF0C2238,
        'backgroundColor' => 0,
        'aspectRatio' => (string) (16 / 9),
        'paddingLeft' => '8',
        'paddingRight' => '16',
        'marginTop' => '2',
        'marginBottom' => '3',
        'left' => '4',
        'right' => '5',
        'borderWidth' => '0',
        'borderColor' => 0,
        'fontWeight' => '700',
        'fontFamily' => 'Space Grotesk',
        'opacity' => '0.5',
        'translationX' => '3',
        'translationY' => '-2',
        'scaleX' => '1.25',
        'scaleY' => '1.25',
        'rotation' => '180',
        'resizeMode' => 'contain',
        'visible' => true,
    ],
    'Modern scoped CSS values and native-safe shorthands must compile exactly.',
);
$assert(
    $modernCss['classCascade']['earlier']['textColor']['order'] === 2
        && $modernCss['classCascade']['later']['textColor']['order'] === 3,
    'Scoped CSS must index property source order for a deterministic constant-time cascade.',
);
$cyclicVariableRejected = false;
try {
    ScopedStyleCompiler::compile(
        ':root { --a: var(--b); --b: var(--a); } Text { color: var(--a); }',
        'CyclicVariables.pam.php',
    );
} catch (RuntimeException $error) {
    $cyclicVariableRejected = str_contains($error->getMessage(), 'Circular CSS variable');
}
$assert(
    $cyclicVariableRejected,
    'Scoped CSS must reject custom-property cycles during compilation.',
);

$cascadeTemplate = TemplateCompiler::compile(
    '<Column><Text class="later earlier">Styled</Text></Column>',
);
$cascadeStyled = new CompiledTemplateNode(
    kind: $cascadeTemplate->kind,
    name: $cascadeTemplate->name,
    attributes: [
        ...$cascadeTemplate->attributes,
        '__pamStyles' => json_encode($modernCss, JSON_THROW_ON_ERROR),
    ],
    source: $cascadeTemplate->source,
    line: $cascadeTemplate->line,
    column: $cascadeTemplate->column,
    value: $cascadeTemplate->value,
);
$cascadeStyled->children = $cascadeTemplate->children;
$cascadeElement = TemplateRenderer::render($cascadeStyled, null, []);
$cascadeText = $cascadeElement->children()[0];
$assert(
    $cascadeText->properties()[PropKey::TextColor->value] === 0xBF0C2238,
    'CSS class order in markup must not override stylesheet source order.',
);

$inheritCss = ScopedStyleCompiler::compile(
    <<<'CSS'
    @font-face {
        font-family: "Theme";
        src: url("asset://assets/fonts/Theme-Regular.ttf");
        font-weight: 400;
    }
    @font-face {
        font-family: "Theme";
        src: url("asset://assets/fonts/Theme-Bold.ttf");
        font-weight: 700;
    }
    .theme {
        color: #123;
        font-family: "Theme";
        font-size: 1rem;
        font-weight: normal;
        text-align: center;
    }
    .strong { font-weight: bold; }
    CSS,
    'InheritedCss.pam.php',
);
$inheritTemplate = TemplateCompiler::compile(
    '<Column class="theme"><Row><Text class="strong">Inherited</Text></Row></Column>',
);
$inheritStyled = new CompiledTemplateNode(
    kind: $inheritTemplate->kind,
    name: $inheritTemplate->name,
    attributes: [
        ...$inheritTemplate->attributes,
        '__pamStyles' => json_encode($inheritCss, JSON_THROW_ON_ERROR),
    ],
    source: $inheritTemplate->source,
    line: $inheritTemplate->line,
    column: $inheritTemplate->column,
    value: $inheritTemplate->value,
);
$inheritStyled->children = $inheritTemplate->children;
$inheritText = TemplateRenderer::render($inheritStyled, null, [])
    ->children()[0]
    ->children()[0];
$assert(
    $inheritText->properties()[PropKey::TextColor->value] === 0xFF112233
        && $inheritText->properties()[PropKey::FontSize->value] === 16
        && $inheritText->properties()[PropKey::FontFamily->value]
            === 'asset://assets/fonts/Theme-Bold.ttf'
        && !isset($inheritText->properties()[PropKey::FontWeight->value])
        && $inheritText->properties()[PropKey::TextAlign->value] === 2,
    'Text CSS properties and logical font families must inherit through native layout containers.',
);
$textAlignmentCss = ScopedStyleCompiler::compile(
    '.left { text-align: left; } .right { text-align: right; }',
    'TextAlignmentCss.pam.php',
);
$textAlignmentTemplate = TemplateCompiler::compile(
    '<Column><Text class="left">Left</Text><Text class="right">Right</Text></Column>',
);
$textAlignmentStyled = new CompiledTemplateNode(
    kind: $textAlignmentTemplate->kind,
    name: $textAlignmentTemplate->name,
    attributes: [
        ...$textAlignmentTemplate->attributes,
        '__pamStyles' => json_encode($textAlignmentCss, JSON_THROW_ON_ERROR),
    ],
    source: $textAlignmentTemplate->source,
    line: $textAlignmentTemplate->line,
    column: $textAlignmentTemplate->column,
    value: $textAlignmentTemplate->value,
);
$textAlignmentStyled->children = $textAlignmentTemplate->children;
$textAlignmentChildren = TemplateRenderer::render($textAlignmentStyled, null, [])
    ->children();
$assert(
    $textAlignmentChildren[0]->properties()[PropKey::TextAlign->value]
        === \Pam\Native\TextAlignment::Start->value
        && $textAlignmentChildren[1]->properties()[PropKey::TextAlign->value]
        === \Pam\Native\TextAlignment::End->value,
    'CSS text-align must accept the web left/right aliases.',
);
$cssImportRoot = sys_get_temp_dir().'/pam-native-css-import-'.getmypid();
mkdir($cssImportRoot.'/src/styles', 0o755, true);
mkdir($cssImportRoot.'/src/screens', 0o755, true);
file_put_contents($cssImportRoot.'/composer.json', "{}\n");
file_put_contents(
    $cssImportRoot.'/src/styles/tokens.css',
    ":root { --brand-ink: #163D2A; }\n",
);
file_put_contents(
    $cssImportRoot.'/src/styles/brand.css',
    <<<'CSS'
@import "./tokens.css";

@font-face {
    font-family: "Brand";
    src: url("asset://assets/fonts/Brand-Regular.ttf");
    font-weight: 400;
}

Text {
    color: var(--brand-ink);
    font-family: "Brand";
}
CSS,
);
$cssImportComponent = $cssImportRoot.'/src/screens/Profile.pam.php';
file_put_contents($cssImportComponent, "<?php\n");
$importedCss = ScopedStyleCompiler::compile(
    '@import "../styles/brand.css"; Text { font-size: 15px; }',
    $cssImportComponent,
);
$assert(
    $importedCss['tags']['Text']['textColor'] === 0xFF163D2A
        && $importedCss['tags']['Text']['fontFamily']
            === 'Brand'
        && $importedCss['tags']['Text']['fontSize'] === '15'
        && $importedCss['fonts']['Brand'][0]['source']
            === 'asset://assets/fonts/Brand-Regular.ttf',
    'Scoped CSS imports must inline nested project styles and preserve local cascade order.',
);
$formattedImportPam = PamFormatter::format(
    <<<'PAM'
<?php
?>
<template><Text>Brand</Text></template><style scoped>@import url('../styles/brand.css'); Text { font-size: 15px; }</style>
PAM,
    $cssImportComponent,
);
$assert(
    str_contains($formattedImportPam, '    @import "../styles/brand.css";')
        && PamFormatter::format($formattedImportPam, $cssImportComponent)
            === $formattedImportPam,
    'PAM formatter must normalize and preserve compile-time CSS imports.',
);
$outsideCss = sys_get_temp_dir().'/pam-native-css-outside-'.getmypid().'.css';
file_put_contents($outsideCss, "Text { color: #FF0000; }\n");
$outsideImportRejected = false;
try {
    ScopedStyleCompiler::compile(
        '@import "../../../'.basename($outsideCss).'";',
        $cssImportComponent,
    );
} catch (RuntimeException $error) {
    $outsideImportRejected = str_contains($error->getMessage(), 'outside the project');
}
$assert(
    $outsideImportRejected,
    'Scoped CSS imports must not escape the Composer project root.',
);
file_put_contents(
    $cssImportComponent,
    <<<'PAM'
<?php

declare(strict_types=1);

namespace Pam\Native\Tests\CssImport;

use Pam\Native\Component;

final class Profile extends Component
{
}
?>

<template>
    <Text>Profile</Text>
</template>

<style scoped>
    @import "../styles/brand.css";
</style>
PAM,
);
$cssImportCache = $cssImportRoot.'/.cache';
$firstImportedComponent = PamPhpCompiler::compileFile(
    $cssImportComponent,
    $cssImportCache,
);
$firstImportedStyles = json_decode(
    (string) ($firstImportedComponent->template->attributes['__pamStyles'] ?? ''),
    true,
    32,
    JSON_THROW_ON_ERROR,
);
file_put_contents(
    $cssImportRoot.'/src/styles/tokens.css',
    ":root { --brand-ink: #245E42; }\n",
);
$secondImportedComponent = PamPhpCompiler::compileFile(
    $cssImportComponent,
    $cssImportCache,
);
$secondImportedStyles = json_decode(
    (string) ($secondImportedComponent->template->attributes['__pamStyles'] ?? ''),
    true,
    32,
    JSON_THROW_ON_ERROR,
);
$assert(
    $firstImportedStyles['tags']['Text']['textColor'] === 0xFF163D2A
        && $secondImportedStyles['tags']['Text']['textColor'] === 0xFF245E42,
    'Changing an imported CSS dependency must invalidate the compiled component cache.',
);
file_put_contents(
    $cssImportRoot.'/src/app.css',
    <<<'CSS'
:root {
    --app-ink: #112A1C;
}

Text {
    color: var(--app-ink);
    font-size: 13px;
}

.shared-card {
    padding: 8px;
}
CSS,
);
file_put_contents(
    $cssImportComponent,
    <<<'PAM'
<?php

declare(strict_types=1);

namespace Pam\Native\Tests\CssImport;

use Pam\Native\Component;

final class Profile extends Component
{
}
?>

<template>
    <Column class="shared-card">
        <Text>Profile</Text>
    </Column>
</template>

<style scoped>
    .shared-card {
        padding-left: 16px;
    }

    Text {
        font-size: 15px;
    }
</style>
PAM,
);
$globalStyleComponent = PamPhpCompiler::compileFile(
    $cssImportComponent,
    $cssImportCache,
);
$globalStyles = json_decode(
    (string) ($globalStyleComponent->template->attributes['__pamStyles'] ?? ''),
    true,
    32,
    JSON_THROW_ON_ERROR,
);
$assert(
    $globalStyles['tags']['Text']['textColor'] === 0xFF112A1C
        && $globalStyles['tags']['Text']['fontSize'] === '15'
        && $globalStyles['classes']['shared-card'] === [
            'paddingTop' => '8',
            'paddingRight' => '8',
            'paddingBottom' => '8',
            'paddingLeft' => '16',
        ],
    'src/app.css must apply globally while local component CSS wins the cascade.',
);
unlink($outsideCss);
unlink($cssImportComponent);
foreach (glob($cssImportCache.'/*') ?: [] as $cacheFile) {
    unlink($cacheFile);
}
rmdir($cssImportCache);
unlink($cssImportRoot.'/src/app.css');
unlink($cssImportRoot.'/src/styles/brand.css');
unlink($cssImportRoot.'/src/styles/tokens.css');
unlink($cssImportRoot.'/composer.json');
rmdir($cssImportRoot.'/src/screens');
rmdir($cssImportRoot.'/src/styles');
rmdir($cssImportRoot.'/src');
rmdir($cssImportRoot);
$shadowStyles = ScopedStyleCompiler::compile(
    '.card { box-shadow: 3px 4px 0 1px #FFD23F; }'
        .'.first { box-shadow: rgba(1, 2, 3, 0.5) 0 2px; }'
        .'.flat { box-shadow: none; }',
    'ShadowStyle.pam.php',
);
$assert(
    $shadowStyles['classes']['card'] === [
        'shadowOffsetX' => '3',
        'shadowOffsetY' => '4',
        'shadowBlurRadius' => '0',
        'shadowSpreadRadius' => '1',
        'shadowColor' => 0xFFFFD23F,
    ]
        && $shadowStyles['classes']['first']['shadowColor'] === 0x80010203
        && $shadowStyles['classes']['flat']['shadowColor'] === 0,
    'Scoped CSS box-shadow must compile to the typed native shadow contract.',
);
$unformattedPam = <<<'PAM'
<?php

declare(strict_types=1);
?>
<template><Column><!-- Keep this explanation. --><Text v-if="$ready" fontSize="15">Ready</Text><Text v-else>Wait</Text></Column></template><style scoped>.ready { color: #112233; font-size: 15px; }</style>
PAM;
$formattedPam = PamFormatter::format($unformattedPam, 'FormatterTest.pam.php');
$assert(
    str_contains($formattedPam, 'p-if="$ready"')
        && str_contains($formattedPam, '<Text p-else>Wait</Text>')
        && str_contains($formattedPam, '<!-- Keep this explanation. -->')
        && str_contains(
            $formattedPam,
            "    .ready {\n        color: #112233;\n        font-size: 15px;\n    }",
        )
        && !str_contains($formattedPam, 'v-if')
        && PamFormatter::format($formattedPam, 'FormatterTest.pam.php')
            === $formattedPam,
    'PAM formatter must migrate directives and produce idempotent output.',
);
$emptyStylePam = PamFormatter::format(
    <<<'PAM'
<?php

declare(strict_types=1);
?>
<template><Text>Ready</Text></template><style scoped>

</style>
PAM,
    'EmptyStyleFormatterTest.pam.php',
);
$assert(
    !str_contains($emptyStylePam, '<style scoped>')
        && str_contains($emptyStylePam, '<Text>Ready</Text>'),
    'PAM formatter must remove empty scoped style blocks.',
);

foreach (NodeKind::cases() as $index => $kind) {
    $assert($kind->value === $index + 1, 'Node kinds must remain sequential protocol integers.');
}
foreach (PropKey::cases() as $index => $key) {
    $assert($key->value === $index + 1, 'Property keys must remain sequential protocol integers.');
}
foreach (EventKind::cases() as $index => $kind) {
    $assert($kind->value === $index + 1, 'Event kinds must remain sequential protocol integers.');
}

$gesture = GestureDetector::make(
    GestureType::Pan,
    Text::make('Drag me'),
)
    ->pointers(1, 2)
    ->direction(GestureDirection::Horizontal)
    ->composition(GestureComposition::Simultaneous)
    ->minimumDistance(12.0)
    ->minimumDuration(80)
    ->onBegin(static function (): void {
    })
    ->onUpdate(static function (): void {
    })
    ->onEnd(static function (): void {
    })
    ->onCancel(static function (): void {
    });
$assert(
    $gesture->kind() === NodeKind::Pressable
        && $gesture->properties()[PropKey::GestureType->value]
            === GestureType::Pan->value
        && $gesture->properties()[PropKey::GestureMaxPointers->value] === 2
        && $gesture->properties()[PropKey::GestureDirection->value]
            === GestureDirection::Horizontal->value
        && count($gesture->events()) === 4,
    'GestureDetector must compile to an additive native Pressable contract.',
);
$gestureEvent = GestureEvent::fromPayload(Wire::map([
    'type' => GestureType::Pinch->value,
    'state' => GestureState::Changed->value,
    'x' => 24.0,
    'y' => 40.0,
    'pageX' => 32.0,
    'pageY' => 72.0,
    'translationX' => 8.0,
    'translationY' => 12.0,
    'velocityX' => 120.0,
    'velocityY' => 80.0,
    'scale' => 1.25,
    'rotation' => 0.2,
    'pointerCount' => 2,
    'timestamp' => 1234,
]));
$assert(
    $gestureEvent->type === GestureType::Pinch
        && $gestureEvent->state === GestureState::Changed
        && $gestureEvent->scale === 1.25
        && $gestureEvent->pointerCount === 2,
    'GestureEvent must decode the bounded cross-platform semantic payload.',
);
$templateGestureScope = new class {
    public ?GestureEvent $event = null;

    public function changed(GestureEvent $event): void
    {
        $this->event = $event;
    }
};
$templateGesture = TemplateRenderer::render(
    TemplateCompiler::compile(
        '<GestureDetector gestureType="pan" on:gestureUpdate="changed">'
        .'<Text>Drag</Text></GestureDetector>',
    ),
    $templateGestureScope,
    [],
);
$templatePinch = TemplateRenderer::render(
    TemplateCompiler::compile(
        '<GestureDetector gestureType="pinch"><Text>Zoom</Text></GestureDetector>',
    ),
    null,
    [],
);
$assert(
    $templatePinch->properties()[PropKey::GestureMinPointers->value] === 2
        && $templatePinch->properties()[PropKey::GestureMaxPointers->value] === 2,
    'Template pinch gestures must retain the native two-pointer defaults.',
);
$templateGesture->events()[EventKind::GestureUpdate->value](Wire::map([
    'type' => GestureType::Pan->value,
    'state' => GestureState::Changed->value,
    'translationX' => -96.0,
    'pointerCount' => 1,
]));
$assert(
    $templateGestureScope->event instanceof GestureEvent
        && $templateGestureScope->event->translationX === -96.0,
    'Template gesture handlers must hydrate their binary payload as GestureEvent.',
);

$repositoryRoot = dirname(__DIR__, 3);
$kotlinProtocol = file_get_contents(
    $repositoryRoot.'/android/app/src/main/java/dev/pam/nativeapp/protocol/PamProtocol.kt',
);
$rustProtocol = file_get_contents(
    $repositoryRoot.'/crates/pam-native-protocol/src/lib.rs',
);
if ($kotlinProtocol === false || $rustProtocol === false) {
    throw new RuntimeException('Cross-language protocol sources must be readable.');
}
if (
    preg_match(
        '/enum class PropKey\\(val value: Int\\) \\{(?<body>.*?)\\n\\}/s',
        $kotlinProtocol,
        $kotlinBlock,
    ) !== 1
    || preg_match(
        '/pub enum PropKey \\{(?<body>.*?)\\n\\}/s',
        $rustProtocol,
        $rustBlock,
    ) !== 1
    || preg_match(
        '/enum class EventKind\\(val value: Int\\) \\{(?<body>.*?)\\n\\}/s',
        $kotlinProtocol,
        $kotlinEventBlock,
    ) !== 1
) {
    throw new RuntimeException('Cross-language protocol enums must be discoverable.');
}
preg_match_all(
    '/^\\s*([A-Z][A-Z0-9_]*)\\((\\d+)\\)[,;]/m',
    $kotlinBlock['body'],
    $kotlinValues,
);
preg_match_all(
    '/^\\s*([A-Za-z][A-Za-z0-9_]*)\\s*=\\s*(\\d+),/m',
    $rustBlock['body'],
    $rustValues,
);
preg_match_all(
    '/^\\s*([A-Z][A-Z0-9_]*)\\((\\d+)\\)[,;]/m',
    $kotlinEventBlock['body'],
    $kotlinEventValues,
);
$phpPropertyValues = array_map(
    static fn (PropKey $key): int => $key->value,
    PropKey::cases(),
);
$kotlinPropertyValues = array_map('intval', $kotlinValues[2]);
$rustPropertyValues = array_map('intval', $rustValues[2]);
$normalizeProtocolName = static function (string $name): string {
    $normalized = preg_replace(
        ['/([a-z0-9])([A-Z])/', '/([A-Z])([A-Z][a-z])/'],
        ['$1_$2', '$1_$2'],
        $name,
    );
    if ($normalized === null) {
        throw new RuntimeException('Protocol enum name could not be normalized.');
    }

    return strtoupper($normalized);
};
$phpPropertyNames = array_map(
    static fn (PropKey $key): string => $normalizeProtocolName($key->name),
    PropKey::cases(),
);
$rustPropertyNames = array_map($normalizeProtocolName, $rustValues[1]);
$assert(
    $kotlinPropertyValues === $phpPropertyValues
        && $rustPropertyValues === $phpPropertyValues
        && $kotlinValues[1] === $phpPropertyNames
        && $rustPropertyNames === $phpPropertyNames,
    'PHP, Kotlin, and Rust property protocol enums must remain byte-for-byte aligned.',
);
$phpEventValues = array_map(
    static fn (EventKind $kind): int => $kind->value,
    EventKind::cases(),
);
$phpEventNames = array_map(
    static fn (EventKind $kind): string => $normalizeProtocolName($kind->name),
    EventKind::cases(),
);
$assert(
    array_map('intval', $kotlinEventValues[2]) === $phpEventValues
        && $kotlinEventValues[1] === $phpEventNames,
    'PHP and Kotlin event protocol enums must remain byte-for-byte aligned.',
);

foreach ([
    AnimationKind::cases(),
    AsyncStatus::cases(),
    MotionPreset::cases(),
    HapticFeedback::cases(),
    FormStatus::cases(),
    TabPresentation::cases(),
    AccessibilityRole::cases(),
    AccessibilityCheckedState::cases(),
    AccessibilityImportance::cases(),
    AccessibilityLiveRegion::cases(),
    FontStyle::cases(),
    ImageCachePolicy::cases(),
    ImageFit::cases(),
    ImageResizeMethod::cases(),
    InputAutoCapitalize::cases(),
    InputAutofillImportance::cases(),
    InputMode::cases(),
    InputSubmitBehavior::cases(),
    InputTextAlignVertical::cases(),
    PointerEvents::cases(),
    PositionType::cases(),
    ReturnKeyType::cases(),
    RefreshIndicatorSize::cases(),
    SafeAreaMode::cases(),
    TextBreakStrategy::cases(),
    TextDataDetectorType::cases(),
    TextDecoration::cases(),
    TextEllipsizeMode::cases(),
    TextHyphenationFrequency::cases(),
    TextTransform::cases(),
] as $cases) {
    foreach ($cases as $index => $case) {
        $assert(
            $case->value === $index + 1,
            $case::class.' values must remain sequential protocol integers.',
        );
    }
}

$loadingState = AsyncValue::loading(['cached']);
$assert(
    $loadingState->status === AsyncStatus::Loading
        && $loadingState->hasData()
        && $loadingState->isBusy(),
    'AsyncValue must preserve cached content while loading.',
);
$offlineState = AsyncValue::offline(['cached']);
$assert(
    $offlineState->status === AsyncStatus::Offline
        && $offlineState->retryable
        && $offlineState->hasData(),
    'Offline state must retain cached content and recovery semantics.',
);

$registrationForm = new class extends NativeForm {
    #[Required]
    #[Email]
    public string $email = '';

    #[Required]
    #[MinLength(12)]
    #[MaxLength(64)]
    public string $password = '';

    #[Matches('password')]
    public string $passwordConfirmation = '';
};
$registrationForm->fill([
    'email' => 'invalid',
    'password' => 'short',
    'passwordConfirmation' => 'different',
], touch: true);
$assert(
    !$registrationForm->beginSubmit()
        && $registrationForm->status() === FormStatus::Failure
        && $registrationForm->error('email') !== null
        && $registrationForm->error('password') !== null
        && $registrationForm->error('passwordConfirmation') !== null,
    'NativeForm must validate attributed fields before submission.',
);
$registrationForm->fill([
    'email' => 'developer@pam.dev',
    'password' => 'a-secure-password',
    'passwordConfirmation' => 'a-secure-password',
]);
$assert(
    $registrationForm->beginSubmit()
        && $registrationForm->status() === FormStatus::Submitting,
    'NativeForm must enter submitting only after successful validation.',
);
$registrationForm->fail(['email' => ['This email is already registered.']]);
$assert(
    $registrationForm->status() === FormStatus::Failure
        && $registrationForm->error('email') === 'This email is already registered.',
    'NativeForm must map server errors to their fields.',
);

$motionElement = Text::make('Motion')->motion(MotionPreset::SlideUp, 260);
$assert(
    $motionElement->properties()[PropKey::AnimationKind->value]
        === AnimationKind::SlideUp->value
        && $motionElement->properties()[PropKey::AnimationDurationMs->value] === 260,
    'Motion presets must compile to native animation properties.',
);
Haptics::trigger(HapticFeedback::Success);
$assert(
    TestDiagnostics::$typedCall !== null
        && TestDiagnostics::$typedCall['operation'] === NativeOperation::Haptic->value
        && Wire::decodeMap(TestDiagnostics::$typedCall['payload'])['feedback']
            === HapticFeedback::Success->value,
    'Semantic haptics must use the typed native operation channel.',
);
Clipboard::setText('Pam Native');
$assert(
    TestDiagnostics::$typedCall !== null
        && TestDiagnostics::$typedCall['operation']
            === NativeOperation::ClipboardSetText->value
        && Wire::decodeMap(TestDiagnostics::$typedCall['payload'])['text']
            === 'Pam Native',
    'Clipboard writes must use the bounded typed native operation channel.',
);
$assert(
    array_map(
        static fn (NativeOperation $operation): int => $operation->value,
        NativeOperation::cases(),
    ) === range(1, 19),
    'Native operations must remain sequential and append-only.',
);
Sensors::read(SensorType::Gyroscope, static function (): void {
}, 50_000);
$sensorPayload = Wire::decodeMap(TestDiagnostics::$typedCall['payload']);
$assert(
    TestDiagnostics::$typedCall['operation'] === NativeOperation::SensorRead->value
        && $sensorPayload['type'] === SensorType::Gyroscope->value
        && $sensorPayload['timeoutMs'] === 10_000,
    'Sensor reads must use integer types and bounded native timeouts.',
);

$tree = Screen::make(
    Column::make(
        Text::make('Pam Native')->key('title'),
        Button::make('Tap')->onPress(static function (): void {
        }),
        Input::make('value')->placeholder('Type here'),
    )->style(new Style(padding: 16.0, gap: 8.0)),
);
$inputSelection = null;
$inputContentSize = null;
$inputKey = null;
$inputEndValue = null;
$inputElement = Input::make('hello')
    ->multiline()
    ->editable(false)
    ->autoCorrect(false)
    ->autoCapitalize(InputAutoCapitalize::Words)
    ->caretHidden()
    ->contextMenuHidden()
    ->cursorColor(0xFF2563EB)
    ->disableFullscreenUi()
    ->autofillImportance(InputAutofillImportance::Yes)
    ->inputMode(InputMode::Email)
    ->minLines(3)
    ->selectTextOnFocus()
    ->selection(1, 4)
    ->showSoftInputOnFocus(false)
    ->submitBehavior(InputSubmitBehavior::Submit)
    ->textAlignVertical(InputTextAlignVertical::Top)
    ->returnKeyLabel('Publish')
    ->scrollEnabled(false)
    ->underlineColor(0x00000000)
    ->onEndEditing(
        static function (string $value) use (&$inputEndValue): void {
            $inputEndValue = $value;
        },
    )
    ->onSelectionChange(
        static function (InputSelectionEvent $event) use (&$inputSelection): void {
            $inputSelection = $event;
        },
    )
    ->onContentSizeChange(
        static function (InputContentSizeEvent $event) use (
            &$inputContentSize,
        ): void {
            $inputContentSize = $event;
        },
    )
    ->onKeyPress(
        static function (InputKeyEvent $event) use (&$inputKey): void {
            $inputKey = $event;
        },
    );
$inputElement->events()[EventKind::InputEndEditing->value]('finished');
$inputElement->events()[EventKind::InputSelectionChange->value](Wire::map([
    'start' => 1,
    'end' => 4,
]));
$inputElement->events()[EventKind::InputContentSizeChange->value](Wire::map([
    'width' => 240.0,
    'height' => 96.0,
]));
$inputElement->events()[EventKind::InputKeyPress->value](Wire::map([
    'key' => 'Enter',
]));
$assert(
    $inputElement->properties()[PropKey::InputEditable->value] === false
        && $inputElement->properties()[PropKey::InputAutoCorrect->value]
            === false
        && $inputElement->properties()[PropKey::InputAutoCapitalize->value]
            === InputAutoCapitalize::Words->value
        && $inputElement->properties()[PropKey::InputAutofillImportance->value]
            === InputAutofillImportance::Yes->value
        && $inputElement->properties()[PropKey::InputMode->value]
            === InputMode::Email->value
        && $inputElement->properties()[PropKey::InputMinLines->value] === 3
        && $inputElement->properties()[PropKey::InputSelectionStart->value]
            === 1
        && $inputElement->properties()[PropKey::InputSelectionEnd->value] === 4
        && $inputElement->properties()[PropKey::InputSubmitBehavior->value]
            === InputSubmitBehavior::Submit->value
        && $inputElement->properties()[PropKey::InputTextAlignVertical->value]
            === InputTextAlignVertical::Top->value
        && $inputElement->properties()[PropKey::InputReturnKeyLabel->value]
            === 'Publish'
        && $inputElement->properties()[PropKey::InputScrollEnabled->value]
            === false
        && $inputEndValue === 'finished'
        && $inputSelection instanceof InputSelectionEvent
        && $inputSelection->end === 4
        && $inputContentSize instanceof InputContentSizeEvent
        && $inputContentSize->height === 96.0
        && $inputKey instanceof InputKeyEvent
        && $inputKey->key === 'Enter',
    'Input helpers must preserve native editing, selection, keyboard, size and key behavior.',
);
$secureAliasElement = TemplateRenderer::render(
    TemplateCompiler::compile('<Input secureTextEntry="true" />'),
    new class {
    },
    [],
);
$assert(
    $secureAliasElement->properties()[PropKey::Secure->value] === true,
    'Input secureTextEntry must remain a compatible alias for secure.',
);
$memoryDiskImage = TemplateRenderer::render(
    TemplateCompiler::compile('<Image source="https://example.test/image.webp" cachePolicy="memory-disk" />'),
    new class {
    },
    [],
);
$assert(
    $memoryDiskImage->properties()[PropKey::ImageCachePolicy->value]
        === ImageCachePolicy::ForceCache->value,
    'Image memory-disk must map the familiar Expo policy to the native memory/disk cache.',
);
$keyboardAliasElement = TemplateRenderer::render(
    TemplateCompiler::compile(
        '<Column>'
        .'<Input keyboardType="default" />'
        .'<Input keyboardType="email-address" />'
        .'<Input keyboardType="number-pad" />'
        .'<Input keyboardType="phone-pad" />'
        .'<Input keyboardType="decimal-pad" />'
        .'</Column>',
    ),
    new class {
    },
    [],
);
$assert(
    array_map(
        static fn (\Pam\Native\Element $input): int => $input
            ->properties()[PropKey::KeyboardType->value],
        $keyboardAliasElement->children(),
    ) === [
        KeyboardType::Text->value,
        KeyboardType::Email->value,
        KeyboardType::Number->value,
        KeyboardType::Phone->value,
        KeyboardType::Decimal->value,
    ],
    'Input keyboardType must accept familiar React Native aliases.',
);
$dynamicColorElement = TemplateRenderer::render(
    TemplateCompiler::compile(
        '<Text :textColor="$active ? \'#1B7A4E\' : \'#777770\'">Status</Text>',
    ),
    null,
    ['active' => true],
);
$assert(
    $dynamicColorElement->properties()[PropKey::TextColor->value] === 0xFF1B7A4E,
    'Dynamically bound hexadecimal colors must cross the native bridge as integers.',
);
$transparentColorElement = TemplateRenderer::render(
    TemplateCompiler::compile(
        '<View backgroundColor="transparent">'
        .'<View backgroundColor="rgba(255 255 255 / 25%)" />'
        .'<View backgroundColor="#1234" />'
        .'<View backgroundColor="#80112233" />'
        .'</View>',
    ),
    null,
    [],
);
$assert(
    $transparentColorElement->properties()[PropKey::BackgroundColor->value] === 0x00000000
        && $transparentColorElement->children()[0]
            ->properties()[PropKey::BackgroundColor->value] === 0x40FFFFFF
        && $transparentColorElement->children()[1]
            ->properties()[PropKey::BackgroundColor->value] === 0x44112233
        && $transparentColorElement->children()[2]
            ->properties()[PropKey::BackgroundColor->value] === 0x80112233,
    'Direct colors must add CSS forms while preserving legacy eight-digit ARGB.',
);
$quotedComparisonTemplate = TemplateCompiler::compile(
    '<Column><Text v-if="$count > 0">More</Text>'
    .'<Text v-if="$count < 1">Less</Text></Column>',
);
$assert(
    $quotedComparisonTemplate->children[0]->children[0]->attributes['v-if']
        === '$count > 0'
        && $quotedComparisonTemplate->children[0]->children[1]->attributes['v-if']
            === '$count < 1',
    'Template tags must keep angle-bracket operators inside quoted attributes.',
);
$pamDirectiveBranches = TemplateRenderer::render(
    TemplateCompiler::compile(
        '<Column>'
        .'<Text p-if="$kind === 1">One</Text>'
        .'<Text p-else-if="$kind === 2">Two</Text>'
        .'<Text p-else>Other</Text>'
        .'</Column>',
    ),
    null,
    ['kind' => 2],
);
$assert(
    count($pamDirectiveBranches->children()) === 1
        && $pamDirectiveBranches->children()[0]
            ->properties()[PropKey::Text->value] === 'Two',
    'Native p-if, p-else-if and p-else directives must render one matching branch.',
);
$scrollViewElement = TemplateRenderer::render(
    TemplateCompiler::compile(
        '<ScrollView horizontal="true">'
        .'<Pressable width="66"><Text>New</Text></Pressable>'
        .'<Pressable width="76"><Text>Saved</Text></Pressable>'
        .'</ScrollView>',
    ),
    null,
    [],
);
$scrollContent = $scrollViewElement->children()[0] ?? null;
$assert(
    $scrollViewElement->kind() === NodeKind::Scroll
        && $scrollContent instanceof \Pam\Native\Element
        && $scrollContent->kind() === NodeKind::Row
        && count($scrollContent->children()) === 2
        && (float) $scrollContent->children()[0]
            ->properties()[PropKey::Width->value] === 66.0
        && (float) $scrollContent->children()[1]
            ->properties()[PropKey::Width->value] === 76.0,
    'Declarative horizontal ScrollView must preserve direct child widths in a Row container.',
);
$pressInEvent = null;
$pressOutEvent = null;
$pressMoveEvent = null;
$pressableElement = Pressable::make(Text::make('Open'))
    ->ripple(0x66010203, true, 24.0, true, 0.5)
    ->pressedOpacity(0.7)
    ->hitSlopEdges(4.0, 6.0, 8.0, 10.0)
    ->pressRetentionEdges(12.0, 14.0, 16.0, 18.0)
    ->delayLongPress(420)
    ->delayPressIn(25)
    ->delayPressOut(40)
    ->androidDisableSound()
    ->onPressIn(static function (PressEvent $event) use (&$pressInEvent): void {
        $pressInEvent = $event;
    })
    ->onPressOut(static function (PressEvent $event) use (&$pressOutEvent): void {
        $pressOutEvent = $event;
    })
    ->onPressMove(static function (PressEvent $event) use (&$pressMoveEvent): void {
        $pressMoveEvent = $event;
    });
$pressPayload = Wire::map([
    'x' => 11.5,
    'y' => 12.5,
    'pageX' => 21.5,
    'pageY' => 22.5,
    'timestamp' => 1234,
    'pointerId' => 7,
]);
$pressableElement->events()[EventKind::PressIn->value]($pressPayload);
$pressableElement->events()[EventKind::PressOut->value]($pressPayload);
$pressableElement->events()[EventKind::PressMove->value]($pressPayload);
$assert(
    $pressableElement->properties()[PropKey::HitSlopLeft->value] === 4.0
        && $pressableElement->properties()[PropKey::HitSlopBottom->value] === 10.0
        && $pressableElement->properties()[PropKey::PressRetentionRight->value] === 16.0
        && $pressableElement->properties()[PropKey::PressDelayLongMs->value] === 420
        && $pressableElement->properties()[PropKey::PressDelayInMs->value] === 25
        && $pressableElement->properties()[PropKey::PressDelayOutMs->value] === 40
        && $pressableElement->properties()[PropKey::PressAndroidDisableSound->value] === true
        && $pressableElement->properties()[PropKey::RippleBorderless->value] === true
        && $pressableElement->properties()[PropKey::RippleRadius->value] === 24.0
        && $pressableElement->properties()[PropKey::RippleForeground->value] === true
        && $pressableElement->properties()[PropKey::RippleAlpha->value] === 0.5
        && $pressInEvent instanceof PressEvent
        && $pressInEvent->x === 11.5
        && $pressOutEvent instanceof PressEvent
        && $pressMoveEvent instanceof PressEvent
        && $pressMoveEvent->pointerId === 7,
    'Pressable helpers must preserve complete native gesture geometry and lifecycle events.',
);
$accessibilityElement = Text::make('Upload')
    ->accessibilityRole(AccessibilityRole::ProgressBar)
    ->accessible()
    ->accessibilityLiveRegion(AccessibilityLiveRegion::Polite)
    ->accessibilityImportance(AccessibilityImportance::Yes)
    ->accessibilityExpanded(false)
    ->accessibilityBusy(true)
    ->accessibilityChecked(AccessibilityCheckedState::Mixed)
    ->accessibilityValue(0.0, 100.0, 40.0, '40 percent');
$assert(
    $accessibilityElement->properties()[PropKey::Accessible->value] === true
        && $accessibilityElement
            ->properties()[PropKey::AccessibilityLiveRegion->value]
            === AccessibilityLiveRegion::Polite->value
        && $accessibilityElement
            ->properties()[PropKey::AccessibilityImportance->value]
            === AccessibilityImportance::Yes->value
        && $accessibilityElement
            ->properties()[PropKey::AccessibilityExpanded->value] === false
        && $accessibilityElement
            ->properties()[PropKey::AccessibilityBusy->value] === true
        && $accessibilityElement
            ->properties()[PropKey::AccessibilityCheckedState->value]
            === AccessibilityCheckedState::Mixed->value
        && $accessibilityElement
            ->properties()[PropKey::AccessibilityValueNow->value] === 40.0
        && $accessibilityElement
            ->properties()[PropKey::AccessibilityValueText->value]
            === '40 percent',
    'Accessibility state and range helpers must use fixed protocol properties.',
);

$safeAreaElement = SafeAreaView::make(Text::make('Inset content'))
    ->edges(top: true, right: false, bottom: true, left: false)
    ->mode(SafeAreaMode::Margin);
$assert(
    $safeAreaElement->properties()[PropKey::SafeAreaTop->value] === true
        && $safeAreaElement->properties()[PropKey::SafeAreaRight->value] === false
        && $safeAreaElement
            ->properties()[PropKey::SafeAreaBottomEdge->value] === true
        && $safeAreaElement->properties()[PropKey::SafeAreaLeft->value] === false
        && $safeAreaElement->properties()[PropKey::SafeAreaMode->value]
            === SafeAreaMode::Margin->value,
    'Safe area helpers must preserve per-edge and mode protocol properties.',
);

$keyboardAvoidingElement = KeyboardAvoidingView::make(
    Text::make('Keyboard content'),
    KeyboardAvoidingBehavior::Padding,
)->verticalOffset(24.0)->avoidingEnabled(false);
$assert(
    $keyboardAvoidingElement->properties()[PropKey::KeyboardBehavior->value]
        === KeyboardAvoidingBehavior::Padding->value
        && $keyboardAvoidingElement
            ->properties()[PropKey::KeyboardVerticalOffset->value] === 24.0
        && $keyboardAvoidingElement
            ->properties()[PropKey::KeyboardAvoidingEnabled->value] === false,
    'Keyboard avoidance helpers must preserve behavior, offset and enabled state.',
);

$refreshColors = [0xff112233, 0xff445566];
$refreshElement = RefreshControl::make(Text::make('Refresh content'), true)
    ->colors(...$refreshColors)
    ->progressBackgroundColor(0xfff8fafc)
    ->progressViewOffset(16.0)
    ->enabled(false)
    ->size(RefreshIndicatorSize::Large);
$assert(
    $refreshElement->properties()[PropKey::Refreshing->value] === true
        && $refreshElement->properties()[PropKey::RefreshColors->value]
            === implode(',', $refreshColors)
        && $refreshElement
            ->properties()[PropKey::RefreshProgressBackgroundColor->value]
            === 0xfff8fafc
        && $refreshElement
            ->properties()[PropKey::RefreshProgressViewOffset->value] === 16.0
        && $refreshElement->properties()[PropKey::Enabled->value] === false
        && $refreshElement->properties()[PropKey::RefreshIndicatorSize->value]
            === RefreshIndicatorSize::Large->value,
    'Refresh control helpers must preserve Android indicator and gesture properties.',
);

$textElement = Text::make('Selectable https://pam.dev')
    ->numberOfLines(2)
    ->selectable()
    ->selectionColor(0x66112233)
    ->ellipsize(TextEllipsizeMode::Middle)
    ->allowFontScaling()
    ->maxFontSizeMultiplier(1.5)
    ->adjustsFontSizeToFit(minimumScale: 0.5)
    ->breakStrategy(TextBreakStrategy::Balanced)
    ->hyphenation(TextHyphenationFrequency::Full)
    ->dataDetector(TextDataDetectorType::Link);
$assert(
    $textElement->properties()[PropKey::NumberOfLines->value] === 2
        && $textElement->properties()[PropKey::TextSelectable->value] === true
        && $textElement->properties()[PropKey::SelectionColor->value] === 0x66112233
        && $textElement->properties()[PropKey::TextEllipsizeMode->value]
            === TextEllipsizeMode::Middle->value
        && $textElement->properties()[PropKey::TextAllowFontScaling->value] === true
        && $textElement
            ->properties()[PropKey::TextMaxFontSizeMultiplier->value] === 1.5
        && $textElement
            ->properties()[PropKey::TextAdjustsFontSizeToFit->value] === true
        && $textElement->properties()[PropKey::TextMinimumFontScale->value] === 0.5
        && $textElement->properties()[PropKey::TextBreakStrategy->value]
            === TextBreakStrategy::Balanced->value
        && $textElement->properties()[PropKey::TextHyphenationFrequency->value]
            === TextHyphenationFrequency::Full->value
        && $textElement->properties()[PropKey::TextDataDetectorType->value]
            === TextDataDetectorType::Link->value,
    'Text helpers must preserve selection, fitting, breaking and detector properties.',
);

$statusBarElement = StatusBar::make(
    0x80112233,
    StatusBarAppearance::Light,
    true,
)->animated()->translucent();
$assert(
    $statusBarElement->properties()[PropKey::StatusBarColor->value] === 0x80112233
        && $statusBarElement->properties()[PropKey::StatusBarStyle->value]
            === StatusBarAppearance::Light->value
        && $statusBarElement->properties()[PropKey::StatusBarHidden->value] === true
        && $statusBarElement->properties()[PropKey::StatusBarAnimated->value] === true
        && $statusBarElement->properties()[PropKey::StatusBarTranslucent->value] === true,
    'Status bar helpers must preserve color, style, visibility and edge-to-edge properties.',
);
$statusBarAliases = TemplateRenderer::render(
    TemplateCompiler::compile(
        '<StatusBar barStyle="light-content" backgroundColor="#F7F6F2" '
        .'animated="true" translucent="true" />',
    ),
    null,
    [],
);
$assert(
    $statusBarAliases->properties()[PropKey::StatusBarColor->value] === 0xFFF7F6F2
        && $statusBarAliases->properties()[PropKey::StatusBarStyle->value]
            === StatusBarAppearance::Light->value
        && $statusBarAliases->properties()[PropKey::StatusBarAnimated->value] === true
        && $statusBarAliases->properties()[PropKey::StatusBarTranslucent->value] === true,
    'StatusBar must accept the familiar backgroundColor and barStyle aliases.',
);
$modalShown = false;
$modalDismissed = false;
$modalRequestedClose = false;
$modalOrientation = null;
$modalElement = Modal::make(
    Text::make('Modal content'),
    true,
    ModalPresentation::Sheet,
)
    ->animationType(ModalAnimationType::Slide)
    ->backdropColor(0xFF102030)
    ->transparent()
    ->hardwareAccelerated()
    ->navigationBarTranslucent()
    ->statusBarTranslucent()
    ->allowSwipeDismissal()
    ->onRequestClose(static function () use (&$modalRequestedClose): void {
        $modalRequestedClose = true;
    })
    ->onShow(static function () use (&$modalShown): void {
        $modalShown = true;
    })
    ->onDismiss(static function () use (&$modalDismissed): void {
        $modalDismissed = true;
    })
    ->onOrientationChange(
        static function (ModalOrientation $orientation) use (
            &$modalOrientation,
        ): void {
            $modalOrientation = $orientation;
        },
    );
$modalElement->events()[EventKind::ModalRequestClose->value]('');
$modalElement->events()[EventKind::ModalShow->value]('');
$modalElement->events()[EventKind::ModalDismiss->value]('');
$modalElement->events()[EventKind::ModalOrientationChange->value](
    (string) ModalOrientation::Landscape->value,
);
$assert(
    $modalElement->properties()[PropKey::ModalPresentation->value]
        === ModalPresentation::Sheet->value
        && $modalElement->properties()[PropKey::ModalAnimationType->value]
            === ModalAnimationType::Slide->value
        && $modalElement->properties()[PropKey::ModalBackdropColor->value]
            === 0xFF102030
        && $modalElement->properties()[PropKey::ModalTransparent->value] === true
        && $modalElement
            ->properties()[PropKey::ModalHardwareAccelerated->value] === true
        && $modalElement
            ->properties()[PropKey::ModalNavigationBarTranslucent->value] === true
        && $modalElement
            ->properties()[PropKey::ModalStatusBarTranslucent->value] === true
        && $modalElement
            ->properties()[PropKey::ModalAllowSwipeDismissal->value] === true
        && $modalRequestedClose
        && $modalShown
        && $modalDismissed
        && $modalOrientation === ModalOrientation::Landscape,
    'Modal helpers must preserve window configuration and typed lifecycle events.',
);

$scrollElement = Scroll::make(Text::make('Scrollable'))
    ->horizontal()
    ->contentOffset(24.0, 8.0)
    ->fillViewport(false)
    ->overScrollMode(ScrollOverScrollMode::Never)
    ->nestedScrollEnabled()
    ->fadingEdgeLength(12.0)
    ->persistentScrollbar()
    ->pagingEnabled()
    ->snapToInterval(80.0)
    ->decelerationRate(0.9)
    ->keyboardDismissMode(ScrollKeyboardDismissMode::OnDrag)
    ->scrollRequest(7, 'first-unread', 143.5)
    ->scrollEnabled(false)
    ->showsIndicator()
    ->onScroll(static function (): void {
    });
$assert(
    $scrollElement->properties()[PropKey::ScrollHorizontal->value] === true
        && $scrollElement->properties()[PropKey::ScrollContentOffsetX->value] === 24.0
        && $scrollElement->properties()[PropKey::ScrollContentOffsetY->value] === 8.0
        && $scrollElement->properties()[PropKey::ScrollFillViewport->value] === false
        && $scrollElement->properties()[PropKey::ScrollOverScrollMode->value]
            === ScrollOverScrollMode::Never->value
        && $scrollElement->properties()[PropKey::ScrollNestedEnabled->value] === true
        && $scrollElement->properties()[PropKey::ScrollFadingEdgeLength->value] === 12.0
        && $scrollElement
            ->properties()[PropKey::ScrollPersistentScrollbar->value] === true
        && $scrollElement->properties()[PropKey::ScrollPagingEnabled->value] === true
        && $scrollElement->properties()[PropKey::ScrollSnapInterval->value] === 80.0
        && $scrollElement->properties()[PropKey::ScrollDecelerationRate->value] === 0.9
        && $scrollElement->properties()[PropKey::ScrollKeyboardDismissMode->value]
            === ScrollKeyboardDismissMode::OnDrag->value
        && $scrollElement->properties()[PropKey::ScrollTargetTestId->value]
            === 'first-unread'
        && $scrollElement->properties()[PropKey::ScrollTargetOffset->value] === 143.5
        && $scrollElement->properties()[PropKey::ScrollRequest->value] === 7
        && isset($scrollElement->events()[EventKind::Scroll->value]),
    'Scroll helpers must preserve Android-owned orientation, momentum and viewport behavior.',
);

$indicatorElement = ActivityIndicator::make(false)
    ->hidesWhenStopped(false)
    ->size(ActivityIndicatorSize::Large)
    ->color(0xFF2563EB);
$assert(
    $indicatorElement->properties()[PropKey::ActivityAnimating->value] === false
        && $indicatorElement
            ->properties()[PropKey::ActivityHidesWhenStopped->value] === false
        && $indicatorElement->properties()[PropKey::ActivitySize->value] === 36.0
        && $indicatorElement->properties()[PropKey::ProgressColor->value] === 0xFF2563EB,
    'Activity indicator helpers must preserve animation, stopped visibility, size and tint.',
);

$toggleElement = Toggle::make(true)
    ->trackColors(0xFF64748B, 0xFF2563EB)
    ->thumbColor(0xFFFFFFFF);
$assert(
    $toggleElement->properties()[PropKey::Checked->value] === true
        && $toggleElement
            ->properties()[PropKey::SwitchTrackColorFalse->value] === 0xFF64748B
        && $toggleElement
            ->properties()[PropKey::SwitchTrackColorTrue->value] === 0xFF2563EB
        && $toggleElement->properties()[PropKey::SwitchThumbColor->value] === 0xFFFFFFFF,
    'Switch helpers must preserve checked, track and thumb colors.',
);

$imageStarted = false;
$imageProgress = null;
$imageLoaded = null;
$imageError = null;
$imageEnded = false;
$imageElement = Image::make('https://example.test/photo.jpg')
    ->fit(ImageFit::Repeat)
    ->defaultSource('asset://placeholder.png')
    ->loadingIndicatorSource('asset://loading.png')
    ->fadeDuration(180)
    ->resizeMethod(ImageResizeMethod::Resize)
    ->resizeMultiplier(2.0)
    ->progressiveRendering()
    ->cache(ImageCachePolicy::ForceCache)
    ->overlayColor(0xFF0F172A)
    ->sourceSet(
        'https://example.test/photo.png 1x, '.
        'https://example.test/photo@2x.png 2x',
    )
    ->headers(['X-Image-Variant' => 'retina'])
    ->onLoadStart(static function () use (&$imageStarted): void {
        $imageStarted = true;
    })
    ->onProgress(
        static function (ImageProgressEvent $event) use (&$imageProgress): void {
            $imageProgress = $event;
        },
    )
    ->onLoad(
        static function (ImageLoadEvent $event) use (&$imageLoaded): void {
            $imageLoaded = $event;
        },
    )
    ->onError(
        static function (ImageErrorEvent $event) use (&$imageError): void {
            $imageError = $event;
        },
    )
    ->onLoadEnd(static function () use (&$imageEnded): void {
        $imageEnded = true;
    });
$imageElement->events()[EventKind::ImageLoadStart->value]('');
$imageElement->events()[EventKind::ImageProgress->value](Wire::map([
    'loaded' => 65_536,
    'total' => 131_072,
]));
$imageElement->events()[EventKind::ImageLoad->value](Wire::map([
    'uri' => 'https://example.test/photo@2x.png',
    'width' => 800.0,
    'height' => 600.0,
]));
$imageElement->events()[EventKind::ImageError->value](Wire::map([
    'error' => 'offline',
]));
$imageElement->events()[EventKind::ImageLoadEnd->value]('');
$imageBackground = ImageBackground::make(
    'asset://hero.jpg',
    Text::make('Overlay'),
)->tint(0xFFFFFFFF);
$assert(
    $imageElement->properties()[PropKey::ImageFit->value]
        === ImageFit::Repeat->value
        && $imageElement->properties()[PropKey::ImageDefaultSource->value]
            === 'asset://placeholder.png'
        && $imageElement
            ->properties()[PropKey::ImageLoadingIndicatorSource->value]
            === 'asset://loading.png'
        && $imageElement->properties()[PropKey::ImageFadeDurationMs->value]
            === 180
        && $imageElement->properties()[PropKey::ImageResizeMethod->value]
            === ImageResizeMethod::Resize->value
        && $imageElement->properties()[PropKey::ImageResizeMultiplier->value]
            === 2.0
        && $imageElement
            ->properties()[PropKey::ImageProgressiveRenderingEnabled->value]
            === true
        && $imageElement->properties()[PropKey::ImageCachePolicy->value]
            === ImageCachePolicy::ForceCache->value
        && $imageElement->properties()[PropKey::ImageOverlayColor->value]
            === 0xFF0F172A
        && $imageElement->properties()[PropKey::ImageRequestHeaders->value]
            === 'X-Image-Variant:retina'
        && $imageStarted
        && $imageProgress instanceof ImageProgressEvent
        && $imageProgress->loaded === 65_536
        && $imageLoaded instanceof ImageLoadEvent
        && $imageLoaded->width === 800.0
        && $imageError instanceof ImageErrorEvent
        && $imageError->message === 'offline'
        && $imageEnded
        && $imageBackground->children()[0]->kind() === NodeKind::Text
        && $imageBackground->properties()[PropKey::TintColor->value]
            === 0xFFFFFFFF,
    'Image helpers must preserve native loading, cache, resize, lifecycle and background behavior.',
);

$drawingChanged = '';
$drawingCanvas = DrawingCanvas::make(
    'pam-file:///editor/source.jpg',
    '{"version":1,"strokes":[]}',
)
    ->brush(0xFF2563EB, 8.0, DrawingMode::Eraser)
    ->clearRequest(2)
    ->undoRequest(3)
    ->onChange(static function (string $value) use (&$drawingChanged): void {
        $drawingChanged = $value;
    });
($drawingCanvas->events()[EventKind::Change->value])('{"version":1,"strokes":[1]}');
$assert(
    $drawingCanvas->kind() === NodeKind::DrawingCanvas
        && $drawingCanvas->properties()[PropKey::Source->value]
            === 'pam-file:///editor/source.jpg'
        && $drawingCanvas->properties()[PropKey::DrawingColor->value] === 0xFF2563EB
        && $drawingCanvas->properties()[PropKey::DrawingWidth->value] === 8.0
        && $drawingCanvas->properties()[PropKey::DrawingMode->value]
            === DrawingMode::Eraser->value
        && $drawingCanvas->properties()[PropKey::DrawingClearRequest->value] === 2
        && $drawingCanvas->properties()[PropKey::DrawingUndoRequest->value] === 3
        && $drawingChanged === '{"version":1,"strokes":[1]}',
    'DrawingCanvas must keep stroke rendering and completed-stroke events native.',
);

$nativeControlTemplate = TemplateRenderer::render(
    TemplateCompiler::compile(<<<'PAM'
<Screen>
    <ScrollView
        horizontal="true"
        contentOffset="24"
        anchorToEnd="true"
        maintainVisibleContentPosition="true"
        autoScrollToEndThreshold="32"
        scrollTargetTestId="first-unread"
        scrollTargetOffset="143.5"
        scrollRequest="9"
        pagingEnabled="true"
        snapToInterval="80"
        overScrollMode="never"
        keyboardDismissMode="on-drag"
    >
        <Text>Scrollable</Text>
    </ScrollView>
    <ActivityIndicator
        animating="false"
        hidesWhenStopped="false"
        size="large"
        progressColor="#2563eb"
    />
    <Switch
        checked="true"
        trackColorFalse="#64748b"
        trackColorTrue="#2563eb"
        thumbColor="#ffffff"
    />
    <DrawingCanvas
        source="pam-file:///editor/source.jpg"
        value="{&quot;version&quot;:1,&quot;strokes&quot;:[]}"
        brushColor="#2563eb"
        brushWidth="7"
        drawingMode="eraser"
        clearRequest="4"
        undoRequest="5"
    />
</Screen>
PAM),
    null,
    [],
);
$templateScroll = $nativeControlTemplate->children()[0];
$templateIndicator = $nativeControlTemplate->children()[1];
$templateSwitch = $nativeControlTemplate->children()[2];
$templateDrawing = $nativeControlTemplate->children()[3];
$assert(
    $templateScroll->properties()[PropKey::ScrollHorizontal->value] === true
        && $templateScroll
            ->properties()[PropKey::ScrollContentOffsetX->value] === 24.0
        && $templateScroll->properties()[PropKey::ScrollAnchorToEnd->value] === true
        && $templateScroll
            ->properties()[PropKey::ScrollMaintainVisibleContentPosition->value] === true
        && $templateScroll
            ->properties()[PropKey::ScrollAutoScrollToEndThreshold->value] === 32.0
        && $templateScroll
            ->properties()[PropKey::ScrollTargetTestId->value] === 'first-unread'
        && $templateScroll
            ->properties()[PropKey::ScrollTargetOffset->value] === 143.5
        && $templateScroll->properties()[PropKey::ScrollRequest->value] === 9
        && $templateScroll->properties()[PropKey::ScrollPagingEnabled->value] === true
        && $templateScroll->properties()[PropKey::ScrollSnapInterval->value] === 80
        && $templateIndicator->properties()[PropKey::ActivityAnimating->value] === false
        && $templateIndicator
            ->properties()[PropKey::ActivityHidesWhenStopped->value] === false
        && $templateIndicator->properties()[PropKey::ActivitySize->value] === 36.0
        && $templateSwitch
            ->properties()[PropKey::SwitchTrackColorFalse->value] === 0xFF64748B
        && $templateSwitch
            ->properties()[PropKey::SwitchTrackColorTrue->value] === 0xFF2563EB
        && $templateSwitch->properties()[PropKey::SwitchThumbColor->value] === 0xFFFFFFFF
        && $templateDrawing->kind() === NodeKind::DrawingCanvas
        && $templateDrawing->properties()[PropKey::DrawingColor->value] === 0xFF2563EB
        && (float) $templateDrawing->properties()[PropKey::DrawingWidth->value] === 7.0
        && $templateDrawing->properties()[PropKey::DrawingMode->value]
            === DrawingMode::Eraser->value
        && $templateDrawing->properties()[PropKey::DrawingClearRequest->value] === 4
        && $templateDrawing->properties()[PropKey::DrawingUndoRequest->value] === 5,
    'Native control tags must map to typed scroll, indicator, switch and drawing protocols.',
);

$listElement = FlatList::make(['One', 'Two', 'Three'])
    ->rowHeight(56.0)
    ->prefetch(8)
    ->columns(2)
    ->inverted()
    ->initialScrollIndex(1)
    ->removeClippedSubviews(false)
    ->scrollEnabled(false)
    ->showsIndicator(false)
    ->onScroll(static function (): void {
    })
    ->onEndReached(static function (): void {
    }, 0.25);
$assert(
    $listElement->properties()[PropKey::ListRowHeight->value] === 56.0
        && $listElement->properties()[PropKey::ListPrefetch->value] === 8
        && $listElement->properties()[PropKey::ListNumColumns->value] === 2
        && $listElement->properties()[PropKey::ListInverted->value] === true
        && $listElement->properties()[PropKey::ListInitialScrollIndex->value] === 1
        && $listElement
            ->properties()[PropKey::ListRemoveClippedSubviews->value] === false
        && $listElement->properties()[PropKey::ScrollEnabled->value] === false
        && $listElement->properties()[PropKey::ShowsScrollIndicator->value] === false
        && $listElement->properties()[PropKey::EndReachedThreshold->value] === 0.25
        && isset($listElement->events()[EventKind::Scroll->value])
        && isset($listElement->events()[EventKind::EndReached->value]),
    'List helpers must preserve recycling, prefetch, layout and scroll event properties.',
);

$richListElement = VirtualizedList::make(
    Column::make(
        Image::make('https://example.com/one.png'),
        Text::make('One'),
    )->key('one'),
    Pressable::make(Text::make('Two'))->key('two'),
)
    ->estimatedRowHeight(180.0)
    ->columns(2)
    ->prefetch(6);
$assert(
    $richListElement->kind() === NodeKind::VirtualList
        && count($richListElement->children()) === 2
        && $richListElement->children()[0]->children()[0]->kind() === NodeKind::Image
        && $richListElement->properties()[PropKey::ListRowHeight->value] === 180.0
        && $richListElement->properties()[PropKey::ListNumColumns->value] === 2
        && $richListElement->properties()[PropKey::ListPrefetch->value] === 6,
    'VirtualizedList must retain arbitrary keyed component trees as recyclable cells.',
);
$adaptiveListTemplate = TemplateRenderer::render(
    TemplateCompiler::compile(
        '<VirtualizedList estimatedRowHeight="180"><Column height="240" /></VirtualizedList>',
    ),
    null,
    [],
);
$assert(
    $adaptiveListTemplate->properties()[PropKey::ListRowHeight->value] == 180.0
        && $adaptiveListTemplate->children()[0]->properties()[PropKey::Height->value] == 240.0,
    'VirtualizedList estimatedRowHeight must preserve an explicit rich-cell extent.',
);
$legacyVirtualizedList = VirtualizedList::make(['One', 'Two']);
$assert(
    $legacyVirtualizedList->kind() === NodeKind::List
        && $legacyVirtualizedList->children() === [],
    'VirtualizedList must remain source-compatible with legacy string-array call sites.',
);

$virtualGridElement = VirtualGrid::make(
    3,
    Text::make('A')->key('a'),
    Image::make('https://example.com/b.png')->key('b'),
);
$assert(
    $virtualGridElement->kind() === NodeKind::VirtualList
        && count($virtualGridElement->children()) === 2
        && $virtualGridElement->properties()[PropKey::ListNumColumns->value] === 3,
    'VirtualGrid must expose rich virtual cells with an explicit native column count.',
);

$sectionListElement = SectionList::make([
    'Frameworks' => ['Laravel', 'PAM'],
])
    ->rowHeight(52.0)
    ->prefetch(6)
    ->horizontal()
    ->columns(2)
    ->inverted()
    ->initialScrollIndex(1)
    ->removeClippedSubviews(false);
$assert(
    $sectionListElement->properties()[PropKey::ListRowHeight->value] === 52.0
        && $sectionListElement->properties()[PropKey::ListPrefetch->value] === 6
        && $sectionListElement->properties()[PropKey::ListHorizontal->value] === true
        && $sectionListElement->properties()[PropKey::ListNumColumns->value] === 2
        && $sectionListElement->properties()[PropKey::ListInverted->value] === true
        && $sectionListElement
            ->properties()[PropKey::ListInitialScrollIndex->value] === 1
        && $sectionListElement
            ->properties()[PropKey::ListRemoveClippedSubviews->value] === false,
    'Section list helpers must preserve native recycler configuration.',
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
$gallery = [
    ['url' => 'file:///mountain.jpg', 'alt' => 'Mountain'],
    ['url' => 'file:///ocean.jpg', 'alt' => 'Ocean'],
];
$variantTemplate = TemplateCompiler::compile(
    '<VariantParent images="$gallery"><VariantChild /></VariantParent>',
);
TemplateRenderer::render($variantTemplate, null, ['gallery' => $gallery]);
$assert(
    $capturedParentVariants === ['images' => $gallery],
    'Bound declarative arrays must remain available to compound child components.',
);
$capturedClassName = null;
TemplateRegistry::component(
    'SemanticClasses',
    static function (
        array $props,
    ) use (&$capturedClassName): \Pam\Native\Renderable {
        $capturedClassName = $props['className'] ?? null;

        return Column::make();
    },
);
TemplateRegistry::styleResolver(
    static fn (string $class): ?array => $class === 'grid-cols-2'
        ? []
        : null,
);
$semanticClassTemplate = TemplateCompiler::compile(
    '<SemanticClasses class="grid-cols-2 gap-3" />',
);
$semanticClassElement = TemplateRenderer::render(
    $semanticClassTemplate,
    null,
    [],
);
$assert(
    $capturedClassName === 'grid-cols-2 gap-3'
        && ($semanticClassElement->properties()[PropKey::Gap->value] ?? null)
            === 12.0,
    'Registered template components must receive className before visual utilities are applied.',
);
$capturedStyleBoundaryProps = null;
TemplateRegistry::component(
    'StyleBoundary',
    static function (
        array $props,
    ) use (&$capturedStyleBoundaryProps): \Pam\Native\Renderable {
        $capturedStyleBoundaryProps = $props;

        return Text::make('Inherited');
    },
);
TemplateRenderer::render(
    TemplateCompiler::compile(
        '<Column textColor="#123456"><StyleBoundary /></Column>',
    ),
    null,
    [],
);
$assert(
    !array_key_exists('textColor', $capturedStyleBoundaryProps ?? [])
        && ($capturedStyleBoundaryProps['__pamInheritedStyles']['textColor'] ?? null)
            === 0xFF123456,
    'Inherited CSS must cross a component boundary as internal style context, not constructor props.',
);
$coreRoleElement = TemplateRenderer::render(
    TemplateCompiler::compile(
        '<Text accessibilityRole="header">Accessible heading</Text>',
    ),
    null,
    [],
);
$flexAliasElement = TemplateRenderer::render(
    TemplateCompiler::compile(
        '<Row alignItems="flex-start" alignSelf="flex-end" justifyContent="flex-end" />',
    ),
    null,
    [],
);
$assert(
    $flexAliasElement->properties()[PropKey::AlignItems->value] === 1
        && $flexAliasElement->properties()[PropKey::AlignSelf->value] === 3
        && $flexAliasElement->properties()[PropKey::JustifyContent->value] === 3,
    'Template flex-start and flex-end aliases must match native start and end layout values.',
);
$gridElement = TemplateRenderer::render(
    TemplateCompiler::compile(
        '<Grid gutterX="16" gutterY="8"><Column span="12" spanSm="6" spanMd="4"><Image source="cover.webp" aspectRatio="1" /><Pressable><Text>Open</Text></Pressable></Column><Column class="col-6 col-lg-3 offset-lg-1 order-md-2" /></Grid>',
    ),
    null,
    [],
);
$assert(
    $gridElement->properties()[PropKey::GridColumns->value] === 12
        && $gridElement->properties()[PropKey::GridColumnGap->value] === 16.0
        && $gridElement->properties()[PropKey::GridRowGap->value] === 8.0
        && $gridElement->children()[0]->properties()[PropKey::GridSpan->value] === 12
        && $gridElement->children()[0]->properties()[PropKey::GridSpanSm->value] === 6
        && $gridElement->children()[0]->properties()[PropKey::GridSpanMd->value] === 4
        && $gridElement->children()[1]->properties()[PropKey::GridSpan->value] === 6
        && $gridElement->children()[1]->properties()[PropKey::GridSpanLg->value] === 3
        && $gridElement->children()[1]->properties()[PropKey::GridOffsetLg->value] === 1
        && $gridElement->children()[1]->properties()[PropKey::GridOrderMd->value] === 2
        && $gridElement->children()[0]->children()[0]->kind() === NodeKind::Image
        && $gridElement->children()[0]->children()[1]->kind() === NodeKind::Pressable,
    'Responsive grids must compile rich cells identically from explicit properties and utility classes.',
);
$virtualGridTemplate = TemplateRenderer::render(
    TemplateCompiler::compile(
        '<VirtualGrid columns="2" rowHeight="220" prefetch="7"><Column key="photo-1"><Image source="one.webp" /><Text>One</Text></Column><Pressable key="photo-2"><Image source="two.webp" /></Pressable></VirtualGrid>',
    ),
    null,
    [],
);
$assert(
    $virtualGridTemplate->kind() === NodeKind::VirtualList
        && count($virtualGridTemplate->children()) === 2
        && $virtualGridTemplate->children()[0]->children()[0]->kind() === NodeKind::Image
        && $virtualGridTemplate->properties()[PropKey::ListNumColumns->value] === 2
        && $virtualGridTemplate->properties()[PropKey::ListRowHeight->value] === 220
        && $virtualGridTemplate->properties()[PropKey::ListPrefetch->value] === 7,
    'VirtualGrid markup must retain arbitrary keyed component trees and native list tuning.',
);
$assert(
    $coreRoleElement->properties()[PropKey::AccessibilityRole->value]
        === AccessibilityRole::Header->value,
    'Core tag accessibility roles must compile to sequential protocol integers.',
);
$pressScope = new class {
    public ?PressEvent $move = null;

    public function moved(PressEvent $event): void
    {
        $this->move = $event;
    }
};
$corePressableElement = TemplateRenderer::render(
    TemplateCompiler::compile(
        '<Pressable hitSlopLeft="4" hitSlopBottom="10" '
        .'pressRetentionOffset="12" delayLongPress="420" '
        .'androidDisableSound="true" rippleAlpha="0.5" '
        .'on:pressMove="moved"><Text>Open</Text></Pressable>',
    ),
    $pressScope,
    [],
);
$corePressableElement->events()[EventKind::PressMove->value](Wire::map([
    'x' => 2.0,
    'y' => 3.0,
    'pageX' => 12.0,
    'pageY' => 13.0,
    'timestamp' => 99,
    'pointerId' => 1,
]));
$assert(
    $corePressableElement->properties()[PropKey::HitSlopLeft->value] === 4.0
        && $corePressableElement
            ->properties()[PropKey::HitSlopBottom->value] === 10.0
        && $corePressableElement
            ->properties()[PropKey::PressRetentionLeft->value] === 12.0
        && $corePressableElement
            ->properties()[PropKey::PressRetentionBottom->value] === 12.0
        && $corePressableElement
            ->properties()[PropKey::PressDelayLongMs->value] === 420
        && $corePressableElement
            ->properties()[PropKey::PressAndroidDisableSound->value] === true
        && $corePressableElement
            ->properties()[PropKey::RippleAlpha->value] === 0.5
        && $pressScope->move instanceof PressEvent
        && $pressScope->move->pageX === 12.0,
    'Core Pressable tags must retain gesture properties and typed move events.',
);
$itemActionScope = new class {
    public string $selected = '';

    public function select(string $id): void
    {
        $this->selected = $id;
    }
};
$itemActionElement = TemplateRenderer::render(
    TemplateCompiler::compile(
        '<Column><Pressable v-for="$item in $items" '
        .'on:longPress="select($item[\'id\'])"><Text>{{ $item[\'id\'] }}</Text>'
        .'</Pressable></Column>',
    ),
    $itemActionScope,
    ['items' => [['id' => 'first'], ['id' => 'second']]],
);
$assert(
    $itemActionScope->selected === '',
    'Native event expressions must not execute during template rendering.',
);
$itemActionElement
    ->children()[1]
    ->events()[EventKind::LongPress->value]('');
$assert(
    $itemActionScope->selected === 'second',
    'Native event expressions must capture v-for item data until dispatch.',
);
$directiveScope = new class {
    /** @var list<string> */
    public array $calls = [];

    public function outside(): void { $this->calls[] = 'outside'; }
    public function intersect(): void { $this->calls[] = 'intersect'; }
    public function mutate(): void { $this->calls[] = 'mutate'; }
    public function resize(): void { $this->calls[] = 'resize'; }
    public function scroll(): void { $this->calls[] = 'scroll'; }
    public function touchStart(): void { $this->calls[] = 'touchStart'; }
    public function touchMove(): void { $this->calls[] = 'touchMove'; }
    public function touchEnd(): void { $this->calls[] = 'touchEnd'; }
};
$directiveElement = TemplateRenderer::render(
    TemplateCompiler::compile(
        '<View p-click-outside="outside" p-intersect="intersect" '
        .'p-mutate="mutate" p-resize="resize" p-scroll="scroll" '
        .'p-touch-start="touchStart" p-touch-move="touchMove" '
        .'p-touch-end="touchEnd"><Text>Directives</Text></View>',
    ),
    $directiveScope,
    [],
);
foreach ([
    EventKind::ClickOutside,
    EventKind::Intersect,
    EventKind::Mutate,
    EventKind::Resize,
    EventKind::Scroll,
    EventKind::TouchStart,
    EventKind::TouchMove,
    EventKind::TouchEnd,
] as $directiveEvent) {
    $directiveElement->events()[$directiveEvent->value]('');
}
$assert(
    $directiveScope->calls === [
        'outside',
        'intersect',
        'mutate',
        'resize',
        'scroll',
        'touchStart',
        'touchMove',
        'touchEnd',
    ],
    'Native p-* directives must compile to distinct append-only event handlers.',
);
$rippleDirective = TemplateRenderer::render(
    TemplateCompiler::compile(
        '<View p-ripple><Text>Ripple</Text></View>',
    ),
    null,
    [],
);
$assert(
    $rippleDirective->properties()[PropKey::RippleColor->value] === 0
        && $rippleDirective->properties()[PropKey::RippleAlpha->value] === 0.12
        && $rippleDirective->properties()[PropKey::RippleBorderless->value] === false
        && $rippleDirective->properties()[PropKey::RippleForeground->value] === false,
    'p-ripple must compile to a theme-aware native MD3 state layer.',
);
$modalScope = new class {
    public ?ModalOrientation $orientation = null;

    public function oriented(ModalOrientation $orientation): void
    {
        $this->orientation = $orientation;
    }
};
$coreModalElement = TemplateRenderer::render(
    TemplateCompiler::compile(
        '<Modal visible="true" presentation="fullScreen" '
        .'animationType="fade" transparent="true" '
        .'hardwareAccelerated="true" statusBarTranslucent="true" '
        .'on:orientationChange="oriented"><Text>Dialog</Text></Modal>',
    ),
    $modalScope,
    [],
);
$coreModalElement->events()[EventKind::ModalOrientationChange->value](
    (string) ModalOrientation::Landscape->value,
);
$assert(
    $coreModalElement->properties()[PropKey::ModalPresentation->value]
        === ModalPresentation::FullScreen->value
        && $coreModalElement->properties()[PropKey::ModalAnimationType->value]
            === ModalAnimationType::Fade->value
        && $coreModalElement->properties()[PropKey::ModalTransparent->value]
            === true
        && $coreModalElement
            ->properties()[PropKey::ModalHardwareAccelerated->value] === true
        && $coreModalElement
            ->properties()[PropKey::ModalStatusBarTranslucent->value] === true
        && $modalScope->orientation === ModalOrientation::Landscape,
    'Core Modal tags must preserve window properties and typed orientation events.',
);
$eventScope = new class {
    public string $submission = '';

    public function submit(string $payload): void
    {
        $this->submission = $payload;
    }
};
TemplateRegistry::component(
    'AdaptedEvent',
    static fn (array $_props, array $children): \Pam\Native\Renderable =>
        Column::make(...$children),
);
TemplateRegistry::eventAdapter(
    'AdaptedEvent',
    static fn (
        EventKind $_kind,
        Closure $handler,
        array $props,
    ): Closure => static function (string $payload) use (
        $handler,
        $props,
    ): void {
        $prefix = is_string($props['prefix'] ?? null)
            ? $props['prefix']
            : '';
        $handler($prefix.$payload);
    },
);
$adaptedEventTemplate = TemplateCompiler::compile(
    '<AdaptedEvent prefix="ui:" on:submit="submit" />',
);
$adaptedEventElement = TemplateRenderer::render(
    $adaptedEventTemplate,
    $eventScope,
    [],
);
$adaptedEventElement->events()[EventKind::Submit->value]('payload');
$assert(
    $eventScope->submission === 'ui:payload',
    'Registered components must be able to adapt declarative event contracts.',
);
$capturedEventContexts = null;
TemplateRegistry::component(
    'EventParent',
    static fn (array $_props, array $children): \Pam\Native\Renderable =>
        Column::make(...$children),
);
TemplateRegistry::component(
    'EventChild',
    static function (
        array $props,
    ) use (&$capturedEventContexts): \Pam\Native\Renderable {
        $capturedEventContexts = $props['__pamEventContexts'] ?? null;

        return Text::make('Child');
    },
);
$eventContextTemplate = TemplateCompiler::compile(
    '<EventParent value="framework" on:change="submit"><EventChild /></EventParent>',
);
TemplateRenderer::render($eventContextTemplate, $eventScope, []);
if (!is_array($capturedEventContexts)) {
    throw new RuntimeException('Declarative event contexts are missing.');
}
$eventContext = $capturedEventContexts['EventParent'] ?? null;
if (!is_array($eventContext)) {
    throw new RuntimeException('Declarative event context is missing.');
}
$eventContextEvents = $eventContext['events'] ?? null;
$eventContextProps = $eventContext['props'] ?? null;
$eventContextHandler = is_array($eventContextEvents)
    ? ($eventContextEvents[EventKind::Change->value] ?? null)
    : null;
if (!$eventContextHandler instanceof Closure) {
    throw new RuntimeException('Declarative event context handler is missing.');
}
if (!is_array($eventContextProps)) {
    throw new RuntimeException('Declarative event context props are missing.');
}
$eventContextHandler('laravel');
$assert(
    ($eventContextProps['value'] ?? null) === 'framework'
        && $eventScope->submission === 'laravel',
    'Registered child components must receive bounded ancestor event context.',
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

$structuralTree = Column::make(
    Text::make('B')->key('value'),
    Text::make('New')->key('new'),
)->key('content');
$structural = $incremental->encode($structuralTree);
$assert(
    $structural['frame'] !== null && str_starts_with($structural['frame'], 'PNP1'),
    'Structural changes after boot must use a patch frame.',
);
$incremental->forceFullFrame();
$recovery = $incremental->encode($structuralTree);
$assert(
    $recovery['full']
        && $recovery['frame'] !== null
        && str_starts_with($recovery['frame'], 'PNT1'),
    'A rejected patch must be recoverable with a complete frame.',
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

$captureError = null;
$captureSucceeded = false;
$captureRequestId = MediaCapture::capture(
    CaptureType::Photo,
    static function (FileReference $_) use (&$captureSucceeded): void {
        $captureSucceeded = true;
    },
    static function (string $message) use (&$captureError): void {
        $captureError = $message;
    },
);
Runtime::dispatchModuleResult(
    $captureRequestId,
    ModuleResultStatus::Failure->value,
    'Camera is unavailable.',
);
$assert(
    !$captureSucceeded && $captureError === 'Camera is unavailable.',
    'Media capture failures must reach the optional failure callback.',
);

$location = null;
$locationRequest = Location::current(
    static function (LocationPosition $position) use (&$location): void {
        $location = $position;
    },
    highAccuracy: false,
    timeoutMs: 4_000,
    maximumAgeMs: 12_000,
);
$locationCall = TestDiagnostics::$moduleCall;
$locationPayload = $locationCall === null
    ? []
    : Wire::decodeMap($locationCall['payload']);
$assert(
    $locationCall !== null
        && $locationCall['requestId'] === $locationRequest
        && $locationCall['module'] === 'location'
        && $locationCall['method'] === 'current'
        && $locationPayload === [
            'highAccuracy' => false,
            'maximumAgeMs' => 12_000,
            'timeoutMs' => 4_000,
        ],
    'Location facade did not emit its typed native module call.',
);
Runtime::dispatchModuleResult(
    $locationRequest,
    ModuleResultStatus::Success->value,
    Wire::map([
        'latitude' => -23.55052,
        'longitude' => -46.633308,
        'accuracy' => 3.5,
        'altitude' => 760.0,
        'speed' => 0.4,
        'bearing' => 90.0,
        'timestamp' => 1_785_000_000_000,
    ]),
);
$assert(
    $location instanceof LocationPosition
        && $location->latitude === -23.55052
        && $location->longitude === -46.633308
        && $location->accuracy === 3.5
        && $location->timestamp === 1_785_000_000_000,
    'Location facade did not decode the native position.',
);

$recording = null;
$audioRequest = AudioRecorder::stop(
    static function (AudioRecording $value) use (&$recording): void {
        $recording = $value;
    },
);
$assert(
    TestDiagnostics::$moduleCall !== null
        && TestDiagnostics::$moduleCall['requestId'] === $audioRequest
        && TestDiagnostics::$moduleCall['module'] === 'audio-recorder'
        && TestDiagnostics::$moduleCall['method'] === 'stop',
    'Audio recorder facade did not emit its typed native module call.',
);
Runtime::dispatchModuleResult(
    $audioRequest,
    ModuleResultStatus::Success->value,
    Wire::map([
        'uri' => 'file:///tmp/pam-voice-test.m4a',
        'relativePath' => 'recordings/pam-voice-test.m4a',
        'fileName' => 'pam-voice-test.m4a',
        'mimeType' => 'audio/mp4',
        'durationMs' => 2_400,
        'size' => 19_200,
    ]),
);
$assert(
    $recording instanceof AudioRecording
        && $recording->durationMs === 2_400
        && $recording->size === 19_200
        && $recording->relativePath === 'recordings/pam-voice-test.m4a'
        && $recording->mimeType === 'audio/mp4',
    'Audio recorder facade did not decode the native recording.',
);

$pickedFiles = [];
$pickManyRequest = Files::pickMany(
    MediaPickerType::Media,
    static function (array $files) use (&$pickedFiles): void {
        $pickedFiles = $files;
    },
    6,
);
$pickManyCall = TestDiagnostics::$moduleCall;
$pickManyPayload = $pickManyCall === null
    ? []
    : Wire::decodeMap($pickManyCall['payload']);
$assert(
    $pickManyCall !== null
        && $pickManyCall['requestId'] === $pickManyRequest
        && $pickManyCall['module'] === 'files'
        && $pickManyCall['method'] === 'pickMany'
        && $pickManyPayload === ['limit' => 6, 'type' => MediaPickerType::Media->value],
    'Files pickMany must emit a bounded typed native module call.',
);
Runtime::dispatchModuleResult(
    $pickManyRequest,
    ModuleResultStatus::Success->value,
    Wire::map([
        'items' => json_encode([
            [
                'path' => 'imports/photo-one.jpg',
                'name' => 'photo-one.jpg',
                'mimeType' => 'image/jpeg',
                'size' => 1_024,
            ],
            [
                'path' => 'imports/photo-two.webp',
                'name' => 'photo-two.webp',
                'mimeType' => 'image/webp',
                'size' => 2_048,
            ],
        ], JSON_THROW_ON_ERROR),
    ]),
);
$assert(
    count($pickedFiles) === 2
        && $pickedFiles[0] instanceof FileReference
        && $pickedFiles[0]->path === 'imports/photo-one.jpg'
        && $pickedFiles[0]->uri() === 'pam-file:///imports/photo-one.jpg'
        && $pickedFiles[1]->mimeType === 'image/webp'
        && $pickedFiles[1]->size === 2_048,
    'Files pickMany must decode every native file reference in selection order.',
);

$importedFile = null;
$importUriRequest = Files::importUri(
    'content://media/external/images/media/42',
    static function (FileReference $file) use (&$importedFile): void {
        $importedFile = $file;
    },
);
$importUriCall = TestDiagnostics::$moduleCall;
$assert(
    $importUriCall !== null
        && $importUriCall['requestId'] === $importUriRequest
        && $importUriCall['module'] === 'files'
        && $importUriCall['method'] === 'importUri'
        && Wire::decodeMap($importUriCall['payload']) === [
            'uri' => 'content://media/external/images/media/42',
        ],
    'Files importUri must emit a typed content URI import.',
);
Runtime::dispatchModuleResult(
    $importUriRequest,
    ModuleResultStatus::Success->value,
    Wire::map([
        'path' => 'imports/photo-42.jpg',
        'name' => 'photo-42.jpg',
        'mimeType' => 'image/jpeg',
        'size' => 4_096,
    ]),
);
$assert(
    $importedFile instanceof FileReference
        && $importedFile->path === 'imports/photo-42.jpg'
        && $importedFile->mimeType === 'image/jpeg'
        && $importedFile->size === 4_096,
    'Files importUri must decode the sandboxed file reference.',
);

$missingStoredValue = 'not-dispatched';
$storageRequest = Storage::get(
    'chat.draft.missing',
    static function (?string $value) use (&$missingStoredValue): void {
        $missingStoredValue = $value;
    },
);
Runtime::dispatchModuleResult(
    $storageRequest,
    ModuleResultStatus::Success->value,
    '',
);
$assert(
    $missingStoredValue === null,
    'Storage get must treat an empty successful payload as a cache miss.',
);

$batchRequestId = SQLite::executeMany(
    'nitro.db',
    'INSERT INTO messages (id, body) VALUES (?, ?)',
    [
        ['m1', 'fast'],
        ['m2', 'faster'],
    ],
);
$batchCall = TestDiagnostics::$moduleCall;
$batchPayload = $batchCall === null ? [] : Wire::decodeMap($batchCall['payload']);
$assert(
    $batchCall !== null
        && $batchCall['requestId'] === $batchRequestId
        && $batchCall['module'] === 'sqlite'
        && $batchCall['method'] === 'executeMany'
        && json_decode((string) ($batchPayload['arguments'] ?? ''), true) === [
            ['m1', 'fast'],
            ['m2', 'faster'],
        ],
    'SQLite executeMany did not emit one typed bridge call for the complete batch.',
);
Runtime::dispatchModuleResult(
    $batchRequestId,
    \Pam\Native\ModuleResultStatus::Success->value,
    '',
);

$transactionRequestId = SQLite::transaction(
    'nitro.db',
    [
        [
            'sql' => 'DELETE FROM messages WHERE chat_id = ?',
            'arguments' => ['chat-1'],
        ],
        [
            'sql' => 'INSERT INTO messages (id, chat_id, body) VALUES (?, ?, ?)',
            'argumentSets' => [
                ['m3', 'chat-1', 'atomic'],
                ['m4', 'chat-1', 'batched'],
            ],
        ],
    ],
);
$transactionCall = TestDiagnostics::$moduleCall;
$transactionPayload = $transactionCall === null
    ? []
    : Wire::decodeMap($transactionCall['payload']);
$assert(
    $transactionCall !== null
        && $transactionCall['requestId'] === $transactionRequestId
        && $transactionCall['module'] === 'sqlite'
        && $transactionCall['method'] === 'transaction'
        && json_decode((string) ($transactionPayload['arguments'] ?? ''), true) === [
            [
                'sql' => 'DELETE FROM messages WHERE chat_id = ?',
                'arguments' => ['chat-1'],
            ],
            [
                'sql' => 'INSERT INTO messages (id, chat_id, body) VALUES (?, ?, ?)',
                'arguments' => [],
                'argumentSets' => [
                    ['m3', 'chat-1', 'atomic'],
                    ['m4', 'chat-1', 'batched'],
                ],
            ],
        ],
    'SQLite transaction did not emit one typed bridge call for heterogeneous statements.',
);
Runtime::dispatchModuleResult(
    $transactionRequestId,
    \Pam\Native\ModuleResultStatus::Success->value,
    '',
);

$contacts = null;
$contactsRequestId = Contacts::all(static function (array $items) use (&$contacts): void {
    $contacts = $items;
});
$contactsCall = TestDiagnostics::$moduleCall;
$assert(
    $contactsCall !== null
        && $contactsCall['requestId'] === $contactsRequestId
        && $contactsCall['module'] === 'contacts'
        && $contactsCall['method'] === 'list'
        && Wire::decodeMap($contactsCall['payload']) === ['offset' => 0, 'limit' => 250],
    'Contacts facade did not request the first bounded native page.',
);
Runtime::dispatchModuleResult(
    $contactsRequestId,
    \Pam\Native\ModuleResultStatus::Success->value,
    Wire::map([
        'items' => json_encode([[
            'id' => '42',
            'displayName' => 'Ada Lovelace',
            'givenName' => 'Ada',
            'familyName' => 'Lovelace',
            'phoneNumbers' => ['+5511999999999'],
            'emailAddresses' => ['ada@example.test'],
        ]], JSON_THROW_ON_ERROR),
        'hasMore' => false,
    ]),
);

$mediaPage = null;
$mediaRequestId = MediaLibrary::assets(
    MediaPickerType::Media,
    static function (MediaAssetPage $page) use (&$mediaPage): void {
        $mediaPage = $page;
    },
    limit: 80,
    offset: 160,
    albumId: 'camera',
);
$mediaCall = TestDiagnostics::$moduleCall;
$assert(
    $mediaCall !== null
        && $mediaCall['requestId'] === $mediaRequestId
        && $mediaCall['module'] === 'media-library'
        && $mediaCall['method'] === 'assets'
        && Wire::decodeMap($mediaCall['payload']) === [
            'albumId' => 'camera',
            'limit' => 80,
            'offset' => 160,
            'type' => MediaPickerType::Media->value,
        ],
    'MediaLibrary assets must emit a bounded paginated native query.',
);
Runtime::dispatchModuleResult(
    $mediaRequestId,
    ModuleResultStatus::Success->value,
    Wire::map([
        'items' => json_encode([[
            'id' => '42',
            'uri' => 'content://media/external/images/media/42',
            'name' => 'photo-42.jpg',
            'mimeType' => 'image/jpeg',
            'width' => 1_080,
            'height' => 1_350,
            'durationMs' => 0,
            'size' => 4_096,
            'createdAt' => 1_785_000_000_000,
            'modifiedAt' => 1_785_000_100_000,
            'albumId' => 'camera',
            'albumTitle' => 'Câmera',
            'favorite' => true,
        ]], JSON_THROW_ON_ERROR),
        'hasMore' => true,
    ]),
);
$assert(
    $mediaPage instanceof MediaAssetPage
        && $mediaPage->hasMore
        && $mediaPage->nextOffset === 161
        && count($mediaPage->items) === 1
        && $mediaPage->items[0] instanceof MediaAsset
        && $mediaPage->items[0]->id === '42'
        && $mediaPage->items[0]->width === 1_080
        && $mediaPage->items[0]->favorite
        && !$mediaPage->items[0]->video(),
    'MediaLibrary assets must decode typed media metadata and pagination.',
);

$mediaAlbums = null;
$albumRequestId = MediaLibrary::albums(
    MediaPickerType::Image,
    static function (array $items) use (&$mediaAlbums): void {
        $mediaAlbums = $items;
    },
);
$albumCall = TestDiagnostics::$moduleCall;
$assert(
    $albumCall !== null
        && $albumCall['requestId'] === $albumRequestId
        && $albumCall['module'] === 'media-library'
        && $albumCall['method'] === 'albums'
        && Wire::decodeMap($albumCall['payload']) === [
            'type' => MediaPickerType::Image->value,
        ],
    'MediaLibrary albums must emit its typed media filter.',
);
Runtime::dispatchModuleResult(
    $albumRequestId,
    ModuleResultStatus::Success->value,
    Wire::map([
        'items' => json_encode([[
            'id' => 'camera',
            'title' => 'Câmera',
            'count' => 42,
            'coverUri' => 'content://media/external/images/media/42',
        ]], JSON_THROW_ON_ERROR),
    ]),
);
$assert(
    is_array($mediaAlbums)
        && count($mediaAlbums) === 1
        && $mediaAlbums[0] instanceof MediaAlbum
        && $mediaAlbums[0]->id === 'camera'
        && $mediaAlbums[0]->count === 42,
    'MediaLibrary albums must decode typed album metadata.',
);

$incomingShare = null;
$incomingShareRequestId = IncomingShares::initial(
    static function (?IncomingShare $share) use (&$incomingShare): void {
        $incomingShare = $share;
    },
);
$incomingShareCall = TestDiagnostics::$moduleCall;
$assert(
    $incomingShareCall !== null
        && $incomingShareCall['requestId'] === $incomingShareRequestId
        && $incomingShareCall['module'] === 'incoming-share'
        && $incomingShareCall['method'] === 'initial',
    'IncomingShares facade did not request the cold-start payload.',
);
Runtime::dispatchModuleResult(
    $incomingShareRequestId,
    \Pam\Native\ModuleResultStatus::Success->value,
    Wire::map([
        'available' => true,
        'text' => 'Shared caption',
        'subject' => 'Shared subject',
        'mimeType' => 'image/jpeg',
        'files' => json_encode([[
            'path' => '/data/user/0/app/cache/pam-incoming-shares/incoming-photo.jpg',
            'name' => 'photo.jpg',
            'mimeType' => 'image/jpeg',
            'size' => 1234,
        ]], JSON_THROW_ON_ERROR),
    ]),
);
$assert(
    $incomingShare instanceof IncomingShare
        && $incomingShare->text === 'Shared caption'
        && count($incomingShare->files) === 1
        && $incomingShare->files[0]->name === 'photo.jpg'
        && $incomingShare->files[0]->size === 1234,
    'IncomingShares facade did not decode the sandboxed file payload.',
);

$cacheUsage = null;
$cacheRequestId = Caches::usage(
    static function (\Pam\Native\CacheUsage $usage) use (&$cacheUsage): void {
        $cacheUsage = $usage;
    },
);
$cacheCall = TestDiagnostics::$moduleCall;
$assert(
    $cacheCall !== null
        && $cacheCall['requestId'] === $cacheRequestId
        && $cacheCall['module'] === 'cache'
        && $cacheCall['method'] === 'usage',
    'Caches facade did not request native cache usage.',
);
Runtime::dispatchModuleResult(
    $cacheRequestId,
    \Pam\Native\ModuleResultStatus::Success->value,
    Wire::map([
        'fileCount' => 12,
        'freedBytes' => 0,
        'imageBytes' => 1024,
        'mediaBytes' => 4096,
        'temporaryBytes' => 128,
        'totalBytes' => 5248,
    ]),
);
$assert(
    $cacheUsage instanceof \Pam\Native\CacheUsage
        && $cacheUsage->fileCount === 12
        && $cacheUsage->totalBytes === 5248,
    'Caches facade did not decode typed native cache usage.',
);

$cacheCleared = null;
$cacheClearRequestId = Caches::clear(
    static function (\Pam\Native\CacheUsage $usage) use (&$cacheCleared): void {
        $cacheCleared = $usage;
    },
);
$cacheClearCall = TestDiagnostics::$moduleCall;
$assert(
    $cacheClearCall !== null
        && $cacheClearCall['module'] === 'cache'
        && $cacheClearCall['method'] === 'clear'
        && Wire::decodeMap($cacheClearCall['payload']) === ['preserveOffline' => true],
    'Caches clear must preserve pinned offline media by default.',
);
Runtime::dispatchModuleResult(
    $cacheClearRequestId,
    \Pam\Native\ModuleResultStatus::Success->value,
    Wire::map([
        'fileCount' => 2,
        'freedBytes' => 4096,
        'imageBytes' => 0,
        'mediaBytes' => 1024,
        'temporaryBytes' => 0,
        'totalBytes' => 1024,
    ]),
);
$assert(
    $cacheCleared instanceof \Pam\Native\CacheUsage
        && $cacheCleared->freedBytes === 4096
        && $cacheCleared->totalBytes === 1024,
    'Caches clear did not decode the reclaimed byte count.',
);
$assert(
    is_array($contacts)
        && count($contacts) === 1
        && $contacts[0] instanceof Contact
        && $contacts[0]->displayName === 'Ada Lovelace'
        && $contacts[0]->phoneNumbers === ['+5511999999999'],
    'Contacts facade did not decode native contacts into typed values.',
);

$httpResponse = null;
$httpRequestId = Http::json(
    method: 'post',
    url: 'https://api.example.test/login',
    data: ['email' => 'person@example.test', 'password' => 'secret'],
    callback: static function (HttpResponse $response) use (&$httpResponse): void {
        $httpResponse = $response;
    },
    headers: ['Authorization' => 'Bearer access-token'],
    timeoutMs: 45_000,
);
$httpCall = TestDiagnostics::$moduleCall;
$httpPayload = Wire::decodeMap($httpCall['payload'] ?? '');
$httpHeaders = json_decode((string) ($httpPayload['headers'] ?? ''), true, flags: JSON_THROW_ON_ERROR);
$httpBody = json_decode((string) ($httpPayload['body'] ?? ''), true, flags: JSON_THROW_ON_ERROR);
$assert(
    $httpCall !== null
        && $httpCall['requestId'] === $httpRequestId
        && $httpCall['module'] === 'http'
        && $httpCall['method'] === 'request'
        && $httpPayload['url'] === 'https://api.example.test/login'
        && $httpPayload['method'] === 'POST'
        && $httpPayload['timeoutMs'] === 45_000
        && $httpHeaders === [
            'Accept' => 'application/json',
            'Content-Type' => 'application/json',
            'Authorization' => 'Bearer access-token',
        ]
        && $httpBody === ['email' => 'person@example.test', 'password' => 'secret'],
    'Generic HTTP JSON request did not preserve method, URL, headers, body and timeout.',
);
Runtime::dispatchModuleResult(
    $httpRequestId,
    \Pam\Native\ModuleResultStatus::Success->value,
    Wire::map(['statusCode' => 201, 'body' => '{"token":"access-token"}']),
);
$assert(
    $httpResponse instanceof HttpResponse
        && $httpResponse->statusCode === 201
        && $httpResponse->successful()
        && $httpResponse->body === '{"token":"access-token"}',
    'Generic HTTP request did not decode its response.',
);

$transportFailure = null;
$transportFailureId = Http::get(
    'https://offline.example.test',
    static function (HttpResponse $response) use (&$transportFailure): void {
        $transportFailure = $response;
    },
);
Runtime::dispatchModuleResult(
    $transportFailureId,
    \Pam\Native\ModuleResultStatus::Failure->value,
    'Unable to resolve host.',
);
$assert(
    $transportFailure instanceof HttpResponse
        && $transportFailure->statusCode === 0
        && $transportFailure->body === ''
        && $transportFailure->error === 'Unable to resolve host.'
        && $transportFailure->transportFailed()
        && !$transportFailure->successful(),
    'HTTP transport failures must reach the callback without crashing the runtime.',
);

foreach (['post' => 'POST', 'put' => 'PUT', 'patch' => 'PATCH', 'delete' => 'DELETE'] as $helper => $method) {
    Http::{$helper}(
        'https://api.example.test/resource',
        static function (HttpResponse $response): void {},
        ['Authorization' => 'Bearer access-token'],
        '{"enabled":true}',
    );
    $helperCall = TestDiagnostics::$moduleCall;
    $helperPayload = Wire::decodeMap($helperCall['payload'] ?? '');
    $assert(
        $helperCall !== null
            && $helperCall['method'] === 'request'
            && $helperPayload['method'] === $method
            && $helperPayload['body'] === '{"enabled":true}',
        "HTTP {$method} helper did not emit a generic native request.",
    );
}

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
    'appearance' => UserInterfaceAppearance::Dark->value,
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
        && $windowMetrics->density === 3.0
        && $windowMetrics->appearance === UserInterfaceAppearance::Dark,
    'Window metrics lifecycle event was not decoded.',
);
$assert(
    $memoryPressure === MemoryPressure::Critical,
    'Memory-pressure lifecycle event was not decoded.',
);
Runtime::shutdown();

$deviceInfo = new DeviceInfo(
    width: 412.0,
    height: 915.0,
    density: 3.0,
    appearance: UserInterfaceAppearance::Dark,
    appState: AppState::Active,
    safeAreaTop: 32.0,
    safeAreaRight: 0.0,
    safeAreaBottom: 24.0,
    safeAreaLeft: 0.0,
);
$assert(
    $deviceInfo->safeAreaTop === 32.0
        && $deviceInfo->safeAreaBottom === 24.0,
    'Device info must expose platform safe-area insets in logical points.',
);
$legacyDeviceInfo = new DeviceInfo(
    width: 360.0,
    height: 800.0,
    density: 2.0,
    appearance: UserInterfaceAppearance::Light,
    appState: AppState::Active,
);
$assert(
    $legacyDeviceInfo->safeAreaTop === 0.0
        && $legacyDeviceInfo->safeAreaRight === 0.0
        && $legacyDeviceInfo->safeAreaBottom === 0.0
        && $legacyDeviceInfo->safeAreaLeft === 0.0,
    'Device info safe-area additions must preserve constructor compatibility.',
);

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
        "minimum": "0.3.0",
        "maximumExclusive": "0.6.0"
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

$pamPhpDirectory = sys_get_temp_dir().'/pam-native-sfc-tests-'.getmypid();
$pamPhpCache = $pamPhpDirectory.'/.cache';

if (
    !is_dir($pamPhpDirectory)
    && !mkdir($pamPhpDirectory, 0o755, true)
    && !is_dir($pamPhpDirectory)
) {
    throw new RuntimeException('Cannot create the .pam.php fixture directory.');
}

file_put_contents(
    $pamPhpDirectory.'/CounterCard.pam.php',
    <<<'PAM'
<?php

declare(strict_types=1);

namespace Pam\Native\Tests\Sfc;

use Pam\Native\Attributes\State;
use Pam\Native\Component;

final class CounterCard extends Component
{
    /** @var list<string> */
    public static array $lifecycle = [];

    #[State]
    public int $count = 0;

    #[State]
    public bool $enabled = false;
    public string $draft = '';

    /** @var list<string> */
    public array $items = ['One', 'Two'];

    public int $repeatCount = 3;

    public function __construct(
        public string $title,
        public ?string $subtitle = null,
        public bool $elevated = false,
    ) {
    }

    public function boot(): void
    {
        self::$lifecycle[] = 'boot';
    }

    public function mount(): void
    {
        self::$lifecycle[] = 'mount';
    }

    public function attached(): void
    {
        self::$lifecycle[] = 'attached';
    }

    public function resumed(): void
    {
        self::$lifecycle[] = 'resumed';
    }

    public function updated(string $property): void
    {
        self::$lifecycle[] = 'updated:'.$property;
    }

    public function paused(): void
    {
        self::$lifecycle[] = 'paused';
    }

    public function unmount(): void
    {
        self::$lifecycle[] = 'unmount';
    }

    public function increment(): void
    {
        $this->count++;
        $this->emit('changed', $this->count);
    }

    /** @return array<array-key, string|bool> */
    public function cardClasses(): array
    {
        return [
            'p-4',
            'gap-2',
            'elevation-2' => $this->elevated,
        ];
    }
}
?>

<template>
    <Column :class="cardClasses()">
        <Text
            :class="$elevated ? 'profile-title' : ''"
            fontSize="16"
        >{{ $title }}</Text>
        <Text p-if="$subtitle">{{ $subtitle }}</Text>
        <Text p-for="$item in $items">{{ $item }}</Text>
        <Text p-for="$number in $repeatCount">Repeat {{ $number }}</Text>
        <Text p-for="$_ in 0">Never rendered</Text>
        <Button @press="increment">
            {{ $count === 0 ? 'Ready' : $count }}
        </Button>
        <Switch bind:checked="$enabled" />
        <Input bind:value="$draft" />
        <Slot name="action">
            <Text>Fallback action</Text>
        </Slot>
        <Slot />
    </Column>
</template>

<style scoped>
    @font-face {
        font-family: "Brand";
        src: url("asset://assets/fonts/Brand-Bold.ttf");
        font-weight: 700;
    }

    Text {
        color: #222222;
        font-size: 13px;
    }

    .profile-title {
        color: #1B7A4E;
        font-family: "Brand";
        font-size: 15px;
        font-weight: 700;
        line-height: 18px;
    }
</style>
PAM,
);
file_put_contents(
    $pamPhpDirectory.'/Dashboard.pam.php',
    <<<'PAM'
<?php

declare(strict_types=1);

namespace Pam\Native\Tests\Sfc;

use Pam\Native\Component;

final class Dashboard extends Component
{
    public string $title = 'PAM Native';
    public int $changes = 0;

    public function changed(int $count): void
    {
        $this->changes = $count;
        $this->title = 'Changed';
    }

}
?>

<template>
    <CounterCard
        :title="$title"
        subtitle="Compiled .pam.php"
        :elevated="true"
        @changed="changed"
    >
        <template #action>
            <Text>Named action</Text>
        </template>

        <Text>Default slot</Text>
    </CounterCard>
</template>
PAM,
);
file_put_contents(
    $pamPhpDirectory.'/ConditionalRoot.pam.php',
    <<<'PAM'
<?php

declare(strict_types=1);

namespace Pam\Native\Tests\Sfc;

use Pam\Native\Component;

final class ConditionalRoot extends Component
{
    public bool $visible = false;
}
?>

<template>
    <View p-if="$visible">
        <Text>Visible</Text>
    </View>
</template>
PAM,
);
file_put_contents(
    $pamPhpDirectory.'/InheritedText.pam.php',
    <<<'PAM'
<?php

declare(strict_types=1);

namespace Pam\Native\Tests\Sfc;

use Pam\Native\Component;

final class InheritedText extends Component
{
}
?>

<template>
    <Text>Inherited component text</Text>
</template>
PAM,
);
file_put_contents(
    $pamPhpDirectory.'/InheritedStyleHost.pam.php',
    <<<'PAM'
<?php

declare(strict_types=1);

namespace Pam\Native\Tests\Sfc;

use Pam\Native\Component;

final class InheritedStyleHost extends Component
{
}
?>

<template>
    <Column textColor="#123456" fontSize="17">
        <InheritedText />
    </Column>
</template>
PAM,
);

$invalidPamPhpDirectory = $pamPhpDirectory.'-invalid';
if (
    !is_dir($invalidPamPhpDirectory)
    && !mkdir($invalidPamPhpDirectory, 0o755, true)
    && !is_dir($invalidPamPhpDirectory)
) {
    throw new RuntimeException('Cannot create the invalid component fixture directory.');
}
file_put_contents(
    $invalidPamPhpDirectory.'/Invalid.pam.php',
    <<<'PAM'
<?php

declare(strict_types=1);

namespace Pam\Native\Tests\Sfc;

use Pam\Native\Component;

final class Invalid extends Component
{
}
?>

<template>
    <Text invalid="unterminated></Text>
</template>
PAM,
);
TestDiagnostics::$messages = [];
$prebootError = null;
try {
    App::components($invalidPamPhpDirectory, $invalidPamPhpDirectory.'/.cache');
} catch (RuntimeException $error) {
    $prebootError = $error;
}
$assert(
    $prebootError instanceof RuntimeException
        && isset(TestDiagnostics::$messages[0])
        && str_starts_with(TestDiagnostics::$messages[0], "PAMERR1\n")
        && str_contains(TestDiagnostics::$messages[0], 'Invalid attributes'),
    'Component compilation errors before runtime boot must emit structured diagnostics.',
);
unlink($invalidPamPhpDirectory.'/Invalid.pam.php');
rmdir($invalidPamPhpDirectory.'/.cache');
rmdir($invalidPamPhpDirectory);
TestDiagnostics::$messages = [];

$assert(
    \Pam\Native\Internal\TemplateExpression::evaluate(
        '$left && $right',
        null,
        ['left' => false, 'right' => true],
    ) === false,
    'Template logical AND must consume its right operand when the left operand is false.',
);
$assert(
    \Pam\Native\Internal\TemplateExpression::evaluate(
        '$left || $right',
        null,
        ['left' => true, 'right' => false],
    ) === true,
    'Template logical OR must consume its right operand when the left operand is true.',
);
$assert(
    \Pam\Native\Internal\TemplateExpression::evaluate(
        'count($selected)',
        null,
        ['selected' => ['message-1', 'message-2']],
    ) === 2,
    'Template expressions must expose the safe count() collection helper.',
);
$assert(
    \Pam\Native\Internal\TemplateExpression::evaluate(
        'in_array($messageId, $selected, true)',
        null,
        [
            'messageId' => 'message-2',
            'selected' => ['message-1', 'message-2'],
        ],
    ) === true,
    'Template expressions must expose strict in_array() membership checks.',
);
$assert(
    \Pam\Native\Internal\TemplateExpression::evaluate(
        '72 + $bottomSpacing * 2',
        null,
        ['bottomSpacing' => 12],
    ) === 96,
    'Template arithmetic must preserve multiplication precedence over addition.',
);
$assert(
    \Pam\Native\Internal\TemplateExpression::evaluate(
        '($mediaIndex + 1) % 3',
        null,
        ['mediaIndex' => 2],
    ) === 0,
    'Template arithmetic must support grouping and integer modulo.',
);
$assert(
    \Pam\Native\Internal\TemplateExpression::evaluate(
        "'@'.\$username.' · '.(\$count + 1)",
        null,
        ['username' => 'pam', 'count' => 2],
    ) === '@pam · 3',
    'Template expressions must support PHP string concatenation with arithmetic precedence.',
);
$assert(
    \Pam\Native\Internal\TemplateExpression::evaluate(
        '$props.title',
        null,
        ['props' => ['title' => 'Profile']],
    ) === 'Profile',
    'Template dot-property paths must remain distinct from string concatenation.',
);
$assert(
    \Pam\Native\Internal\TemplateExpression::evaluate(
        '$profile["bio"] ?? $fallback ?? "Sem bio"',
        null,
        ['profile' => [], 'fallback' => null],
    ) === 'Sem bio',
    'Template null coalescing must be right associative and tolerate a missing array key.',
);
$assert(
    \Pam\Native\Internal\TemplateExpression::evaluate(
        '$profile["details"]["website"] ?? "Sem site"',
        null,
        ['profile' => []],
    ) === 'Sem site',
    'Template null coalescing must safely traverse a missing nested path.',
);
$assert(
    \Pam\Native\Internal\TemplateExpression::evaluate(
        '$profile["bio"] ?? "Sem bio"',
        null,
        ['profile' => ['bio' => 'PAM']],
    ) === 'PAM',
    'Template null coalescing must preserve a present non-null value.',
);
$missingWithoutCoalescingRejected = false;
try {
    \Pam\Native\Internal\TemplateExpression::evaluate(
        '$profile["bio"]',
        null,
        ['profile' => []],
    );
} catch (RuntimeException $error) {
    $missingWithoutCoalescingRejected = str_contains(
        $error->getMessage(),
        'Cannot resolve template value',
    );
}
$assert(
    $missingWithoutCoalescingRejected,
    'Missing template values must remain strict outside null coalescing.',
);
$concatenationRejected = false;
try {
    \Pam\Native\Internal\TemplateExpression::evaluate(
        "'items: '.\$items",
        null,
        ['items' => ['one']],
    );
} catch (RuntimeException $error) {
    $concatenationRejected = str_contains(
        $error->getMessage(),
        'requires scalar, null, or Stringable operands',
    );
}
$assert(
    $concatenationRejected,
    'Template concatenation must reject arrays instead of coercing them with a warning.',
);

TemplateRegistry::reset();
App::components($pamPhpDirectory, $pamPhpCache);
$dashboardClass = 'Pam\\Native\\Tests\\Sfc\\Dashboard';
$counterClass = 'Pam\\Native\\Tests\\Sfc\\CounterCard';
$conditionalRoot = App::make('Pam\\Native\\Tests\\Sfc\\ConditionalRoot')->toElement();
$inheritedStyleHost = App::make(
    'Pam\\Native\\Tests\\Sfc\\InheritedStyleHost',
)->toElement();
$inheritedText = $inheritedStyleHost->children()[0] ?? null;
$assert(
    $inheritedText instanceof \Pam\Native\Element
        && $inheritedText->properties()[PropKey::TextColor->value] === 0xFF123456
        && (float) $inheritedText->properties()[PropKey::FontSize->value] === 17.0,
    'Inherited CSS must remain active inside a compiled child component.',
);
$assert(
    $conditionalRoot->properties()[PropKey::Visible->value] === false
        && $conditionalRoot->children() === [],
    'A false conditional root must render an inert zero-layout placeholder.',
);
$dashboard = App::make($dashboardClass);
App::run($dashboard);
$sfcElement = $dashboard->toElement();
$sfcEncoded = (new TreeEncoder())->encode($sfcElement);
$pressKey = null;
$toggleKey = null;
$inputKey = null;

foreach (array_keys($sfcEncoded['callbacks']) as $callbackKey) {
    [, $kind] = array_map('intval', explode(':', $callbackKey));
    if ($kind === EventKind::Press->value) {
        $pressKey = $callbackKey;
    } elseif ($kind === EventKind::Toggle->value) {
        $toggleKey = $callbackKey;
    } elseif ($kind === EventKind::Change->value) {
        $inputKey = $callbackKey;
    }
}

$assert($pressKey !== null, '.pam.php @press event was not compiled.');
$assert($toggleKey !== null, '.pam.php bind:checked event was not compiled.');
$assert($inputKey !== null, '.pam.php bind:value event was not compiled.');
$assert(
    count($sfcElement->children()) === 12,
    '.pam.php p-if, iterable/integer p-for and slots did not compose correctly.',
);
$assert(
    $sfcElement->children()[0]->properties()[PropKey::FontFamily->value]
        === 'asset://assets/fonts/Brand-Bold.ttf'
        && (float) $sfcElement->children()[0]
            ->properties()[PropKey::FontSize->value] === 16.0
        && (float) $sfcElement->children()[0]
            ->properties()[PropKey::LineHeight->value] === 18.0
        && $sfcElement->children()[0]->properties()[PropKey::TextColor->value]
            === 0xFF1B7A4E,
    '.pam.php scoped CSS must cascade tag, dynamic class and inline properties.',
);
$assert(
    array_map(
        static fn (\Pam\Native\Element $element): mixed =>
            $element->properties()[PropKey::Text->value] ?? null,
        array_slice($sfcElement->children(), 4, 3),
    ) === ['Repeat 1', 'Repeat 2', 'Repeat 3'],
    '.pam.php integer p-for must expose a one-based loop value.',
);
$assert(
    $counterClass::$lifecycle === ['boot', 'mount', 'attached', 'resumed'],
    '.pam.php component lifecycle did not mount on the committed render.',
);

if ($pressKey === null) {
    throw new RuntimeException('.pam.php press callback is missing.');
}
[$pressNode, $pressKind] = array_map('intval', explode(':', $pressKey));
Runtime::dispatchEvent($pressNode, $pressKind, '');
$afterPress = $dashboard->toElement();
$assert(
    $afterPress->children()[0]->properties()[PropKey::Text->value] === 'Changed',
    'Component emit() did not reach the parent @event handler.',
);
$assert(
    in_array('updated:title', $counterClass::$lifecycle, true),
    'Constructor prop changes must update a stable nested component.',
);

$rerendered = $dashboard->toElement();
$rerenderedEncoded = (new TreeEncoder())->encode($rerendered);
$newToggleKey = null;
foreach (array_keys($rerenderedEncoded['callbacks']) as $callbackKey) {
    [, $kind] = array_map('intval', explode(':', $callbackKey));
    if ($kind === EventKind::Toggle->value) {
        $newToggleKey = $callbackKey;
        break;
    }
}
if ($newToggleKey === null) {
    throw new RuntimeException('Rerendered .pam.php toggle callback is missing.');
}
[$toggleNode, $toggleKind] = array_map('intval', explode(':', $newToggleKey));
Runtime::dispatchEvent($toggleNode, $toggleKind, '1');
$assert(
    $dashboard->toElement()->children()[8]
        ->properties()[PropKey::Checked->value] === true,
    'bind:checked must update component state and rerender the native toggle.',
);
$assert(
    in_array('updated:enabled', $counterClass::$lifecycle, true),
    'bind:checked must invoke the component updated lifecycle hook.',
);

if ($inputKey === null) {
    throw new RuntimeException('.pam.php input callback is missing.');
}
[$inputNode, $inputKind] = array_map('intval', explode(':', $inputKey));
Runtime::dispatchEvent($inputNode, $inputKind, 'Offline draft');
$afterInput = $dashboard->toElement();
$assert(
    $afterInput->children()[9]->properties()[PropKey::Value->value] === 'Offline draft',
    'bind:value must update component state and rerender the native input.',
);
$assert(
    in_array('updated:draft', $counterClass::$lifecycle, true),
    'bind:value must invoke the component updated lifecycle hook.',
);

Runtime::shutdown();
$assert(
    array_slice($counterClass::$lifecycle, -2) === ['paused', 'unmount'],
    '.pam.php component lifecycle did not clean up on runtime shutdown.',
);

$globalCallRejected = false;
try {
    TemplateRenderer::render(
        TemplateCompiler::compile('<Text>{{ system(\'id\') }}</Text>'),
        new class {
        },
        [],
    );
} catch (RuntimeException) {
    $globalCallRejected = true;
}
$assert(
    $globalCallRejected,
    '.pam.php expressions must never call global PHP functions.',
);

$navigator = new Navigator(
    initialRoute: 'home',
    routes: [
        'home' => static fn () => Screen::make(Text::make('Home')),
        'details' => static fn () => Screen::make(Text::make('Details')),
    ],
    transition: NavigationTransition::Fade,
    transitionDurationMs: 300,
);
$assert($navigator->render()->toElement()->kind() === NodeKind::NavigationHost, 'Navigator must render a native host.');
$navigator->push('details');
$pushed = $navigator->render()->toElement();
$assert(count($pushed->children()) === 2, 'Push must retain both screens for the native transition.');
$assert(
    $pushed->properties()[PropKey::NavigationOperation->value] === NavigationOperation::Push->value,
    'Push operation was not sent to the native host.',
);
$assert($navigator->pop(), 'Navigator must pop a secondary route.');
$popped = $navigator->render()->toElement();
$assert(count($popped->children()) === 2, 'Pop must retain its outgoing screen until native animation completes.');
$assert($navigator->currentRoute() === 'home', 'Pop must reveal the previous route.');
$systemBackConsumed = false;
$navigator->interceptSystemBack(static function () use (&$systemBackConsumed): bool {
    $systemBackConsumed = true;

    return true;
});
$assert(
    $navigator->consumeSystemBack() && $systemBackConsumed,
    'Navigator system Back interceptor must consume transient UI before changing routes.',
);
$navigator->interceptSystemBack(static fn (): bool => false);
$assert(
    !$navigator->consumeSystemBack(),
    'Navigator system Back interceptor must allow route navigation when it returns false.',
);
$navigator->interceptSystemBack(null);
$assert(
    !$navigator->consumeSystemBack(),
    'Navigator system Back interceptor must be removable.',
);
$fluentNavigator = Router::stack('home')
    ->route('home', static fn () => Screen::make(Text::make('Home')))
    ->transitions(NavigationTransition::Scale, 180)
    ->build();
$assert($fluentNavigator->currentRoute() === 'home', 'Fluent Router must build its initial stack.');

$advancedNavigator = Router::stack('home')
    ->route('home', static fn () => Screen::make(Text::make('Home')))
    ->route(
        'profile',
        static fn (RouteContext $route) => Screen::make(
            Text::make('Profile '.$route->integer('id')),
        ),
    )
    ->route(
        'article',
        static fn (RouteContext $route) => Screen::make(
            Text::make($route->string('slug') ?? ''),
        ),
    )
    ->deepLink('/articles/{slug}', 'article')
    ->build();
$advancedNavigator->push('profile', ['id' => 42, 'preview' => true]);
$assert(
    $advancedNavigator->current()->integer('id') === 42
        && $advancedNavigator->current()->boolean('preview') === true
        && $advancedNavigator->render()->toElement()->children()[1]
            ->children()[0]->properties()[PropKey::Text->value] === 'Profile 42',
    'Route contexts must expose bounded typed parameters to screen factories.',
);
$advancedNavigator->push('article', ['slug' => 'temporary']);
$assert(
    $advancedNavigator->popTo('profile')
        && $advancedNavigator->currentRoute() === 'profile',
    'popTo must remove intermediate routes and retain the target entry.',
);
$savedNavigation = $advancedNavigator->saveState();
$restoredNavigator = Router::stack('home')
    ->route('home', static fn () => Screen::make(Text::make('Home')))
    ->route('profile', static fn (RouteContext $route) => Screen::make(
        Text::make((string) $route->integer('id')),
    ))
    ->build();
$restoredNavigator->restoreState($savedNavigation);
$assert(
    $restoredNavigator->current()->integer('id') === 42,
    'Navigator persistence must preserve route parameters.',
);
$assert(
    $advancedNavigator->open('pam://docs/articles/native%20grid?source=notification')
        && $advancedNavigator->currentRoute() === 'article'
        && $advancedNavigator->current()->string('slug') === 'native grid'
        && $advancedNavigator->current()->string('source') === 'notification',
    'Deep links must resolve encoded path and scalar query parameters.',
);
$hostNavigator = Router::stack('home')
    ->route('home', static fn () => Screen::make(Text::make('Home')))
    ->route('profile', static fn () => Screen::make(Text::make('Profile')))
    ->deepLink('/profile/{username}', 'profile')
    ->build();
$assert(
    $hostNavigator->open('pushin://profile/david?source=share')
        && $hostNavigator->currentRoute() === 'profile'
        && $hostNavigator->current()->string('username') === 'david'
        && $hostNavigator->current()->string('source') === 'share',
    'Custom-scheme deep links must also match host-plus-path route patterns.',
);
$assert(
    $advancedNavigator->popToTop()
        && $advancedNavigator->currentRoute() === 'home',
    'popToTop must restore the first stack entry.',
);

$tabNavigator = Router::tabs('home')
    ->tab('home', 'Home', Screen::make(Text::make('Home tab')))
    ->tab(
        'orders',
        'Orders',
        Router::stack('orders.index')
            ->route(
                'orders.index',
                static fn () => Screen::make(Text::make('Orders tab')),
            )
            ->build(),
    )
    ->presentation(TabPresentation::Bottom)
    ->persistence('test-main-tabs')
    ->build();
$assert(
    $tabNavigator instanceof TabNavigator
        && $tabNavigator->selectedTab() === 'home'
        && $tabNavigator->resolvedPresentation() === TabPresentation::Bottom,
    'Tab router must build a typed native tab navigator.',
);
$assert(
    $tabNavigator->select('orders')
        && $tabNavigator->selectedTab() === 'orders'
        && $tabNavigator->toElement()->kind() === NodeKind::SafeAreaView,
    'Tab navigator must retain independent tab content and expose safe native navigation.',
);
$restoredTabs = Router::tabs('home')
    ->tab('home', 'Home', Screen::make(Text::make('Home tab')))
    ->tab('orders', 'Orders', Screen::make(Text::make('Orders tab')))
    ->persistence('test-main-tabs')
    ->build();
$assert(
    $restoredTabs->selectedTab() === 'orders',
    'Tab navigator must restore the selected destination.',
);

$homeTabRenders = 0;
$ordersTabRenders = 0;
$lazyTabs = Router::tabs('home')
    ->tab('home', 'Home', static function () use (&$homeTabRenders): Screen {
        $homeTabRenders++;
        return Screen::make(Text::make('Lazy home'));
    })
    ->tab('orders', 'Orders', static function () use (&$ordersTabRenders): Screen {
        $ordersTabRenders++;
        return Screen::make(Text::make('Lazy orders'));
    })
    ->appearance(0xFF0F172A, 0xFF60A5FA, 0xFF94A3B8, 0xFF1E293B)
    ->persistence('test-lazy-tabs')
    ->build();
$lazyTabs->toElement();
$assert(
    $homeTabRenders === 1 && $ordersTabRenders === 0,
    'Tab navigation must mount only the selected destination during cold start.',
);
$lazyTabs->select('orders');
$lazyTabs->toElement();
$assert(
    $homeTabRenders === 1 && $ordersTabRenders === 1,
    'Selecting a tab must lazily mount only the next destination.',
);
$groupedDrawer = Router::drawer('overview')
    ->route('overview', 'Overview', Screen::make(Text::make('Overview')))
    ->route(
        'button',
        'Button',
        Screen::make(Text::make('Button')),
        group: 'Actions',
    )
    ->route(
        'field',
        'Text field',
        Screen::make(Text::make('Text field')),
        group: 'Forms',
    )
    ->persistence('test-grouped-drawer')
    ->build();
$assert(
    !$groupedDrawer->isGroupExpanded('Actions')
        && $groupedDrawer->toggleGroup('Actions')
        && $groupedDrawer->isGroupExpanded('Actions')
        && $groupedDrawer->navigate('field')
        && $groupedDrawer->isGroupExpanded('Forms'),
    'Grouped drawer routes must collapse cleanly and keep every destination navigable.',
);
$restoredGroupedDrawer = Router::drawer('overview')
    ->route('overview', 'Overview', Screen::make(Text::make('Overview')))
    ->route(
        'button',
        'Button',
        Screen::make(Text::make('Button')),
        group: 'Actions',
    )
    ->route(
        'field',
        'Text field',
        Screen::make(Text::make('Text field')),
        group: 'Forms',
    )
    ->persistence('test-grouped-drawer')
    ->build();
$assert(
    $restoredGroupedDrawer->selectedRoute() === 'field'
        && $restoredGroupedDrawer->isGroupExpanded('Actions')
        && $restoredGroupedDrawer->isGroupExpanded('Forms'),
    'Grouped drawer state must restore selection and expanded sections.',
);
$assert(
    \Pam\Native\Protocol::SDK_VERSION === '0.5.86',
    'The runtime SDK contract must match the 0.5.86 package release.',
);
$imageEditorParameters = (new ReflectionMethod(
    \Pam\Native\System\ImageEditor::class,
    'render',
))->getParameters();
$assert(
    array_map(
        static fn (\Pam\Native\ImageCropRatio $case): int => $case->value,
        \Pam\Native\ImageCropRatio::cases(),
    ) === [1, 2, 3, 4, 5]
        && array_map(
            static fn (\Pam\Native\ImageFilterType $case): int => $case->value,
            \Pam\Native\ImageFilterType::cases(),
        ) === [1, 2, 3, 4, 5]
        && array_map(
            static fn (ReflectionParameter $parameter): string => $parameter->getName(),
            array_slice($imageEditorParameters, -4),
        ) === ['maxWidth', 'maxHeight', 'outputQuality', 'drawing']
        && $imageEditorParameters[count($imageEditorParameters) - 2]->getDefaultValue() === 94
        && $imageEditorParameters[array_key_last($imageEditorParameters)]->getDefaultValue() === '',
    'The image editor contract must expose typed transforms, bounded output and drawing.',
);

$bottomSheet = BottomSheet::make(
    Text::make('Sheet content'),
    [0.25, 0.6, 1.0],
    1,
)
    ->dismissible(false)
    ->backdropDismiss(false)
    ->handleVisible(false)
    ->dragEnabled(false)
    ->cornerRadius(28)
    ->keyboardBehavior(BottomSheetKeyboardBehavior::FillParent);
$bottomSheetProperties = $bottomSheet->properties();
$assert(
    $bottomSheet->kind() === NodeKind::Modal
        && $bottomSheetProperties[PropKey::BottomSheetIndex->value] === 1
        && $bottomSheetProperties[PropKey::BottomSheetDismissible->value] === false
        && $bottomSheetProperties[PropKey::BottomSheetKeyboardBehavior->value]
            === BottomSheetKeyboardBehavior::FillParent->value,
    'BottomSheet must compile its complete native configuration.',
);
$templateBottomSheet = TemplateRenderer::render(
    TemplateCompiler::compile(
        '<BottomSheet index="1" keyboardBehavior="extend">'
        .'<Text>Template sheet</Text></BottomSheet>',
    ),
    new class {
    },
    [],
);
$assert(
    $templateBottomSheet->properties()[PropKey::BottomSheetIndex->value] === 1
        && $templateBottomSheet->properties()[
            PropKey::BottomSheetKeyboardBehavior->value
        ] === BottomSheetKeyboardBehavior::Extend->value,
    'BottomSheet must be available from declarative templates.',
);

$animated = Animated::make(
    Text::make('Animated'),
    [
        new AnimationKeyframe(0.0, opacity: 0.0, translationY: 12.0),
        new AnimationKeyframe(1.0, opacity: 1.0, translationY: 0.0),
    ],
    420,
)->iterations(2)->autoReverse();
$assert(
    $animated->properties()[PropKey::AnimationIterations->value] === 2
        && $animated->properties()[PropKey::AnimationAutoReverse->value] === true,
    'Animated must encode declarative native keyframes.',
);

$interaction = InteractionRegion::make(Text::make('Card'))
    ->draggable('card:42')
    ->acceptsDrop()
    ->contextMenu([
        new NativeMenuItem('inspect', 'Inspect'),
        new NativeMenuItem('delete', 'Delete', destructive: true),
    ]);
$assert(
    $interaction->properties()[PropKey::Draggable->value] === true
        && $interaction->properties()[PropKey::DropEnabled->value] === true
        && isset($interaction->properties()[PropKey::ContextMenuItems->value]),
    'InteractionRegion must encode drag, drop and native menu behavior.',
);

$webView = WebView::make('https://example.com')->javaScriptEnabled(false);
$media = MediaPlayer::make('https://example.com/video.mp4', MediaType::Video)
    ->autoPlay()
    ->loop()
    ->fit(ImageFit::Cover)
    ->volume(0.5);
$assert(
    $webView->kind() === NodeKind::WebView
        && $webView->properties()[PropKey::WebViewJavaScriptEnabled->value] === false
        && $media->kind() === NodeKind::Media
        && $media->properties()[PropKey::MediaLoop->value] === true
        && $media->properties()[PropKey::ImageFit->value] === ImageFit::Cover->value,
    'WebView and MediaPlayer must compile into dedicated native node kinds.',
);

$cachedImage = Image::make('https://example.com/hero.webp')
    ->cache(MediaCachePolicy::StaleWhileRevalidate)
    ->cacheKey('hero:v3')
    ->maxAge(2_592_000_000)
    ->cacheTags(['feed', 'hero'])
    ->pinOffline()
    ->priority(MediaPriority::Visible)
    ->maxCacheSize(64 * 1024 * 1024)
    ->resize(720, 1280)
    ->thumbnail('https://example.com/hero-thumb.webp')
    ->checksum(str_repeat('a', 64));
$cachedImageProperties = $cachedImage->properties();
$assert(
    $cachedImageProperties[PropKey::MediaCachePolicy->value]
        === MediaCachePolicy::StaleWhileRevalidate->value
        && $cachedImageProperties[PropKey::MediaCacheKey->value] === 'hero:v3'
        && $cachedImageProperties[PropKey::MediaCacheTags->value] === "feed\nhero"
        && $cachedImageProperties[PropKey::MediaCachePinOffline->value] === true
        && $cachedImageProperties[PropKey::MediaResizeWidth->value] === 720
        && $cachedImageProperties[PropKey::MediaCacheChecksum->value] === str_repeat('a', 64),
    'Image media-cache helpers must encode the complete append-only native contract.',
);

$cachedMedia = MediaPlayer::make('https://example.com/movie.mp4')
    ->cache(MediaCachePolicy::Disk)
    ->cacheKey('movie:42')
    ->streamingCache()
    ->preloadSeconds(12)
    ->downloadWhilePlaying()
    ->pinOffline();
$assert(
    $cachedMedia->properties()[PropKey::MediaCacheStreaming->value] === true
        && $cachedMedia->properties()[PropKey::MediaCachePreloadSeconds->value] === 12
        && $cachedMedia->properties()[PropKey::MediaCacheDownloadWhilePlaying->value] === true,
    'MediaPlayer must expose streaming, preload and offline cache controls.',
);

$cachedTagImage = TemplateRenderer::render(
    TemplateCompiler::compile(
        '<Image source="https://example.com/tag.webp" cache="stale-while-revalidate" '.
        'cache-key="tag:v2" cache-max-age="30d" cache-tags="feed,avatar" pin-offline '.
        'priority="visible" cache-max-bytes="64mb" resize-width="512" resize-height="512" />',
    ),
    null,
    [],
);
$assert(
    $cachedTagImage->properties()[PropKey::MediaCachePolicy->value]
        === MediaCachePolicy::StaleWhileRevalidate->value
        && $cachedTagImage->properties()[PropKey::MediaCacheMaxAgeMs->value] === 2_592_000_000
        && $cachedTagImage->properties()[PropKey::MediaCacheMaxBytes->value] === 67_108_864
        && $cachedTagImage->properties()[PropKey::MediaResizeHeight->value] === 512,
    'Tag media-cache attributes must normalize kebab-case values and bounded units.',
);

$hardenedWebView = WebView::make('https://example.com/app')
    ->allowedHosts(['example.com', 'cdn.example.com']);
$assert(
    $hardenedWebView->properties()[PropKey::WebViewAllowedHosts->value]
        === "example.com\ncdn.example.com"
        && PropKey::WebViewAllowedHosts->value === 366,
    'WebView host allowlists must use the append-only native protocol.',
);

$permission = new PermissionDecision(
    PermissionKind::Photos,
    PermissionStatus::Limited,
    false,
);
$assert(
    $permission->granted()
        && PermissionKind::LocationWhenInUse->value === 5
        && PermissionKind::Contacts->value === 6
        && PermissionStatus::Limited->value === 4
        && PushEventType::Opened->value === 2
        && PushProvider::Apns->value === 2,
    'Production capability variants must remain sequential integer enums.',
);

Stores::resetRuntime();
$counterStore = Stores::get(new class extends Store {
    protected function state(): array
    {
        return ['count' => 1, 'label' => 'Counter', 'temporary' => false];
    }

    protected function persist(): array
    {
        return ['count'];
    }

    public function increment(int $amount = 1): void
    {
        $this->count += $amount;
    }

    public function failAfterMutation(): void
    {
        $this->count = 999;
        throw new RuntimeException('rollback');
    }

    #[StoreComputed]
    protected function doubled(): int
    {
        return $this->count * 2;
    }
}::class);
$observedChanges = [];
$counterStore->subscribe(static function ($change) use (&$observedChanges): void {
    $observedChanges[] = $change;
});
$counterStore->dispatch('increment', ['amount' => 2]);
$assert(
    $counterStore->count === 3
        && $counterStore->doubled === 6
        && count($observedChanges) === 1
        && $observedChanges[0]->diff['count'] === ['before' => 1, 'after' => 3],
    'Pam Store actions must batch mutations, invalidate computed values and publish diffs.',
);
$counterStore->undo();
$assert($counterStore->count === 1, 'Pam Store must support undo.');
$counterStore->redo();
$assert($counterStore->count === 3, 'Pam Store must support redo.');
try {
    $counterStore->dispatch('failAfterMutation');
    $assert(false, 'Failed store actions must throw.');
} catch (RuntimeException) {
    $assert($counterStore->count === 3, 'Failed store actions must roll state back atomically.');
}
try {
    $counterStore->optimistic(
        'optimistic-increment',
        static function () use ($counterStore): void {
            $counterStore->count = 10;
        },
        static fn () => throw new RuntimeException('network'),
    );
    $assert(false, 'Failed optimistic tasks must throw.');
} catch (RuntimeException) {
    $assert($counterStore->count === 3, 'Optimistic updates must roll back on failure.');
}
$changeId = $observedChanges[0]->id;
$counterStore->dispatch('increment', ['amount' => 5], ActionPolicy::Every);
$assert(
    Stores::timeTravel($changeId)
        && $counterStore->count === 3
        && StoreChangeKind::TimeTravel->value === 5,
    'Pam Store DevTools history must support time travel with integer change kinds.',
);
$middlewareCalls = 0;
Stores::middleware(new class($middlewareCalls) implements StoreMiddleware {
    public function __construct(private int &$calls)
    {
    }

    public function handle(Store $store, string $action, array $arguments, Closure $next): mixed
    {
        $this->calls++;

        return $next();
    }
});
$counterStore->dispatch('increment');
$assert($middlewareCalls === 1, 'Pam Store middleware must wrap actions.');

$componentLog = [];
$component = new class($componentLog) extends Component {
    public int $renders = 0;

    public function __construct(private array &$log)
    {
    }

    protected function initialState(): array
    {
        return ['count' => 1];
    }

    public function setup(): void
    {
        $this->log[] = 'setup';
    }

    public function mount(): void
    {
        $this->log[] = 'mount';
    }

    public function rendering(): void
    {
        $this->log[] = 'rendering';
    }

    public function render(): \Pam\Native\Renderable
    {
        $this->renders++;

        return Text::make((string) $this->doubled);
    }

    #[\Pam\Native\Attributes\Computed]
    protected function doubled(): int
    {
        return $this->state->count * 2;
    }

    protected function effects(): array
    {
        return [
            \Pam\Native\Effect::watch(
                fn (): int => $this->state->count,
                function (int $count): Closure {
                    $this->log[] = "effect:{$count}";

                    return function (): void {
                        $this->log[] = 'effect:cleanup';
                    };
                },
            ),
        ];
    }

    public function increment(): void
    {
        $this->state->count++;
    }

    public function shouldUpdate(\Pam\Native\ComponentChanges $changes): bool
    {
        return !$changes->changed('ignored');
    }

    public function cleanup(): void
    {
        $this->log[] = 'cleanup';
    }

    #[\Pam\Native\Attributes\Expose]
    public function exposedCount(): int
    {
        return $this->state->count;
    }

    public function bind(\Pam\Native\ComponentRef $ref): void
    {
        $this->exposeTo($ref);
    }
};
ComponentLifecycle::beginRender();
$component->toElement();
ComponentLifecycle::finishRender();
ComponentLifecycle::commit();
$assert(
    $componentLog === ['setup', 'mount', 'rendering', 'effect:1']
        && $component->renders === 1,
    'Component setup, render and effect hooks must run in deterministic order.',
);
$component->increment();
ComponentLifecycle::beginRender();
$component->toElement();
ComponentLifecycle::finishRender();
ComponentLifecycle::commit();
$assert(
    $component->renders === 2
        && array_slice($componentLog, -3) === ['rendering', 'effect:cleanup', 'effect:2'],
    'Local state must invalidate computed values, render and clean changed effects.',
);
ComponentLifecycle::beginRender();
$component->toElement();
ComponentLifecycle::finishRender();
ComponentLifecycle::commit();
$assert(
    $component->renders === 2,
    'Dependency tracking must skip clean component subtrees.',
);
$ref = new \Pam\Native\ComponentRef();
$component->bind($ref);
$assert($ref->call('exposedCount') === 2, 'Component refs may call only exposed methods.');

$boundary = new class extends Component {
    public function render(): \Pam\Native\Renderable
    {
        throw new RuntimeException('child failed');
    }

    public function failed(
        Throwable $error,
        \Pam\Native\ErrorContext $context,
    ): ?\Pam\Native\Renderable {
        return Text::make($context->phase.':'.$error->getMessage());
    }
};
ComponentLifecycle::beginRender();
$boundaryElement = $boundary->toElement();
ComponentLifecycle::finishRender();
ComponentLifecycle::commit();
$assert(
    $boundaryElement->kind() === NodeKind::Text,
    'Component error boundaries must recover a failed render with a fallback element.',
);

$context = new \stdClass();
$provider = new class($context) extends Component {
    public function __construct(private object $context)
    {
    }

    protected function provide(): array
    {
        return [\stdClass::class => $this->context];
    }

    public function render(): \Pam\Native\Renderable
    {
        return Text::make('provider');
    }
};
$consumer = new class extends Component {
    public function context(): object
    {
        return $this->inject(\stdClass::class);
    }

    protected function slots(): array
    {
        return ['content' => \Pam\Native\Slot::required()];
    }

    public function render(): \Pam\Native\Renderable
    {
        return Text::make('consumer');
    }
};
$provider->__pamConfigure([], []);
$consumer->__pamConfigure(['content' => [Text::make('slot')]], [], $provider);
$assert(
    $consumer->context() === $context,
    'Component provide/inject context and typed slot validation must follow ancestry.',
);

$nativeRef = new \Pam\Native\NativeRef();
$focused = false;
$nativeRef->attach([
    'focus' => static function () use (&$focused): void {
        $focused = true;
    },
    'blur' => static function (): void {},
    'measure' => static fn (): array => [
        'x' => 0.0,
        'y' => 0.0,
        'width' => 100.0,
        'height' => 40.0,
    ],
    'scrollIntoView' => static function (): void {},
]);
$nativeRef->focus();
$assert(
    $focused && $nativeRef->measure()['width'] === 100.0,
    'Native refs must expose lifecycle-safe focus and measurement operations.',
);
$nativeRef->detach();

$schedulerOrder = [];
\Pam\Native\Scheduling\Scheduler::reset();
\Pam\Native\Scheduling\Scheduler::schedule(
    static function () use (&$schedulerOrder): void {
        $schedulerOrder[] = 'background';
    },
    \Pam\Native\Scheduling\TaskPriority::Background,
);
\Pam\Native\Scheduling\Scheduler::schedule(
    static function () use (&$schedulerOrder): void {
        $schedulerOrder[] = 'input';
    },
    \Pam\Native\Scheduling\TaskPriority::UserBlocking,
);
$obsolete = \Pam\Native\Scheduling\Scheduler::schedule(
    static function () use (&$schedulerOrder): void {
        $schedulerOrder[] = 'obsolete';
    },
    coalesce: 'search',
);
\Pam\Native\Scheduling\Scheduler::schedule(
    static function () use (&$schedulerOrder): void {
        $schedulerOrder[] = 'latest';
    },
    coalesce: 'search',
);
\Pam\Native\Scheduling\Scheduler::drain(100);
$assert(
    $obsolete->token->cancelled()
        && $schedulerOrder === ['input', 'latest', 'background'],
    'Scheduler must prioritize user work and coalesce obsolete tasks.',
);

ComponentLifecycle::shutdown();
$assert(
    in_array('effect:cleanup', $componentLog, true)
        && end($componentLog) === 'cleanup',
    'Component shutdown must guarantee effect and component cleanup.',
);

echo "Pam Native PHP SDK tests passed.\n";
