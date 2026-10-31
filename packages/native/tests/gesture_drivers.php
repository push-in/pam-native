<?php

declare(strict_types=1);

use Pam\Native\Animation\Drag;
use Pam\Native\Animation\KeyboardProgress;
use Pam\Native\PropKey;
use Pam\Native\UI\Pressable;
use Pam\Native\UI\View;

$driver = KeyboardProgress::make()->drive('opacity', 1, 0)->drive('translateY', 0, -120);
$assert($driver->encode() === "opacity|1|0\ntranslateY|0|-120", 'Keyboard tracks encode absolute endpoints.');
$assert(View::make()->keyboardProgress($driver)->properties[PropKey::KeyboardProgress->value] === $driver->encode(), 'Keyboard driver is available on elements.');
foreach ([0xFFE5E7EB, 0xFF374151] as $color) {
    $assert(Pressable::make()->pressedBackgroundColor($color)->properties[PropKey::PressBackgroundColor->value] === $color, 'Pressable transmits themed pressed colors.');
}
foreach ([['scale', 1, 2], ['opacity', 0, 2], ['translateX', NAN, 0]] as [$property, $hidden, $open]) {
    try {
        KeyboardProgress::make()->drive($property, $hidden, $open);
        $assert(false, 'Invalid keyboard track must throw.');
    } catch (InvalidArgumentException) {
        $assert(true, 'Invalid keyboard track rejected.');
    }
}

$scope = new class {
    public KeyboardProgress $progress;
    public function __construct() { $this->progress = KeyboardProgress::make()->drive('opacity', 1, 0); }
};
$template = \Pam\Native\Internal\TemplateRenderer::render(
    \Pam\Native\Internal\TemplateCompiler::compile(
        '<Column><Pressable pressedBackgroundColor="#E5E7EB" /><Pressable pressedBackgroundColor="#374151" /><View :keyboardProgress="$progress" /></Column>',
        'gesture-drivers.pam.php',
    ),
    $scope,
    [],
);
[$light, $dark, $driven] = $template->children();
$assert($light->properties()[PropKey::PressBackgroundColor->value] === 0xFFE5E7EB, 'Templates convert light pressed colors.');
$assert($dark->properties()[PropKey::PressBackgroundColor->value] === 0xFF374151, 'Templates convert dark pressed colors.');
$assert($driven->properties()[PropKey::KeyboardProgress->value] === 'opacity|1|0', 'Templates encode typed keyboard drivers.');

try {
    \Pam\Native\RendererContract::use(\Pam\Native\RendererContract::fromJson(
        '{"abi":1,"events":[80,1000],"properties":[543,1007],"protocol":[1,1]}',
    ));
    foreach ([Pressable::make()->pressedBackgroundColor(0xFF374151), View::make()->keyboardProgress($driver)] as $element) {
        try {
            (new \Pam\Native\Internal\TreeEncoder())->encode($element);
            $assert(false, 'Older renderers must refuse new driver properties.');
        } catch (\Pam\Native\ProtocolMismatchException) {
            $assert(true, 'Older renderers refuse unsupported drivers.');
        }
    }
} finally {
    \Pam\Native\RendererContract::use(null);
}
