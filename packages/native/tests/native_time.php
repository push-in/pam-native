<?php

declare(strict_types=1);

use Pam\Native\ClockFormat;
use Pam\Native\Internal\TemplateCompiler;
use Pam\Native\Internal\TemplateRenderer;
use Pam\Native\Internal\TreeEncoder;
use Pam\Native\PropKey;
use Pam\Native\ProtocolMismatchException;
use Pam\Native\RendererContract;
use Pam\Native\UI\Text;
use Pam\Native\UI\View;

$assert ??= static function (bool $condition, string $message): void {
    if (!$condition) throw new RuntimeException($message);
};
$refuses = static function (Closure $body): bool {
    try {
        $body();
    } catch (InvalidArgumentException|RuntimeException) {
        return true;
    }

    return false;
};

// Native time primitives: extension properties 1022-1034, append-only.
$assert(
    PropKey::ClockStartedAt->value === 1022 && PropKey::ClockEndsAt->value === 1023
        && PropKey::ClockDurationMs->value === 1024 && PropKey::ClockOffsetMs->value === 1025
        && PropKey::ClockRunning->value === 1026 && PropKey::ClockFormat->value === 1027
        && PropKey::ProgressStartedAt->value === 1028 && PropKey::ProgressDurationMs->value === 1029
        && PropKey::ProgressOffsetMs->value === 1030 && PropKey::ProgressRunning->value === 1031
        && PropKey::RevealCharMs->value === 1032 && PropKey::RevealDurationMs->value === 1033
        && PropKey::RevealKey->value === 1034,
    'Clock, timed progress and reveal properties are 1022-1034.',
);
$assert(
    array_map(static fn (ClockFormat $f): int => $f->value, ClockFormat::cases()) === [1, 2, 3, 4]
        && array_map(static fn (ClockFormat $f): string => $f->templateName(), ClockFormat::cases())
            === ['m:ss', 'mm:ss', 'h:mm:ss', 'seconds'],
    'ClockFormat is 1 m:ss, 2 mm:ss, 3 h:mm:ss, 4 seconds.',
);

// --- Builders -------------------------------------------------------------
$started = 1_763_300_000_000;
$call = Text::make('00:00')->clock($started, ClockFormat::PaddedMinutesSeconds)->toElement()->properties();
$assert(
    $call[1022] === $started && $call[1025] === 0 && $call[1027] === 2
        && !array_key_exists(1026, $call) && !array_key_exists(1023, $call) && !array_key_exists(1024, $call),
    'clock() sends the start, a zero offset and the format; running is the default.',
);
$paused = Text::make('0:00')->clock($started, offsetMs: 42_000, running: false)->toElement()->properties();
$assert(
    $paused[1022] === $started && $paused[1025] === 42_000 && $paused[1026] === false && $paused[1027] === 1,
    'A paused clock sends running=false and the time counted so far.',
);
$idle = Text::make('0:00')->clock(null)->toElement()->properties();
$assert(
    !array_key_exists(1022, $idle) && $idle[1025] === 0 && $idle[1026] === false,
    'A clock with no start yet stands at its offset (not running).',
);
$countdown = Text::make('30')->countdown(30_000, $started, offsetMs: 5_000)->toElement()->properties();
$assert(
    $countdown[1024] === 30_000 && $countdown[1022] === $started && $countdown[1025] === 5_000
        && $countdown[1027] === ClockFormat::Seconds->value && !array_key_exists(1023, $countdown),
    'countdown() sends the duration with the clock source; seconds by default.',
);
$deadline = Text::make('5')->clock($started)->countdownTo($started + 5_000)->toElement()->properties();
$assert(
    $deadline[1023] === $started + 5_000 && $deadline[1027] === 4
        && !array_key_exists(1022, $deadline) && !array_key_exists(1024, $deadline)
        && !array_key_exists(1025, $deadline) && !array_key_exists(1026, $deadline),
    'countdownTo() replaces any earlier clock source with the deadline.',
);
$assert(
    !array_key_exists(1024, Text::make('0')->countdown(9_000, $started)->clock($started)->toElement()->properties()),
    'clock() after countdown() counts up again.',
);
$assert(
    $refuses(static fn () => Text::make('')->clock(-1))
        && $refuses(static fn () => Text::make('')->clock($started, offsetMs: -1))
        && $refuses(static fn () => Text::make('')->countdown(-1, $started)),
    'Negative clock times are refused.',
);

$bar = View::make()->timeProgress($started, 30_000)->toElement()->properties();
$assert(
    $bar[1028] === $started && $bar[1029] === 30_000 && $bar[1030] === 0 && !array_key_exists(1031, $bar),
    'timeProgress() sends start, duration and offset; running is the default.',
);
$heldBar = View::make()->timeProgress($started, 30_000, 12_000, false)->toElement()->properties();
$assert($heldBar[1030] === 12_000 && $heldBar[1031] === false, 'A paused bar sends running=false and its offset.');
$unstarted = View::make()->timeProgress(null, 15_000)->toElement()->properties();
$assert(!array_key_exists(1028, $unstarted) && $unstarted[1031] === false, 'A bar with no start yet does not run.');
$assert($refuses(static fn () => View::make()->timeProgress($started, -5)), 'A negative duration is refused.');

$typed = Text::make('Hello')->reveal(55, 'preview-3')->toElement()->properties();
$assert(
    $typed[1032] === 55 && $typed[1034] === 'preview-3' && !array_key_exists(1033, $typed),
    'reveal() sends ms per character and the restart key.',
);
$over = Text::make('Hello')->reveal(55, 'x')->revealOver(1_200, 7)->toElement()->properties();
$assert(
    $over[1033] === 1_200 && $over[1034] === '7' && !array_key_exists(1032, $over),
    'revealOver() replaces the per-character rate with a total duration.',
);
$assert(!array_key_exists(1034, Text::make('Hi')->reveal(40)->toElement()->properties()), 'A reveal without key sends none.');
$assert(
    $refuses(static fn () => Text::make('')->reveal(0))
        && $refuses(static fn () => Text::make('')->revealOver(0))
        && $refuses(static fn () => Text::make('')->reveal(50, str_repeat('k', 257))),
    'A zero rate, a zero duration and an oversized key are refused.',
);

// --- Templates ------------------------------------------------------------
$scope = new class {
    public ?int $startedAt = 1_763_300_000_000;
    public float $floatStart = 1_763_300_000_123.9;
    public ?int $missing = null;
    public bool $recording = true;
    public int $previewKey = 3;
};
$tree = TemplateRenderer::render(
    TemplateCompiler::compile(
        '<Column>'
        .'<Text :clockStartedAt="$startedAt" clockFormat="mm:ss">00:00</Text>'
        .'<Text clockEndsAt="1763300009000" clockFormat="seconds">9</Text>'
        .'<Text :clockStartedAt="$floatStart" clockDurationMs="30000" clockOffsetMs="2500" clockRunning="false" clockFormat="h:mm:ss">0:00:30</Text>'
        .'<Text :clockStartedAt="$missing" clockFormat="m:ss">0:00</Text>'
        .'<View :progressStartedAt="$startedAt" progressDurationMs="30000" :progressRunning="$recording" progressOffsetMs="0" />'
        .'<Text revealCharMs="55" :revealKey="$previewKey">Story</Text>'
        .'<Text revealDurationMs="900" revealCharMs="0">Fast</Text>'
        .'</Column>',
        'native-time.pam.php',
    ),
    $scope,
    [],
);
[$tCall, $tDeadline, $tPaused, $tMissing, $tBar, $tTyped, $tOver] = $tree->children();
$p = static fn ($element): array => $element->properties();
$assert(
    $p($tCall)[1022] === 1_763_300_000_000 && $p($tCall)[1027] === 2,
    'Templates bind clockStartedAt and spell clockFormat (mm:ss).',
);
$assert($p($tDeadline)[1023] === 1_763_300_009_000 && $p($tDeadline)[1027] === 4, 'clockEndsAt with format seconds.');
$assert(
    $p($tPaused)[1022] === 1_763_300_000_123 && $p($tPaused)[1024] === 30_000 && $p($tPaused)[1025] === 2_500
        && $p($tPaused)[1026] === false && $p($tPaused)[1027] === 3,
    'A float start (microtime) is floored; duration, offset and running=false are sent.',
);
$assert(!array_key_exists(1022, $p($tMissing)), 'A null start sends nothing: the clock turns off.');
$assert(
    $p($tBar)[1028] === 1_763_300_000_000 && $p($tBar)[1029] === 30_000 && $p($tBar)[1030] === 0 && $p($tBar)[1031] === true,
    'Templates bind progressStartedAt, progressDurationMs, progressOffsetMs and progressRunning.',
);
$assert($p($tTyped)[1032] === 55 && $p($tTyped)[1034] === '3', 'revealCharMs and a bound revealKey (int becomes a string).');
$assert(
    $p($tOver)[1033] === 900 && !array_key_exists(1032, $p($tOver)),
    'revealDurationMs; a zero revealCharMs is not sent.',
);
$badFormat = $refuses(static fn () => TemplateRenderer::render(
    TemplateCompiler::compile('<Text clockStartedAt="1" clockFormat="hh:mm">0</Text>', 'bad-clock.pam.php'),
    $scope,
    [],
));
$assert($badFormat, 'An unknown clockFormat is refused with the accepted spellings.');

// --- Contract: older renderers never receive the new identifiers -----------
try {
    RendererContract::use(RendererContract::fromJson('{"abi":1,"events":[79,1001],"properties":[543,1020],"protocol":[1,1]}'));
    $refused = false;
    try {
        (new TreeEncoder())->encode(Text::make('0:00')->clock($started));
    } catch (ProtocolMismatchException $error) {
        $refused = str_contains($error->getMessage(), 'ClockStartedAt (1022)');
    }
    $assert($refused, 'A renderer declaring up to 1020 refuses a native clock by name.');
    RendererContract::use(RendererContract::fromJson('{"abi":1,"events":[79,1001],"properties":[543,1034],"protocol":[1,1]}'));
    $frame = (new TreeEncoder())->encode(
        View::make(Text::make('Hi')->reveal(50, 'k'))->timeProgress($started, 1_000),
    )['frame'];
    $assert(
        is_string($frame) && str_contains($frame, pack('v', 1028)) && str_contains($frame, pack('v', 1032))
            && str_contains($frame, pack('v', 1034)),
        'A renderer declaring 1034 receives progress and reveal properties.',
    );
} finally {
    RendererContract::use(null);
}
