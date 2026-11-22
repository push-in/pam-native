<?php

declare(strict_types=1);

use Pam\Native\EventKind;
use Pam\Native\Internal\TemplateCompiler;
use Pam\Native\Internal\TemplateRenderer;
use Pam\Native\Internal\TreeEncoder;
use Pam\Native\Internal\Wire;
use Pam\Native\MediaTracksEvent;
use Pam\Native\PropKey;
use Pam\Native\ProtocolMismatchException;
use Pam\Native\RendererContract;
use Pam\Native\UI\MediaPlayer;

$assert ??= static function (bool $condition, string $message): void {
    if (!$condition) throw new RuntimeException($message);
};

// Track selection: extension properties 1039-1041 and extension event 1002.
$assert(
    PropKey::MediaAudioTrack->value === 1039 && PropKey::MediaTextTrack->value === 1040
        && PropKey::OnMediaTracks->value === 1041 && EventKind::MediaTracks->value === 1002,
    'audioTrack, textTrack and onTracks use 1039-1041 and event 1002.',
);
$player = MediaPlayer::make('https://cdn.example/a.mkv')->audioTrack('1:0')->textTrack('3:1')
    ->onTracks(static fn (MediaTracksEvent $event): null => null)->toElement()->properties();
$assert(
    $player[PropKey::MediaAudioTrack->value] === '1:0' && $player[PropKey::MediaTextTrack->value] === '3:1'
        && isset($player[PropKey::OnMediaTracks->value]),
    'audioTrack(), textTrack() and onTracks() set their properties.',
);
$plain = MediaPlayer::make('a.mp4')->toElement()->properties();
$assert(
    !isset($plain[1039]) && !isset($plain[1040]) && !isset($plain[1041]),
    'A player without track selection sends none of 1039-1041 (works on older renderers).',
);

$event = MediaTracksEvent::fromPayload(Wire::map(['tracks' => json_encode([
    ['id' => '1:0', 'type' => 'audio', 'language' => 'pt', 'label' => 'Português', 'selected' => true],
    ['id' => '1:1', 'type' => 'audio', 'language' => 'en', 'label' => '', 'selected' => false],
    ['id' => '3:0', 'type' => 'text', 'language' => 'pt', 'label' => 'Forced', 'selected' => false],
    ['id' => '', 'type' => 'text'],
    ['id' => '4:0', 'type' => 'video'],
])]));
$assert(
    count($event->audio) === 2 && $event->audio[0] === ['id' => '1:0', 'language' => 'pt', 'label' => 'Português', 'selected' => true]
        && count($event->text) === 1 && $event->text[0]['id'] === '3:0' && $event->text[0]['selected'] === false,
    'MediaTracksEvent splits audio and text tracks and drops invalid ones.',
);
$empty = MediaTracksEvent::fromPayload('');
$assert($empty->audio === [] && $empty->text === [], 'An empty payload has no tracks.');

$scope = new class {
    public ?MediaTracksEvent $seen = null;

    public function tracks(MediaTracksEvent $event): void
    {
        $this->seen = $event;
    }
};
$template = TemplateRenderer::render(
    TemplateCompiler::compile('<Video source="https://cdn.example/a.mkv" audioTrack="1:1" textTrack="" on:mediaTracks="tracks" />', 'media-tracks.pam.php'),
    $scope,
    [],
);
$props = $template->properties();
$assert(
    $props[PropKey::MediaAudioTrack->value] === '1:1' && $props[PropKey::MediaTextTrack->value] === ''
        && isset($props[PropKey::OnMediaTracks->value]),
    'Templates expose audioTrack, textTrack and on:mediaTracks.',
);

try {
    RendererContract::use(RendererContract::fromJson('{"abi":1,"events":[80,1001],"properties":[543,1038],"protocol":[1,1]}'));
    $refused = false;
    try {
        (new TreeEncoder())->encode(MediaPlayer::make('a.mp4')->textTrack('3:0'));
    } catch (ProtocolMismatchException $error) {
        $refused = str_contains($error->getMessage(), 'MediaTextTrack (1040)');
    }
    $assert($refused, 'A renderer declaring up to 1038 refuses textTrack by name.');
    RendererContract::use(RendererContract::fromJson('{"abi":1,"events":[80,1002],"properties":[543,1041],"protocol":[1,1]}'));
    $frame = (new TreeEncoder())->encode(MediaPlayer::make('a.mp4')->textTrack('3:0'))['frame'];
    $assert(is_string($frame) && str_contains($frame, pack('v', 1040)), 'A renderer declaring 1041 receives it.');
} finally {
    RendererContract::use(null);
}
