<?php

declare(strict_types=1);

use Pam\Native\AppState;
use Pam\Native\ClockFormat;
use Pam\Native\Component;
use Pam\Native\Element;
use Pam\Native\Internal\CompiledTemplateNode;
use Pam\Native\Internal\ComponentLifecycle;
use Pam\Native\Internal\DependencyTracker;
use Pam\Native\Internal\NativeTime;
use Pam\Native\Internal\Runtime;
use Pam\Native\Internal\ScopedStyleCompiler;
use Pam\Native\Internal\TemplateCompiler;
use Pam\Native\Internal\TemplateRenderer;
use Pam\Native\Internal\TreeEncoder;
use Pam\Native\ModuleResultStatus;
use Pam\Native\PropKey;
use Pam\Native\ProtocolMismatchException;
use Pam\Native\Renderable;
use Pam\Native\RendererContract;
use Pam\Native\UI\Column;
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

// --- Clock labels: the framework renders what the native clock shows -------
// ClockFormat::format() is Android/iOS PamClockFormat.format(), value for value.
$formats = [
    [ClockFormat::MinutesSeconds, 42, '0:42'], [ClockFormat::MinutesSeconds, 3_665, '1:01:05'],
    [ClockFormat::MinutesSeconds, -5, '0:00'], [ClockFormat::MinutesSeconds, 0, '0:00'],
    [ClockFormat::MinutesSeconds, 599, '9:59'], [ClockFormat::MinutesSeconds, 600, '10:00'],
    [ClockFormat::MinutesSeconds, 3_599, '59:59'], [ClockFormat::MinutesSeconds, 3_600, '1:00:00'],
    [ClockFormat::MinutesSeconds, 36_000, '10:00:00'], [ClockFormat::MinutesSeconds, 360_061, '100:01:01'],
    [ClockFormat::PaddedMinutesSeconds, 42, '00:42'], [ClockFormat::PaddedMinutesSeconds, 4_503, '75:03'],
    [ClockFormat::PaddedMinutesSeconds, 5_999, '99:59'], [ClockFormat::PaddedMinutesSeconds, 6_000, '100:00'],
    [ClockFormat::PaddedMinutesSeconds, -1, '00:00'], [ClockFormat::PaddedMinutesSeconds, 60_000, '1000:00'],
    [ClockFormat::HoursMinutesSeconds, 42, '0:00:42'], [ClockFormat::HoursMinutesSeconds, 3_661, '1:01:01'],
    [ClockFormat::HoursMinutesSeconds, 35_999, '9:59:59'], [ClockFormat::HoursMinutesSeconds, 36_000, '10:00:00'],
    [ClockFormat::HoursMinutesSeconds, -7, '0:00:00'],
    [ClockFormat::Seconds, 42, '42'], [ClockFormat::Seconds, 0, '0'], [ClockFormat::Seconds, 9, '9'],
    [ClockFormat::Seconds, 10, '10'], [ClockFormat::Seconds, 100, '100'], [ClockFormat::Seconds, -3, '0'],
];
foreach ($formats as [$format, $seconds, $label]) {
    $assert($format->format($seconds) === $label, "{$format->templateName()} formats {$seconds} as {$label}.");
}

$at = 1_763_300_000_000;
$props = static fn (Text $text): array => $text->toElement()->properties();
$labelAt = static fn (Text $text, int $now): ?string => NativeTime::clockLabel($props($text), $now);
// PamNativeTimeTest (Android) cases.
$up = Text::make('')->clock($at, ClockFormat::PaddedMinutesSeconds, 2_000);
$assert(
    $labelAt($up, $at) === '00:02' && $labelAt($up, $at + 42_999) === '00:44' && $labelAt($up, $at - 500) === '00:02',
    'A clock counts up from its start and offset; a start in the future stands at the offset.',
);
$assert(
    $labelAt(Text::make('')->clock($at, offsetMs: 61_000, running: false), $at + 99_000) === '1:01'
        && $labelAt(Text::make('')->clock(null), $at) === '0:00',
    'A paused or unstarted clock stands at its offset.',
);
$deadline = Text::make('')->countdownTo($at + 5_000);
$timer = Text::make('')->countdown(30_000, $at, ClockFormat::MinutesSeconds, 10_000);
$assert(
    $labelAt($deadline, $at) === '5' && $labelAt($deadline, $at + 1) === '5' && $labelAt($deadline, $at + 4_999) === '1'
        && $labelAt($deadline, $at + 5_000) === '0' && $labelAt($deadline, $at + 9_000) === '0'
        && $labelAt($timer, $at) === '0:20' && $labelAt($timer, $at + 5_000) === '0:15' && $labelAt($timer, $at + 25_000) === '0:00'
        && $labelAt(Text::make('')->countdown(30_000, $at, offsetMs: 10_000, running: false), $at + 9_000) === '20',
    'Countdowns round up, stop at zero and hold while paused.',
);
$assert(NativeTime::clockLabel([PropKey::Text->value => 'x'], $at) === null, 'A Text without clock values has no clock label.');

$clockSeconds = 0;
NativeTime::useClock(static function () use (&$clockSeconds, $at): int {
    return $at + $clockSeconds;
});
try {
    // The initial label is computed: the placeholder is only measured when no clock is on.
    $text = static fn (Element $element): mixed => $element->properties()[PropKey::Text->value] ?? null;
    $assert(
        $text(Text::make('00:00')->clock($at - 42_300, ClockFormat::PaddedMinutesSeconds)->toElement()) === '00:42'
            && $text(Text::make('0:00')->clock($at - 7_200_000)->toElement()) === '2:00:00'
            && $text(Text::make('0')->countdown(30_000, $at)->toElement()) === '30'
            && $text(Text::make('0')->countdown(90_000, null, ClockFormat::MinutesSeconds)->toElement()) === '1:30'
            && $text(Text::make('0:00')->clock($at - 5_000, offsetMs: 125_000, running: false)->toElement()) === '2:05'
            && $text(Text::make('5')->countdownTo($at + 12_000)->toElement()) === '12'
            && $text(Text::make('x')->countdownTo($at - 1)->toElement()) === '0',
        'Text::clock()/countdown()/countdownTo() render the current label, not the placeholder.',
    );
    $assert(
        $text(Text::make('0:00')->property(PropKey::ClockStartedAt, $at - 61_000)->toElement()) === '1:01'
            && $text(Text::make('Live')->toElement()) === 'Live',
        'A clock set through property() is labelled too; other texts keep their content.',
    );
    $clockTree = TemplateRenderer::render(
        TemplateCompiler::compile(
            '<Column>'
            .'<Text :clockStartedAt="$startedAt" clockFormat="mm:ss">00:00</Text>'
            .'<Text :clockStartedAt="$missing" clockFormat="m:ss">--:--</Text>'
            .'<Text clockDurationMs="10000" :clockStartedAt="$startedAt" clockFormat="seconds">0</Text>'
            .'</Column>',
            'native-clock-label.pam.php',
        ),
        new class ($at) {
            public ?int $missing = null;

            public function __construct(public int $startedAt)
            {
            }
        },
        [],
    );
    [$tUp, $tOff, $tDown] = $clockTree->children();
    $assert(
        $text($tUp) === '00:00' && $text($tOff) === '--:--' && $text($tDown) === '10',
        'Templates render the clock label; a Text whose clock is off keeps its content.',
    );

    // Styled templates take the fast path, with pre-encoded properties.
    $compiled = TemplateCompiler::compile(
        '<Column class="bar"><Text class="time" :clockStartedAt="$startedAt" clockFormat="m:ss">0:00</Text></Column>',
        'native-clock-fast.pam.php',
    );
    $fast = new CompiledTemplateNode(
        kind: $compiled->kind,
        name: $compiled->name,
        attributes: [
            ...$compiled->attributes,
            '__pamStyles' => json_encode(
                ScopedStyleCompiler::compile('.bar { padding: 4px } .time { font-size: 14px }', 'native-clock-fast.pam'),
                JSON_THROW_ON_ERROR,
            ),
        ],
        source: $compiled->source,
        line: $compiled->line,
        column: $compiled->column,
        value: $compiled->value,
    );
    $fast->children = $compiled->children;
    $fastScope = new class ($at - 9_000) {
        public function __construct(public int $startedAt)
        {
        }
    };
    $labels = [];
    $consistent = true;
    RendererContract::use(RendererContract::fromJson('{"abi":1,"events":[79,1001],"properties":[543,1034],"protocol":[1,1]}'));
    foreach ([0, 600_000, 3_000_000, 3_600_000] as $clockSeconds) {
        $clockText = TemplateRenderer::render($fast, $fastScope, [])->children()[0];
        $labels[] = $text($clockText);
        $cached = $clockText->encodedProperties;
        $prebuiltClocks = ($prebuiltClocks ?? 0) + ($cached === null ? 0 : 1);
        $consistent = $consistent && ($cached === null || $cached[0] === $clockText->properties());
        $frame = (new TreeEncoder(false))->encode(Column::make($clockText))['frame'];
        $consistent = $consistent && is_string($frame) && str_contains($frame, (string) end($labels));
    }
    $assert(
        $labels === ['0:09', '10:09', '50:09', '1:00:09'] && $consistent && $prebuiltClocks > 0,
        'Fast-path template clocks render the label of each render and encode it.',
    );

    // The next instant the label changes length, in every format.
    $change = static fn (Text $text, int $now): ?int => NativeTime::clockLengthChangeAt($props($text), $now);
    $assert(
        $change(Text::make('')->clock($at), $at) === $at + 600_000
            && $change(Text::make('')->clock($at), $at + 600_000) === $at + 3_600_000
            && $change(Text::make('')->clock($at), $at + 3_600_000) === $at + 36_000_000
            && $change(Text::make('')->clock($at, ClockFormat::PaddedMinutesSeconds), $at) === $at + 6_000_000
            && $change(Text::make('')->clock($at, ClockFormat::PaddedMinutesSeconds), $at + 6_000_000) === $at + 60_000_000
            && $change(Text::make('')->clock($at, ClockFormat::HoursMinutesSeconds), $at) === $at + 36_000_000
            && $change(Text::make('')->clock($at, ClockFormat::Seconds), $at) === $at + 10_000
            && $change(Text::make('')->clock($at, ClockFormat::Seconds), $at + 10_000) === $at + 100_000,
        'Counting up, the label gains a character at 10:00, 1:00:00, 100:00, 10:00:00 and 10 s.',
    );
    $assert(
        $change(Text::make('')->clock($at, offsetMs: 590_000), $at) === $at + 10_000
            && $change(Text::make('')->clock($at + 5_000, ClockFormat::Seconds, 3_000), $at) === $at + 12_000,
        'The offset counts toward the change; a start in the future delays it.',
    );
    $assert(
        $change(Text::make('')->countdown(100_000, $at), $at) === $at + 1_000
            && $change(Text::make('')->countdown(100_000, $at), $at + 1_000) === $at + 91_000
            && $change(Text::make('')->countdown(100_000, $at), $at + 91_000) === null
            && $change(Text::make('')->countdown(3_600_000, $at, ClockFormat::MinutesSeconds), $at) === $at + 1_000
            && $change(Text::make('')->countdown(3_600_000, $at, ClockFormat::MinutesSeconds), $at + 1_000) === $at + 3_001_000
            && $change(Text::make('')->countdown(6_000_000, $at, ClockFormat::PaddedMinutesSeconds), $at) === $at + 1_000
            && $change(Text::make('')->countdown(36_000_000, $at, ClockFormat::HoursMinutesSeconds), $at) === $at + 1_000
            && $change(Text::make('')->countdown(36_000_000, $at, ClockFormat::HoursMinutesSeconds), $at + 1_000) === null
            && $change(Text::make('')->countdownTo($at + 10_500), $at) === $at + 1_500
            && $change(Text::make('')->countdown(20_000, $at + 2_000, offsetMs: 5_000), $at) === $at + 8_000,
        'Counting down, the label loses a character at 99, 59:59, 99:59, 9:59:59 and 9 s left.',
    );
    $assert(
        $change(Text::make('')->clock($at, offsetMs: 9_000, running: false), $at) === null
            && $change(Text::make('')->clock(null, offsetMs: 590_000), $at) === null
            && $change(Text::make('')->countdown(30_000, null), $at) === null
            && $change(Text::make('')->countdownTo($at - 1), $at) === null,
        'A paused, unstarted or finished clock never changes length.',
    );

    // Layout: counting up, the longer label is rendered (and measured) a
    // little before the native clock shows it; counting down, right after.
    $layout = static fn (Text $text, int $now): ?array => NativeTime::clockLayout($props($text), $now);
    $lead = NativeTime::CLOCK_GROWTH_LEAD_MS;
    $assert(
        $layout(Text::make('')->clock($at), $at + 590_000) === ['9:50', $at + 600_000 - $lead]
            && $layout(Text::make('')->clock($at), $at + 600_000 - $lead) === ['10:00', $at + 3_600_000 - $lead]
            && $layout(Text::make('')->countdown(100_000, $at), $at) === ['100', $at + 1_000]
            && $layout(Text::make('')->clock($at, running: false), $at + 900_000) === ['0:00', null],
        'clockLayout() leads growth by CLOCK_GROWTH_LEAD_MS and follows shrinking.',
    );

    // One timer per component renders it again at the next length change.
    $clockSeconds = 0;
    $fixture = new class extends Component {
        public ?int $startedAt = null;
        public bool $running = true;
        public int $offsetMs = 0;
        public bool $shown = true;
        public string $title = 'Call';

        public function render(): Renderable
        {
            return Column::make(
                Text::make($this->title),
                $this->shown
                    ? Text::make('00:00')->clock($this->startedAt, ClockFormat::PaddedMinutesSeconds, $this->offsetMs, $this->running)
                    : null,
                $this->shown ? Text::make('0')->countdown(30_000, $this->startedAt) : null,
            );
        }
    };
    $renderClock = static function () use ($fixture): Element {
        ComponentLifecycle::beginRender();
        $element = $fixture->toElement();
        ComponentLifecycle::finishRender();
        ComponentLifecycle::commit();

        return $element;
    };
    $renderClock();
    $assert($fixture->__pamClockRenderDue() === null, 'A clock that has not started arms nothing.');
    $fixture->startedAt = $at - 25_000;
    TestDiagnostics::$moduleCall = null;
    $rendered = $renderClock();
    $call = TestDiagnostics::$moduleCall;
    $assert(
        $text($rendered->children()[1]) === '00:25' && $text($rendered->children()[2]) === '5'
            && $fixture->__pamClockRenderDue() === $at + 6_000_000 - 25_000 - $lead
            && $call !== null && $call['module'] === 'timers' && $call['method'] === 'after',
        'A running clock arms one timer for its next length change (the countdown never shrinks below 5).',
    );
    $armedClock = $call['requestId'];
    $fixture->title = 'Call with Ana';
    TestDiagnostics::$moduleCall = null;
    $renderClock();
    $assert(
        TestDiagnostics::$moduleCall === null && $fixture->__pamClockRenderDue() === $at + 6_000_000 - 25_000 - $lead,
        'Rendering again for the same instant keeps the armed timer.',
    );
    $fixture->startedAt = $at - 15_000;
    $fixture->title = 'Call';
    $renderClock();
    $assert(
        $fixture->__pamClockRenderDue() === $at + 6_000
            && TestDiagnostics::$moduleCall['method'] === 'after' && TestDiagnostics::$moduleCall['requestId'] !== $armedClock,
        'A sooner change (the countdown reaching 9) re-arms the one timer.',
    );
    $armedClock = TestDiagnostics::$moduleCall['requestId'];
    $clockSeconds = 6_000;
    Runtime::dispatchModuleResult($armedClock, ModuleResultStatus::Success->value, '');
    $assert(
        DependencyTracker::isDirty($fixture) && $fixture->__pamClockRenderDue() === null,
        'The timer marks its component dirty when the label changes length.',
    );
    $rendered = $renderClock();
    $assert(
        $text($rendered->children()[2]) === '9' && $fixture->__pamClockRenderDue() === $at + 6_000_000 - 15_000 - $lead,
        'The render it causes measures the new label and arms the next change.',
    );

    ComponentLifecycle::appState(AppState::Background);
    $assert($fixture->__pamClockRenderSuspended(), 'In the background the timer waits (no PHP wake-up).');
    TestDiagnostics::$moduleCall = null;
    $clockSeconds = 6_000_000;
    ComponentLifecycle::appState(AppState::Active);
    $assert(
        !$fixture->__pamClockRenderSuspended() && TestDiagnostics::$moduleCall['method'] === 'after',
        'Back in the foreground the overdue render is armed at once.',
    );
    Runtime::dispatchModuleResult(TestDiagnostics::$moduleCall['requestId'], ModuleResultStatus::Success->value, '');
    $rendered = $renderClock();
    $assert($text($rendered->children()[1]) === '100:15', 'The render on return corrects the label.');

    $fixture->running = false;
    $fixture->offsetMs = 5_000_000;
    TestDiagnostics::$moduleCall = null;
    $renderClock();
    $assert(
        $fixture->__pamClockRenderDue() === null && TestDiagnostics::$moduleCall['method'] === 'cancel',
        'Pausing every clock cancels the timer; no timer while paused.',
    );
    TestDiagnostics::$moduleCall = null;
    $fixture->title = 'Paused';
    $renderClock();
    $assert(TestDiagnostics::$moduleCall === null, 'A paused clock arms nothing on later renders.');

    $fixture->running = true;
    $renderClock();
    $assert($fixture->__pamClockRenderDue() !== null, 'Resuming arms it again.');
    $fixture->shown = false;
    $renderClock();
    $assert($fixture->__pamClockRenderDue() === null, 'Removing the clock texts cancels the timer.');
    $fixture->shown = true;
    $renderClock();
    TestDiagnostics::$moduleCall = null;
    ComponentLifecycle::beginRender();
    ComponentLifecycle::finishRender();
    ComponentLifecycle::commit();
    $assert(
        $fixture->__pamClockRenderDue() === null && TestDiagnostics::$moduleCall['method'] === 'cancel',
        'Unmounting cancels the timer.',
    );
    ComponentLifecycle::shutdown();
} finally {
    NativeTime::useClock(null);
    RendererContract::use(null);
}
