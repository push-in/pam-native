<?php

declare(strict_types=1);

use Pam\Native\AsyncValue;
use Pam\Native\Internal\CompiledTemplateNode;
use Pam\Native\Internal\PamPhpCompiler;
use Pam\Native\Internal\ScopedStyleCompiler;
use Pam\Native\Internal\TemplateCompiler;
use Pam\Native\Internal\TemplateRenderer;
use Pam\Native\LanguageVersion;
use Pam\Native\NodeKind;
use Pam\Native\PropKey;
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
        && count($language2Styles['keyframes']['enter'] ?? []) === 2,
    'Language 2 styles must compile tokens, recipes, states, queries and compositor keyframes to IR.',
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
