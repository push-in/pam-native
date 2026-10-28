<?php

declare(strict_types=1);

use Pam\Native\Internal\TemplateCompiler;
use Pam\Native\Internal\TemplateRenderer;
use Pam\Native\Internal\TreeEncoder;
use Pam\Native\PropKey;
use Pam\Native\ProtocolMismatchException;
use Pam\Native\RendererContract;
use Pam\Native\UI\MediaPlayer;

$assert ??= static function (bool $condition, string $message): void {
    if (!$condition) throw new RuntimeException($message);
};

// MediaPlayer idleSurface: extension property 1004.
$assert(PropKey::MediaIdleSurface->value === 1004, 'idleSurface is extension property 1004.');
$idle = MediaPlayer::make('https://cdn.example/a.mp4')->idleSurface()->toElement();
$assert($idle->properties()[PropKey::MediaIdleSurface->value] === true, 'idleSurface() sets the flag.');
$assert(
    !isset(MediaPlayer::make('a.mp4')->toElement()->properties()[PropKey::MediaIdleSurface->value]),
    'A player that never asks for an idle surface does not send 1004 (works on renderers without it).',
);
$templateScope = new class {
};
$template = TemplateRenderer::render(
    TemplateCompiler::compile('<Column><Video source="https://cdn.example/a.mp4" idleSurface="true" /><Video source="https://cdn.example/b.mp4" :idleSurface="false" /></Column>', 'media-idle-surface.pam.php'),
    $templateScope,
    [],
);
[$on, $off] = $template->children();
$assert(
    $on->properties()[PropKey::MediaIdleSurface->value] === true && $off->properties()[PropKey::MediaIdleSurface->value] === false,
    'Templates expose idleSurface.',
);
try {
    RendererContract::use(RendererContract::fromJson('{"abi":1,"events":[79,1000],"properties":[540,1003],"protocol":[1,1]}'));
    $refused = false;
    try {
        (new TreeEncoder())->encode(MediaPlayer::make('a.mp4')->idleSurface());
    } catch (ProtocolMismatchException $error) {
        $refused = str_contains($error->getMessage(), 'MediaIdleSurface (1004)');
    }
    $assert($refused, 'A renderer declaring up to 1003 refuses idleSurface by name.');
    RendererContract::use(RendererContract::fromJson('{"abi":1,"events":[79,1000],"properties":[540,1004],"protocol":[1,1]}'));
    $frame = (new TreeEncoder())->encode(MediaPlayer::make('a.mp4')->idleSurface())['frame'];
    $assert(is_string($frame) && str_contains($frame, pack('v', 1004)), 'A renderer declaring 1004 receives it.');
} finally {
    RendererContract::use(null);
}
