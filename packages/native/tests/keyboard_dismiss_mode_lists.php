<?php

declare(strict_types=1);

use Pam\Native\Internal\TemplateCompiler;
use Pam\Native\Internal\TemplateRenderer;
use Pam\Native\PropKey;

$assert ??= static function (bool $condition, string $message): void {
    if (!$condition) throw new RuntimeException($message);
};

// Lists honour keyboardDismissMode like a ScrollView (React Native FlatList
// inherits it); nothing is sent unless the template sets it.
$key = PropKey::ScrollKeyboardDismissMode->value;
$template = TemplateRenderer::render(
    TemplateCompiler::compile(
        '<Column>'
        .'<VirtualizedList keyboardDismissMode="interactive"><Text>a</Text></VirtualizedList>'
        .'<VirtualizedList keyboardDismissMode="on-drag"><Text>b</Text></VirtualizedList>'
        .'<VirtualizedList><Text>c</Text></VirtualizedList>'
        .'<Scroll keyboardDismissMode="on-drag"><Text>d</Text></Scroll>'
        .'</Column>',
        'keyboard-dismiss-mode-lists.pam.php',
    ),
    new stdClass(),
    [],
);
$values = array_map(
    static fn ($child): mixed => $child->properties()[$key] ?? null,
    $template->children(),
);
$assert(
    $values === [3, 2, null, 2],
    'VirtualizedList forwards keyboardDismissMode only when set: '.json_encode($values),
);

echo "KEYBOARD_DISMISS_MODE_LISTS_OK\n";
