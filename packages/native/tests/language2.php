<?php

declare(strict_types=1);

use Pam\Native\AsyncValue;
use Pam\Native\Internal\CompiledTemplateNode;
use Pam\Native\Internal\PamPhpCompiler;
use Pam\Native\Internal\ScopedStyleCompiler;
use Pam\Native\Internal\StyleQueryCompiler;
use Pam\Native\Internal\StyleTokenGenerator;
use Pam\Native\Internal\StyleValueCompiler;
use Pam\Native\Internal\TemplateCompiler;
use Pam\Native\Internal\TemplateRenderer;
use Pam\Native\LanguageVersion;
use Pam\Native\NodeKind;
use Pam\Native\PropKey;
use Pam\Native\Style\StyleInvalidationKind;
use Pam\Native\Style\StylePropertyCatalog;
use Pam\Native\Style\StyleScope;
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
$assert(
    (float) ($styledElement->properties()[PropKey::PressOpacity->value] ?? 0) === 0.72
        && (float) ($styledElement->properties()[PropKey::PressScale->value] ?? 0) === 0.98
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
