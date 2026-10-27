<?php

declare(strict_types=1);

use Pam\Native\Internal\TreeEncoder;
use Pam\Native\NodeKind;
use Pam\Native\PropKey;
use Pam\Native\TextScalePolicy;
use Pam\Native\UI\Column;
use Pam\Native\UI\Text;

$assert ??= static function (bool $condition, string $message): void {
    if (!$condition) throw new RuntimeException($message);
};

// One rule per typographic role: body text up to 1.6x, display text (>= 18
// points) up to 1.3x; an element's own cap or opt-out wins.
$caps = static function (): array {
    $encoder = new TreeEncoder();
    $encoder->encode(Column::make(
        Text::make('body')->__pamWithProperties([PropKey::FontSize->value => 15.0]),
        Text::make('title')->__pamWithProperties([PropKey::FontSize->value => 28.0]),
        Text::make('badge')->__pamWithProperties([PropKey::FontSize->value => 11.0, PropKey::TextMaxFontSizeMultiplier->value => 1.2]),
        Text::make('fixed')->__pamWithProperties([PropKey::FontSize->value => 30.0, PropKey::TextAllowFontScaling->value => false]),
    ));
    $found = [];
    foreach ($encoder->frameNodes() as $node) {
        if ($node->kind === NodeKind::Text->value) {
            $found[] = $node->properties[PropKey::TextMaxFontSizeMultiplier->value] ?? null;
        }
    }

    return $found;
};
$assert($caps() === [null, null, TreeEncoder::encodeProperty(1.2), null], 'Without a policy nothing is capped.');
TextScalePolicy::set(maximum: 1.6, display: 1.3, displayFontSize: 18);
$assert(
    $caps() === [TreeEncoder::encodeProperty(1.6), TreeEncoder::encodeProperty(1.3), TreeEncoder::encodeProperty(1.2), null],
    'Body text gets the maximum, display text the display cap; own caps and opt-outs stay.',
);
TextScalePolicy::reset();
echo "TEXT_SCALE_POLICY_OK body=1.6 display=1.3 own=kept\n";
