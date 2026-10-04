<?php

declare(strict_types=1);

use Pam\Native\AsyncValue;
use Pam\Native\Internal\CompiledTemplateNode;
use Pam\Native\Internal\PamPhpCompiler;
use Pam\Native\Internal\ScopedStyleCompiler;
use Pam\Native\Internal\StyleQueryCompiler;
use Pam\Native\Internal\StyleTokenGenerator;
use Pam\Native\Internal\StyleUtilityCompiler;
use Pam\Native\Internal\StyleValueCompiler;
use Pam\Native\Internal\TemplateCompiler;
use Pam\Native\Internal\TemplateRenderer;
use Pam\Native\LanguageVersion;
use Pam\Native\NodeKind;
use Pam\Native\PropKey;
use Pam\Native\Style\StyleInvalidationKind;
use Pam\Native\Style\StylePropertyCatalog;
use Pam\Native\Style\StyleScope;
use Pam\Native\Style\StyleVariables;
use Pam\Native\Tooling\PamFormatter;

$language2Tree = TemplateCompiler::compile(
    '<Show when="$ready"><Text>Language 2</Text></Show>',
    'Language2Show.pam',
    LanguageVersion::Language2,
);
$language2Element = TemplateRenderer::render($language2Tree, null, ['ready' => true]);
$assert(
    $language2Element->kind() === NodeKind::Text
        && $language2Element->properties()[PropKey::Text->value] === 'Language 2',
    'Language 2 Show must render its typed truthy branch.',
);

$modelScope = new class extends \Pam\Native\Component {
    public string $search = 'Loki';
    public bool $enabled = false;
};
$modelTree = TemplateCompiler::compile(
    '<Column><Input p-model="$search" /><Switch p-model="$enabled" /></Column>',
    'Language2Model.pam',
    LanguageVersion::Language2,
);
$modelElement = TemplateRenderer::render($modelTree, $modelScope, []);
$modelInput = $modelElement->children()[0] ?? null;
$modelSwitch = $modelElement->children()[1] ?? null;
$assert(
    $modelInput instanceof \Pam\Native\Element
        && $modelSwitch instanceof \Pam\Native\Element
        && ($modelInput->properties()[PropKey::Value->value] ?? null) === 'Loki'
        && ($modelSwitch->properties()[PropKey::Checked->value] ?? null) === false,
    'p-model must read typed input and checked state without PHP markup expressions.',
);
($modelInput->events()[\Pam\Native\EventKind::Change->value])('IPTV');
($modelSwitch->events()[\Pam\Native\EventKind::Toggle->value])(true);
$assert(
    $modelScope->search === 'IPTV' && $modelScope->enabled,
    'p-model must write native input and checked events back to component state.',
);

$matchTree = TemplateCompiler::compile(
    '<Match value="$mode"><Case value="compact"><Text>Compact</Text></Case><Default><Text>Default</Text></Default></Match>',
    'Language2Match.pam',
    LanguageVersion::Language2,
);
$matchElement = TemplateRenderer::render($matchTree, null, ['mode' => 'compact']);
$assert(
    $matchElement->properties()[PropKey::Text->value] === 'Compact',
    'Language 2 Match must use strict case selection.',
);

$awaitTree = TemplateCompiler::compile(
    '<Await value="$request"><Pending><Text>Loading</Text></Pending><Content><Text>{{ $data }}</Text></Content></Await>',
    'Language2Await.pam',
    LanguageVersion::Language2,
);
$awaitElement = TemplateRenderer::render(
    $awaitTree,
    null,
    ['request' => AsyncValue::content('Ready')],
);
$assert(
    $awaitElement->properties()[PropKey::Text->value] === 'Ready',
    'Language 2 Await must expose typed async content to its Content branch.',
);

$language2Styles = ScopedStyleCompiler::compile(<<<'CSS'
@tokens {
    color.brand: #4F46E5;
    space.md: 16px;
}

@recipe card {
    base {
        padding: var(--space-md);
    }
    variant tone=primary {
        background: var(--color-brand);
    }
}

.touchable {
    opacity: 1;
}

.touchable:pressed {
    opacity: 0.72;
    transform: scale(0.98);
}

.touchable:focus-visible {
    background: #3366FF;
    border-color: #2244CC;
    elevation: 3;
    transform: scale(1.04);
}

@media (min-width: 768dp) {
    .card { padding: 24px; }
}

@container card (min-width: 320dp) {
    .title { font-size: 20px; }
}

@keyframes enter {
    from { opacity: 0; transform: translateY(12px); }
    to { opacity: 1; transform: translateY(0px); }
}
CSS, 'Language2Styles.pam');
$assert(
    ($language2Styles['tokens']['--color-brand'] ?? null) === '#4F46E5'
        && ($language2Styles['recipes']['card']['variants']['tone']['primary']['backgroundColor'] ?? null) === 0xFF4F46E5
        && ($language2Styles['states']['.touchable']['pressed']['opacity'] ?? null) === '0.72'
        && count($language2Styles['queries']) === 2
        && count($language2Styles['keyframes']['enter'] ?? []) === 2
        && ($language2Styles['styleIr']['version'] ?? null) === 1
        && ($language2Styles['styleIr']['dependencies']['container'] ?? null)
            === StyleInvalidationKind::Container->value
        && is_string($language2Styles['styleBytecode'] ?? null)
        && base64_decode($language2Styles['styleBytecode'], true) !== false
        && strlen((string) ($language2Styles['styleFingerprint'] ?? '')) === 64
        && ($language2Styles['styleSourceMap'][0]['source'] ?? null)
            === 'Language2Styles.pam',
    'Language 2 styles must compile tokens, recipes, states, queries and compositor keyframes to IR.',
);
$styleDefinitions = StylePropertyCatalog::all();
foreach ($styleDefinitions as $index => $definition) {
    $assert(
        $definition->id === $index + 1,
        'PAM Style property IDs must remain sequential and append-only.',
    );
}
$assert(
    StylePropertyCatalog::find('background')?->nativeName === 'backgroundColor'
        && StylePropertyCatalog::find('transform')?->cost->value === 1,
    'The public CSS compatibility catalog must resolve aliases and compositor costs.',
);
$utility = StyleUtilityCompiler::compile('grid-4');
$assert(
    ($utility['attribute'] ?? null) === 'columns'
        && ($utility['value'] ?? null) === 4
        && ($language2Styles['styleIr']['utilities']['source'] ?? null) === 'tailwind-compatible',
    'Optional Tailwind-compatible utilities must compile through the shared Style IR manifest.',
);
$moduleStyles = ScopedStyleCompiler::compile(
    '.card { padding: 12px; }',
    'ModuleStyles.pam',
    StyleScope::Module,
);
$assert(
    ($moduleStyles['scope'] ?? null) === StyleScope::Module->value
        && strlen((string) ($moduleStyles['scopeId'] ?? '')) === 16
        && ($moduleStyles['styleIr']['scope'] ?? null)
            === StyleScope::Module->value,
    'Style modules must carry an immutable numeric scope and deterministic scope ID.',
);
$generatedTokens = StyleTokenGenerator::generate([
    '--color-brand' => '#4F46E5',
    '--space-md' => '16px',
]);
$assert(
    str_contains($generatedTokens['php'], 'public const string COLOR_BRAND')
        && str_contains($generatedTokens['kotlin'], 'public const val SPACE_MD: String')
        && str_contains($generatedTokens['swift'], 'public static let COLOR_BRAND: String'),
    'Design tokens must generate typed PHP, Kotlin and Swift APIs deterministically.',
);
$layerStyles = ScopedStyleCompiler::compile(<<<'CSS'
@layer reset { .card { padding: 4px; } }
@layer components { .card { padding: 16px; } }
CSS, 'LayerStyles.pam');
$assert(
    ($layerStyles['classes']['card']['paddingLeft'] ?? null) === '16',
    'Named cascade layers must flatten in deterministic declaration order.',
);
$gridStyles = ScopedStyleCompiler::compile(
    '.catalog { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; } .featured { grid-column: span 2; }',
    'GridStyles.pam',
);
$assert(
    ($gridStyles['classes']['catalog']['columns'] ?? null) === '4'
        && ($gridStyles['classes']['featured']['span'] ?? null) === '2',
    'CSS Grid tracks and spans must lower to the native grid layout protocol.',
);
$formattedModule = PamFormatter::format(<<<'PAM'
<?php
declare(strict_types=1);
final class ModuleStyleExample {}
?>
<template language="2"><Column class="card" /></template>
<style module>.card { padding: 12px; }</style>
PAM, 'ModuleStyleExample.pam');
$assert(
    str_contains($formattedModule, '<style module>')
        && PamFormatter::format($formattedModule, 'ModuleStyleExample.pam')
            === $formattedModule,
    'The formatter must preserve module style isolation idempotently.',
);
$modernWidthQuery = StyleQueryCompiler::compile(
    '(width >= 840dp)',
    'ModernQueries.pam',
);
$darkQuery = StyleQueryCompiler::compile(
    '(prefers-color-scheme: dark)',
    'ModernQueries.pam',
);
$assert(
    StyleQueryCompiler::matches($modernWidthQuery, ['width' => 900.0])
        && !StyleQueryCompiler::matches($modernWidthQuery, ['width' => 700.0])
        && StyleQueryCompiler::matches($darkQuery, ['colorScheme' => 'dark'])
        && !StyleQueryCompiler::matches($darkQuery, ['colorScheme' => 'light']),
    'Modern comparison and platform preference queries must compile to typed IR and evaluate deterministically.',
);
$fluidStyles = ScopedStyleCompiler::compile(
    '.fluid { width: calc(100vw - 32px); padding-left: clamp(12px, 2vw, 24px); }',
    'FluidStyles.pam',
);
$fluidWidth = $fluidStyles['classes']['fluid']['width'] ?? null;
$fluidPadding = $fluidStyles['classes']['fluid']['paddingLeft'] ?? null;
$assert(
    is_string($fluidWidth)
        && is_string($fluidPadding)
        && StyleValueCompiler::encoded($fluidWidth)
        && StyleValueCompiler::encoded($fluidPadding)
        && StyleValueCompiler::resolve($fluidWidth, ['width' => 400.0, 'height' => 800.0]) === 368.0
        && StyleValueCompiler::resolve($fluidPadding, ['width' => 400.0, 'height' => 800.0]) === 12.0,
    'CSS calc(), clamp() and viewport units must compile once and resolve deterministically.',
);
$safeInset = StyleValueCompiler::encode(
    'calc(env(safe-area-inset-top) + 8dp)',
    'SafeInset.pam',
);
$containerStyles = ScopedStyleCompiler::compile(
    '.relative { width: calc(50% + 8px); height: calc(25% + 4px); }',
    'ContainerDimensions.pam',
);
$containerTemplate = TemplateCompiler::compile('<View class="relative" />', 'ContainerDimensions.pam', LanguageVersion::Language2);
$containerRoot = new CompiledTemplateNode(
    kind: $containerTemplate->kind,
    name: $containerTemplate->name,
    attributes: ['__pamStyles' => json_encode($containerStyles, JSON_THROW_ON_ERROR)],
    source: $containerTemplate->source,
    line: $containerTemplate->line,
    column: $containerTemplate->column,
);
$containerRoot->children = $containerTemplate->children;
$relativeElement = TemplateRenderer::render($containerRoot, null, [
    '__pamContainerWidth' => '400', '__pamContainerHeight' => 800.0,
]);
$assert(($relativeElement->properties()[PropKey::Width->value] ?? null) === 208.0
    && ($relativeElement->properties()[PropKey::Height->value] ?? null) === 204.0,
    'Container math must use finite horizontal/vertical references and preserve numeric-string compatibility.');
foreach ([[], new stdClass(), true, -1, INF, NAN, 'invalid'] as $invalidDimension) {
    foreach (['__pamContainerWidth', '__pamContainerHeight'] as $dimensionKey) {
        try {
            TemplateRenderer::render($containerRoot, null, [$dimensionKey => $invalidDimension]);
            throw new RuntimeException('Invalid container dimensions reached style evaluation.');
        } catch (InvalidArgumentException) {
            $assert(true, 'Malformed container dimensions fail before style evaluation.');
        }
    }
}
foreach (['fontScale', 'rootFontSize', 'env.safe-area-inset-top'] as $environmentKey) {
    try {
        TemplateRenderer::render($containerRoot, null, ['__pamStyleEnvironment' => [$environmentKey => INF]]);
        throw new RuntimeException('Nonfinite style environment reached style evaluation.');
    } catch (InvalidArgumentException) {
        $assert(true, 'Nonfinite style environment values fail before native dispatch.');
    }
}
$assert(
    StyleValueCompiler::resolve($safeInset, [
        'width' => 400.0,
        'height' => 800.0,
        'env.safe-area-inset-top' => 24.0,
    ]) === 32.0,
    'Native env() values must participate in compiled CSS math.',
);

$cascadeStyles = ScopedStyleCompiler::compile(<<<'CSS'
View.card { padding: 8px; background: #111111; }
#shell > View.card[role="region"] { padding: 12px; }
.shell .card { background: #222222 !important; }
View.card { background: #333333; }
CSS, 'CascadeStyles.pam');
$cascadeTemplate = TemplateCompiler::compile(
    '<Column id="shell" class="shell"><View class="card" role="region" /></Column>',
    'CascadeStyles.pam',
    LanguageVersion::Language2,
);
$cascadeRoot = new CompiledTemplateNode(
    kind: $cascadeTemplate->kind,
    name: $cascadeTemplate->name,
    attributes: ['__pamStyles' => json_encode($cascadeStyles, JSON_THROW_ON_ERROR)],
    source: $cascadeTemplate->source,
    line: $cascadeTemplate->line,
    column: $cascadeTemplate->column,
);
$cascadeRoot->children = $cascadeTemplate->children;
$cascadeElement = TemplateRenderer::render($cascadeRoot, null, [])->children()[0] ?? null;
$assert(
    $cascadeElement instanceof \Pam\Native\Element
        && (float) ($cascadeElement->properties()[PropKey::PaddingLeft->value] ?? 0) === 12.0
        && ($cascadeElement->properties()[PropKey::BackgroundColor->value] ?? null) === 0xFF222222,
    'Compound, child, descendant and attribute selectors must honor specificity and !important.',
);
$reactiveStyles = ScopedStyleCompiler::compile(
    ':root { --space: 8px; --surface: #111111; } .reactive { padding: var(--space); background: var(--surface); }',
    'ReactiveStyles.pam',
);
$reactiveTemplate = TemplateCompiler::compile('<View class="reactive" />', 'ReactiveStyles.pam', LanguageVersion::Language2);
$reactiveRoot = new CompiledTemplateNode(
    kind: $reactiveTemplate->kind,
    name: $reactiveTemplate->name,
    attributes: ['__pamStyles' => json_encode($reactiveStyles, JSON_THROW_ON_ERROR)],
    source: $reactiveTemplate->source,
    line: $reactiveTemplate->line,
    column: $reactiveTemplate->column,
);
$reactiveRoot->children = $reactiveTemplate->children;
StyleVariables::replace(['space' => '20px', 'surface' => '#4F46E5']);
$reactiveElement = TemplateRenderer::render($reactiveRoot, null, []);
$assert(
    (float) ($reactiveElement->properties()[PropKey::PaddingLeft->value] ?? 0) === 20.0
        && ($reactiveElement->properties()[PropKey::BackgroundColor->value] ?? null) === 0xFF4F46E5,
    'Reactive CSS variables must invalidate and recompile only dependent declarations.',
);
StyleVariables::replace([]);

$reactiveMethod = new ReflectionMethod(TemplateRenderer::class, 'reactiveStyleSheet');
$attributesMethod = new ReflectionMethod(TemplateRenderer::class, 'styleAttributes');
$assert($attributesMethod->invoke(null, ['width' => 12.5, 'fontWeight' => 600, 'visible' => false], 'test')
    === ['width' => 12.5, 'fontWeight' => 600, 'visible' => false],
    'Style attribute validation must preserve fractional dimensions, integers and booleans.');
foreach ([['width' => []], ['width' => new stdClass()], ['width' => INF], [0 => 'value']] as $invalidAttributes) {
    try {
        $attributesMethod->invoke(null, $invalidAttributes, 'test');
        throw new LogicException('Invalid style attributes were accepted.');
    } catch (RuntimeException) {
        $assert(true, 'Invalid attribute maps fail before native conversion.');
    }
}
$tagMethod = new ReflectionMethod(TemplateRenderer::class, 'tag');
try {
    $tagMethod->invoke(null, 'Button', ['on:press' => 42], [], null, []);
    throw new LogicException('Numeric event expression was accepted.');
} catch (RuntimeException $eventError) {
    $assert(str_contains($eventError->getMessage(), 'event expression'), 'Invalid event metadata has an explicit diagnostic.');
}
$animationTemplate = TemplateCompiler::compile('<Animated animation="enter"><View /></Animated>', 'KeyframeValidation.pam', LanguageVersion::Language2);
foreach ([null, false, ['offset' => -0.1], ['offset' => 1.1], ['offset' => '0.5'],
    ['offset' => 0.0, 'styles' => ['opacity' => []]]] as $invalidFrame) {
    $frames = [
        $invalidFrame ?? ['offset' => 0.0, 'styles' => ['opacity' => 0.0, 'offset' => 0.8]],
        ['offset' => 1.0, 'styles' => ['opacity' => 1.0]],
    ];
    $animationRoot = new CompiledTemplateNode(
        kind: $animationTemplate->kind, name: $animationTemplate->name,
        attributes: ['__pamStyles' => json_encode(['classes' => [], 'tags' => [], 'keyframes' => ['enter' => $frames]], JSON_THROW_ON_ERROR)],
        source: $animationTemplate->source, line: $animationTemplate->line, column: $animationTemplate->column,
    );
    $animationRoot->children = $animationTemplate->children;
    try {
        $animationElement = TemplateRenderer::render($animationRoot, null, []);
        if ($invalidFrame !== null) throw new LogicException('Malformed keyframe was accepted.');
        $animationPayload = $animationElement->properties()[PropKey::AnimationKeyframes->value] ?? null;
        $assert($animationPayload instanceof \Pam\Native\Internal\BinaryValue
            && json_decode($animationPayload->bytes, true, flags: JSON_THROW_ON_ERROR)[0]['offset'] === 0.0,
            'Valid keyframes render and frame styles cannot overwrite their timeline offset.');
    } catch (RuntimeException $keyframeError) {
        if ($invalidFrame === null) throw $keyframeError;
        $assert(str_contains($keyframeError->getMessage(), 'keyframe'), 'Malformed keyframes fail with a relevant template diagnostic.');
    }
}
$cascadeMethod = new ReflectionMethod(TemplateRenderer::class, 'cascadeStyleAttributes');
$selectorMethod = new ReflectionMethod(TemplateRenderer::class, 'styleSelectorMatches');
$specificityRules = [
    ['selector' => ['specificity' => [1, 0, 0], 'compounds' => [['id' => 'target']]],
        'order' => 0, 'declarations' => ['width' => ['value' => '10']]],
    ['selector' => ['specificity' => [0, 1001, 0], 'compounds' => [['classes' => array_fill(0, 1001, 'a')]]],
        'order' => 1, 'declarations' => ['width' => ['value' => '20']]],
];
$selectorNode = ['tag' => 'View', 'id' => 'target', 'classes' => ['a']];
$assert($cascadeMethod->invoke(null, $specificityRules, $selectorNode, []) === ['width' => '10'],
    'One ID selector must outrank any number of class selectors without packed-score collisions.');
$specificityRules[1]['declarations']['width']['important'] = true;
$assert($cascadeMethod->invoke(null, $specificityRules, $selectorNode, []) === ['width' => '20'],
    'Important declarations must retain precedence over selector specificity.');
$assert($selectorMethod->invoke(null, ['compounds' => [
    3 => ['tag' => 'Column'], 7 => ['tag' => 'Text', 'combinator' => 'child'],
]], ['tag' => 'Text'], [9 => ['tag' => 'Column']]) === true,
    'Selector and ancestor lists must match correctly with sparse storage keys.');
foreach ([['compounds' => [false]], ['compounds' => [['classes' => false]]],
    ['compounds' => [['attributes' => [['name' => []]]]]],
    ['compounds' => [['attributes' => [['name' => 'value', 'operator' => '=', 'value' => 'x']]]]]] as $invalidSelector) {
    $assert($selectorMethod->invoke(null, $invalidSelector, ['attributes' => ['value' => []]], []) === false,
        'Malformed selectors and nonscalar attribute comparisons must fail without invalid array/string operations.');
}
$responsiveMethod = new ReflectionMethod(TemplateRenderer::class, 'responsiveStyleSheet');
$responsiveSheet = [
    'classes' => ['box' => ['width' => '20', 'fontSize' => '14']],
    'cascadeRules' => [['order' => 9]],
    'queries' => [[
        'kind' => \Pam\Native\Style\StyleQueryKind::Container->value,
        'condition' => '(min-width: 100px)',
        'styles' => [
            'classes' => ['box' => ['width' => '40']],
            'cascadeRules' => [['order' => 2]],
        ],
    ]],
];
$responsiveResult = $responsiveMethod->invoke(null, $responsiveSheet, ['__pamContainerWidth' => 200.0]);
$assert(is_array($responsiveResult)
    && ($responsiveResult['classes']['box'] ?? null) === ['width' => '40', 'fontSize' => '14']
    && ($responsiveResult['cascadeRules'][1]['order'] ?? null) === 12,
    'Responsive merges preserve base declarations and order incoming rules after sparse base orders.');
$assert($responsiveMethod->invoke(null, $responsiveSheet, ['__pamContainerWidth' => 50.0]) === $responsiveSheet,
    'Unmatched container queries must leave the base sheet unchanged.');
foreach ([['classes' => ['box' => false]], ['cascadeRules' => false],
    ['cascadeRules' => [['order' => []]]], ['cascadeRules' => [['order' => PHP_INT_MAX]]]] as $invalidBase) {
    try {
        $responsiveMethod->invoke(null, [...$responsiveSheet, ...$invalidBase], ['__pamContainerWidth' => 200.0]);
        throw new LogicException('Invalid responsive base metadata was accepted.');
    } catch (RuntimeException) {
        $assert(true, 'Invalid responsive merge metadata fails before cascade application.');
    }
}
$fontsMethod = new ReflectionMethod(TemplateRenderer::class, 'styleSheetFonts');
$fontFaces = $fontsMethod->invoke(null, ['__pamStyles' => ['fonts' => ['Display' => [
    4 => ['source' => 'fonts/display.ttf', 'weight' => '700', 'style' => 'normal'],
]]]]);
$assert($fontFaces === ['Display' => [
    ['source' => 'fonts/display.ttf', 'weight' => '700', 'style' => 'normal'],
]], 'Font metadata must preserve valid faces and normalize the face list.');
foreach ([['Display' => false], ['Display' => [['source' => []]]], [0 => []]] as $invalidFonts) {
    try {
        $fontsMethod->invoke(null, ['__pamStyles' => ['fonts' => $invalidFonts]]);
        throw new LogicException('Invalid font metadata was accepted.');
    } catch (RuntimeException) {
        $assert(true, 'Malformed font metadata fails before font resolution.');
    }
}
StyleVariables::replace(['space' => '20px']);
try {
    $firstSheet = $reactiveStyles;
    $firstSheet['styleFingerprint'] = '';
    $secondSheet = $firstSheet;
    $secondSheet['variables']['surface'] = '#222222';
    $firstResolved = $reactiveMethod->invoke(null, $firstSheet);
    $secondResolved = $reactiveMethod->invoke(null, $secondSheet);
    $assert($firstResolved !== $secondResolved,
        'Sheets without fingerprints must not share reactive results when their base variables differ.');
    foreach ([['variables' => false], ['variables' => ['surface' => []]], ['cascadeRules' => false]] as $invalidSheetPart) {
        try {
            $reactiveMethod->invoke(null, [...$firstSheet, ...$invalidSheetPart]);
            throw new LogicException('Invalid reactive metadata was accepted.');
        } catch (RuntimeException) {
            $assert(true, 'Malformed reactive metadata fails before cascade evaluation.');
        }
    }
} finally {
    StyleVariables::replace([]);
}

$nativeResourceStyles = ScopedStyleCompiler::compile(
    '.native-theme { -pam-native-background-color: colorSurface; -pam-native-text-color: label_primary; -pam-native-border-color: accent-color; }',
    'NativeResources.pam',
);
$nativeResourceTemplate = TemplateCompiler::compile(
    '<Text class="native-theme">Native theme</Text>',
    'NativeResources.pam',
    LanguageVersion::Language2,
);
$nativeResourceRoot = new CompiledTemplateNode(
    kind: $nativeResourceTemplate->kind,
    name: $nativeResourceTemplate->name,
    attributes: ['__pamStyles' => json_encode($nativeResourceStyles, JSON_THROW_ON_ERROR)],
    source: $nativeResourceTemplate->source,
    line: $nativeResourceTemplate->line,
    column: $nativeResourceTemplate->column,
);
$nativeResourceRoot->children = $nativeResourceTemplate->children;
$nativeResourceElement = TemplateRenderer::render($nativeResourceRoot, null, []);
$assert(
    ($nativeResourceElement->properties()[PropKey::NativeBackgroundColorResource->value] ?? null) === 'colorSurface'
        && ($nativeResourceElement->properties()[PropKey::NativeTextColorResource->value] ?? null) === 'label_primary'
        && ($nativeResourceElement->properties()[PropKey::NativeBorderColorResource->value] ?? null) === 'accent-color',
    'Native Android color resources and iOS named colors must cross the typed protocol unchanged.',
);

$styledTemplate = TemplateCompiler::compile(
    '<Pressable class="touchable"><View recipe="card" variant:tone="primary" /></Pressable>',
    'Language2StyledRuntime.pam',
    LanguageVersion::Language2,
);
$styledRoot = new CompiledTemplateNode(
    kind: $styledTemplate->kind,
    name: $styledTemplate->name,
    attributes: ['__pamStyles' => json_encode($language2Styles, JSON_THROW_ON_ERROR)],
    source: $styledTemplate->source,
    line: $styledTemplate->line,
    column: $styledTemplate->column,
);
$styledRoot->children = $styledTemplate->children;
$styledElement = TemplateRenderer::render($styledRoot, null, []);
$styledChild = $styledElement->children()[0] ?? null;
$nativeStateStyles = json_decode(
    (string) ($styledElement->properties()[PropKey::NativeStateStyles->value] ?? ''),
    true,
    flags: JSON_THROW_ON_ERROR,
);
$assert(
    (float) ($styledElement->properties()[PropKey::PressOpacity->value] ?? 0) === 0.72
        && (float) ($styledElement->properties()[PropKey::PressScale->value] ?? 0) === 0.98
        && str_contains(
            (string) ($styledElement->properties()[PropKey::NativeStateStyles->value] ?? ''),
            '"2"',
        )
        && ($nativeStateStyles[2][PropKey::BorderColor->value] ?? null) === 0xFF2244CC
        && (float) ($nativeStateStyles[2][PropKey::Elevation->value] ?? 0) === 3.0
        && $styledChild instanceof \Pam\Native\Element
        && (float) ($styledChild->properties()[PropKey::PaddingLeft->value] ?? 0) === 16.0
        && ($styledChild->properties()[PropKey::BackgroundColor->value] ?? null) === 0xFF4F46E5,
    'Compiled states and recipe variants must reach native element properties.',
);

$virtualKeyRejected = false;
try {
    TemplateCompiler::compile(
        '<VirtualizedList><Text p-for="$item in $items">{{ $item }}</Text></VirtualizedList>',
        'Language2VirtualList.pam',
        LanguageVersion::Language2,
    );
} catch (RuntimeException $error) {
    $virtualKeyRejected = str_contains($error->getMessage(), 'PAM2201');
}
$assert($virtualKeyRejected, 'Language 2 virtualized loops must require stable p-key identity.');

$imageA11yRejected = false;
try {
    TemplateCompiler::compile(
        '<Image source="asset://photo.jpg" />',
        'Language2Image.pam',
        LanguageVersion::Language2,
    );
} catch (RuntimeException $error) {
    $imageA11yRejected = str_contains($error->getMessage(), 'PAM2301');
}
$assert($imageA11yRejected, 'Language 2 must diagnose unlabeled non-decorative images.');

$formattedLanguage2 = PamFormatter::format(<<<'PAM'
<?php
declare(strict_types=1);
final class LanguageTwoFormat {}
?>
<template language="2"><Image decorative="true" /></template>
PAM, 'LanguageTwoFormat.pam');
$assert(
    str_contains($formattedLanguage2, '<template language="2">'),
    'The formatter must preserve the Language 2 opt-in contract.',
);

$showcaseLanguage2 = PamPhpCompiler::compileFile(
    dirname(__DIR__, 3).'/examples/showcase/resources/components/LanguageTwoCard.pam',
    $pamPhpCache,
);
$assert(
    $showcaseLanguage2->language === LanguageVersion::Language2
        && $showcaseLanguage2->tag === 'LanguageTwoCard',
    'The public showcase Language 2 component must compile with its #[Tag] contract.',
);
