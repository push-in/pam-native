<?php

declare(strict_types=1);

use Pam\Native\Internal\TemplateCompiler;
use Pam\Native\Internal\TemplateRenderer;
use Pam\Native\KeyboardPersistTaps;
use Pam\Native\PropKey;
use Pam\Native\UI\FlatList;
use Pam\Native\UI\Scroll;
use Pam\Native\UI\SectionList;
use Pam\Native\UI\Text;
use Pam\Native\UI\VirtualizedList;

$assert ??= static function (bool $condition, string $message): void {
    if (!$condition) throw new RuntimeException($message);
};

// Protocol: 537 is reserved for AccessibilitySelected, 538 is this property.
$assert(
    PropKey::AccessibilitySelected->value === 537 && PropKey::ScrollKeyboardPersistTaps->value === 538,
    'keyboardShouldPersistTaps must use property 538 after AccessibilitySelected 537.',
);
$assert(
    KeyboardPersistTaps::Never->value === 1 && KeyboardPersistTaps::Always->value === 2
        && KeyboardPersistTaps::Handled->value === 3,
    'KeyboardPersistTaps values are sequential integers from 1.',
);

$key = PropKey::ScrollKeyboardPersistTaps->value;
$scroll = Scroll::make(Text::make('x'));
$assert(!isset($scroll->toElement()->properties()[$key]), 'Absent keyboardShouldPersistTaps sends nothing (historical behaviour).');
$assert(
    $scroll->keyboardShouldPersistTaps(KeyboardPersistTaps::Handled)->toElement()->properties()[$key] === 3
        && Scroll::make(Text::make('x'))->keyboardShouldPersistTaps(true)->toElement()->properties()[$key] === 2
        && Scroll::make(Text::make('x'))->keyboardShouldPersistTaps(false)->toElement()->properties()[$key] === 1,
    'Scroll::keyboardShouldPersistTaps() accepts the enum and RN booleans.',
);
$assert(
    FlatList::make(['a'])->keyboardShouldPersistTaps(KeyboardPersistTaps::Always)->toElement()->properties()[$key] === 2
        && SectionList::make([])->keyboardShouldPersistTaps(KeyboardPersistTaps::Never)->toElement()->properties()[$key] === 1
        && VirtualizedList::make(Text::make('a'))->keyboardShouldPersistTaps(KeyboardPersistTaps::Handled)->toElement()->properties()[$key] === 3,
    'FlatList, SectionList and VirtualizedList expose keyboardShouldPersistTaps.',
);

$template = TemplateRenderer::render(
    TemplateCompiler::compile(
        '<Column>'
        .'<Scroll keyboardShouldPersistTaps="handled"><Text>a</Text></Scroll>'
        .'<Scroll keyboardShouldPersistTaps="never"><Text>b</Text></Scroll>'
        .'<Scroll keyboardShouldPersistTaps="always"><Text>c</Text></Scroll>'
        .'<Scroll keyboardShouldPersistTaps="true"><Text>d</Text></Scroll>'
        .'<Scroll :keyboardShouldPersistTaps="false"><Text>e</Text></Scroll>'
        .'</Column>',
        'keyboard-persist-taps.pam.php',
    ),
    new stdClass(),
    [],
);
$values = array_map(
    static fn ($child): mixed => $child->properties()[$key] ?? null,
    $template->children(),
);
$assert(
    $values === [3, 1, 2, 2, 1],
    'Templates map keyboardShouldPersistTaps never/always/handled and booleans: '.json_encode($values),
);

echo "KEYBOARD_PERSIST_TAPS_OK\n";
