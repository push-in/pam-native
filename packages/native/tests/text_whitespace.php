<?php

declare(strict_types=1);

use Pam\Native\Internal\TemplateCompiler;
use Pam\Native\Internal\TemplateRenderer;
use Pam\Native\PropKey;

// Text / Span leaves keep the spaces of interpolated values (RN renders the
// string as given); only the template's own line-break indentation at the
// edges is dropped.
$textOf = static fn ($element): mixed => $element->properties()[PropKey::Text->value] ?? null;
$leaf = TemplateRenderer::render(TemplateCompiler::compile('<Text>{{ $name }}{{ $separator }}</Text>'), null, ['name' => 'Ana', 'separator' => ' · ']);
$assert($textOf($leaf) === 'Ana · ', 'A Text leaf must keep the trailing space of an interpolated value: '.var_export($textOf($leaf), true));
$prefixed = TemplateRenderer::render(TemplateCompiler::compile('<Text>{{ \' · \'.$pronouns }}</Text>'), null, ['pronouns' => 'ela/dela']);
$assert($textOf($prefixed) === ' · ela/dela', 'A Text leaf must keep the leading space of an interpolated value.');
$indented = TemplateRenderer::render(TemplateCompiler::compile("<Text>\n    {{ \$name }}\n</Text>"), null, ['name' => 'Ana']);
$assert($textOf($indented) === 'Ana', 'Template line-break indentation around a Text leaf is still dropped.');
$rich = TemplateRenderer::render(
    TemplateCompiler::compile('<Text>{{ $name }}<Span>{{ \' · \'.$pronouns }}</Span></Text>'),
    null,
    ['name' => 'Ana', 'pronouns' => 'ela/dela'],
);
$assert($textOf($rich) === 'Ana · ela/dela', 'A Span leaf must keep the leading space of its interpolated value: '.var_export($textOf($rich), true));

// RN `refreshControl` is a prop of the ScrollView, so the list's own flex
// sizing decides the box: a RefreshControl wrapper adopts its content's flex
// item sizing unless it authors its own.
$refresh = TemplateRenderer::render(
    TemplateCompiler::compile('<RefreshControl :refreshing="false"><ScrollView flexGrow="1" minHeight="0"><Text>Row</Text></ScrollView></RefreshControl>'),
    null,
    [],
);
$refreshProps = $refresh->properties();
$assert(
    ($refreshProps[PropKey::FlexGrow->value] ?? null) == 1.0 && ($refreshProps[PropKey::MinHeight->value] ?? null) == 0.0,
    'A RefreshControl must take its ScrollView flex sizing (RN refreshControl prop): '.json_encode($refreshProps),
);
$authored = TemplateRenderer::render(
    TemplateCompiler::compile('<RefreshControl :refreshing="false" flexGrow="2"><ScrollView flexGrow="1"><Text>Row</Text></ScrollView></RefreshControl>'),
    null,
    [],
);
$assert(($authored->properties()[PropKey::FlexGrow->value] ?? null) == 2.0, 'An authored RefreshControl flexGrow wins.');
