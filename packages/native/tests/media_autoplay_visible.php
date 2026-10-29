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

// MediaPlayer visible autoplay: extension properties 1006 (fraction) and 1007 (group).
$assert(PropKey::MediaAutoPlayVisible->value === 1006 && PropKey::MediaAutoPlayGroup->value === 1007, 'Visible autoplay is 1006/1007.');
$props = MediaPlayer::make('https://cdn.example/a.mp4')->autoPlay()->autoPlayWhenVisible(0.5, 'feed')->toElement()->properties();
$assert($props[PropKey::MediaAutoPlayVisible->value] === 0.5, 'autoPlayWhenVisible() sends the fraction as a float.');
$assert($props[PropKey::MediaAutoPlayGroup->value] === 'feed', 'autoPlayWhenVisible() sends the group.');
$assert($props[PropKey::MediaAutoPlay->value] === true, 'autoPlay stays the play intent.');
$alone = MediaPlayer::make('a.mp4')->autoPlayWhenVisible()->toElement()->properties();
$assert($alone[PropKey::MediaAutoPlayVisible->value] === 0.5 && !isset($alone[PropKey::MediaAutoPlayGroup->value]), 'Default: half visible, no group.');
$plain = MediaPlayer::make('a.mp4')->toElement()->properties();
$assert(
    !isset($plain[PropKey::MediaAutoPlayVisible->value]) && !isset($plain[PropKey::MediaAutoPlayGroup->value]),
    'A player that never asks for visible autoplay sends neither 1006 nor 1007.',
);
foreach ([0.0, -0.1, 1.5, NAN] as $bad) {
    $refused = false;
    try {
        MediaPlayer::make('a.mp4')->autoPlayWhenVisible($bad);
    } catch (InvalidArgumentException) {
        $refused = true;
    }
    $assert($refused, 'A fraction outside (0, 1] is refused.');
}

$templateScope = new class {
    public float $fraction = 0.6;
};
$template = TemplateRenderer::render(
    TemplateCompiler::compile(
        '<Column>'
        .'<Video source="https://cdn.example/a.mp4" autoPlay="true" autoPlayVisible="0.5" autoPlayGroup="feed" />'
        .'<Video source="https://cdn.example/b.mp4" autoPlayVisible="true" />'
        .'<Video source="https://cdn.example/c.mp4" :autoPlayVisible="$fraction" />'
        .'<Video source="https://cdn.example/d.mp4" autoPlayVisible="false" autoPlayGroup="" />'
        .'<Video source="https://cdn.example/e.mp4" autoPlayVisible="2" />'
        .'</Column>',
        'media-autoplay-visible.pam.php',
    ),
    $templateScope,
    [],
);
[$grouped, $flag, $dynamic, $off, $clamped] = $template->children();
$visible = PropKey::MediaAutoPlayVisible->value;
$group = PropKey::MediaAutoPlayGroup->value;
$assert($grouped->properties()[$visible] === 0.5 && $grouped->properties()[$group] === 'feed', 'Templates expose autoPlayVisible and autoPlayGroup.');
$assert($flag->properties()[$visible] === 0.5, 'autoPlayVisible="true" means half visible.');
$assert($dynamic->properties()[$visible] === 0.6, 'A bound fraction is sent as is.');
$assert(!isset($off->properties()[$visible]) && !isset($off->properties()[$group]), 'false and an empty group send nothing.');
$assert($clamped->properties()[$visible] === 1.0, 'A fraction above 1 is clamped to fully visible.');

try {
    RendererContract::use(RendererContract::fromJson('{"abi":1,"events":[79,1000],"properties":[540,1005],"protocol":[1,1]}'));
    $refused = false;
    try {
        (new TreeEncoder())->encode(MediaPlayer::make('a.mp4')->autoPlayWhenVisible());
    } catch (ProtocolMismatchException $error) {
        $refused = str_contains($error->getMessage(), 'MediaAutoPlayVisible (1006)');
    }
    $assert($refused, 'A renderer declaring up to 1005 refuses visible autoplay by name.');
    (new TreeEncoder())->encode(MediaPlayer::make('a.mp4')->autoPlay());
    RendererContract::use(RendererContract::fromJson('{"abi":1,"events":[79,1000],"properties":[540,1007],"protocol":[1,1]}'));
    $frame = (new TreeEncoder())->encode(MediaPlayer::make('a.mp4')->autoPlayWhenVisible(0.5, 'feed'))['frame'];
    $assert(
        is_string($frame) && str_contains($frame, pack('v', 1006)) && str_contains($frame, pack('v', 1007)),
        'A renderer declaring 1007 receives the fraction and the group.',
    );
} finally {
    RendererContract::use(null);
}
