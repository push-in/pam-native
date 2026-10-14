<?php

declare(strict_types=1);

use Pam\Native\Element;
use Pam\Native\EventKind;
use Pam\Native\Internal\EncodedNode;
use Pam\Native\Internal\TemplateCompiler;
use Pam\Native\Internal\TemplateRenderer;
use Pam\Native\Internal\TreeEncoder;
use Pam\Native\NodeKind;
use Pam\Native\PropKey;
use Pam\Native\UI\Scroll;
use Pam\Native\UI\Text;

// RN `refreshControl` given as props of a ScrollView/list is lowered to a
// native RefreshControl host: refresh props and the outer box move to the
// host, everything else stays on the scrollable.
$refreshNodes = static function (TreeEncoder $encoder, Element $tree): array {
    $result = $encoder->encode($tree);
    $byKind = [];
    foreach ($encoder->frameNodes() as $node) {
        \assert($node instanceof EncodedNode);
        $byKind[$node->kind][] = $node;
    }

    return [$byKind, $result['callbacks']];
};
$refreshScope = new class {
    public int $pulls = 0;

    public function pull(): void
    {
        ++$this->pulls;
    }
};
$refreshTemplate = TemplateCompiler::compile(
    '<Column>'
    .'<VirtualizedList flexGrow="1" minHeight="0" paddingBottom="24" :refreshing="$refreshing"'
    .' on:refresh="pull" refreshColors="#ff0000, #00ff00" progressViewOffset="56" tintColor="#22c55e" :refreshEnabled="$enabled">'
    .'<Text>One</Text><Text>Two</Text>'
    .'</VirtualizedList>'
    .'</Column>',
    'refresh-control.pam.php',
);
$refreshEncoder = new TreeEncoder();
[$byKind, $callbacks] = $refreshNodes(
    $refreshEncoder,
    TemplateRenderer::render($refreshTemplate, $refreshScope, ['refreshing' => false, 'enabled' => false]),
);
$host = $byKind[NodeKind::RefreshControl->value][0] ?? null;
$list = $byKind[NodeKind::VirtualList->value][0] ?? null;
$assert(
    $host instanceof EncodedNode && $list instanceof EncodedNode && $list->parent === $host->id
        && isset($host->properties[PropKey::Refreshing->value], $host->properties[PropKey::OnRefresh->value])
        && isset($host->properties[PropKey::FlexGrow->value], $host->properties[PropKey::MinHeight->value])
        && isset($host->properties[PropKey::RefreshColors->value], $host->properties[PropKey::RefreshProgressViewOffset->value])
        && $host->properties[PropKey::Enabled->value] === TreeEncoder::encodeProperty(false)
        && $host->properties[PropKey::RefreshColors->value]
            === TreeEncoder::encodeProperty('4294901760,4278255360')
        && !isset($list->properties[PropKey::Refreshing->value], $list->properties[PropKey::OnRefresh->value])
        && !isset($list->properties[PropKey::FlexGrow->value], $list->properties[PropKey::RefreshEnabled->value])
        && isset($list->properties[PropKey::PaddingBottom->value])
        && isset($host->properties[PropKey::TintColor->value]) && !isset($list->properties[PropKey::TintColor->value]),
    'refreshing/on:refresh on a VirtualizedList must host it in a RefreshControl that takes the refresh props and outer box.',
);
($callbacks[$host->id.':'.EventKind::Refresh->value] ?? static fn () => null)();
$assert(
    $refreshScope->pulls === 1 && !isset($callbacks[$list->id.':'.EventKind::Refresh->value]),
    'The refresh handler must move to the RefreshControl host.',
);
[$byKind] = $refreshNodes(
    $refreshEncoder,
    TemplateRenderer::render($refreshTemplate, $refreshScope, ['refreshing' => true, 'enabled' => true]),
);
$assert(
    ($byKind[NodeKind::RefreshControl->value][0] ?? null)?->id === $host->id
        && ($byKind[NodeKind::VirtualList->value][0] ?? null)?->id === $list->id,
    'Toggling refreshing must keep the native identity of the host and the list.',
);

$plainEncoder = new TreeEncoder();
[$plain] = $refreshNodes(
    $plainEncoder,
    Scroll::make(Text::make('Static'))->toElement(),
);
$assert(
    !isset($plain[NodeKind::RefreshControl->value]),
    'A scrollable without refresh props must not get a RefreshControl host.',
);

$fluentEncoder = new TreeEncoder();
$fluentPulls = 0;
[$fluent, $fluentCallbacks] = $refreshNodes(
    $fluentEncoder,
    Scroll::make(Text::make('Feed'))
        ->refreshing(false)
        ->refreshColors(0xFF2563EB)
        ->progressViewOffset(88.0)
        ->onRefresh(static function () use (&$fluentPulls): void {
            ++$fluentPulls;
        })
        ->toElement(),
);
$fluentHost = $fluent[NodeKind::RefreshControl->value][0] ?? null;
($fluentCallbacks[($fluentHost?->id ?? 0).':'.EventKind::Refresh->value] ?? static fn () => null)();
$assert(
    $fluentHost !== null && ($fluent[NodeKind::Scroll->value][0] ?? null)?->parent === $fluentHost->id
        && $fluentPulls === 1,
    'Scroll->refreshing()->onRefresh() must host the ScrollView in a native RefreshControl.',
);
