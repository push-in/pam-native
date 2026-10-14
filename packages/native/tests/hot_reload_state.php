<?php

declare(strict_types=1);

use Pam\Native\App;
use Pam\Native\Attributes\State;
use Pam\Native\Component;
use Pam\Native\Element;
use Pam\Native\Internal\Runtime;
use Pam\Native\UI\Column;
use Pam\Native\UI\Screen;
use Pam\Native\UI\Text;

Runtime::shutdown();

enum HotReloadTestFilter: int
{
    case All = 1;
    case Unread = 2;
}

final class HotReloadTestRow extends Component
{
    public static int $mounts = 0;

    #[State]
    public string $draft = '';

    public function __construct(public string $title = '')
    {
    }

    public static ?string $mountedWith = null;

    public function mount(): void
    {
        self::$mounts++;
        // Restored state is in place before mount() (Fast Refresh semantics).
        self::$mountedWith ??= $this->draft;
        if ($this->draft === '') {
            $this->draft = 'mounted';
        }
    }

    public function render(): Element
    {
        return Text::make("{$this->title}:{$this->draft}")->toElement();
    }
}

final class HotReloadTestBadge extends Component
{
    #[State]
    public int $count = 0;

    public function render(): Element
    {
        return Text::make("badge-{$this->count}")->toElement();
    }
}

final class HotReloadTestScreen extends Component
{
    #[State]
    public int $counter = 0;

    #[State]
    public HotReloadTestFilter $filter = HotReloadTestFilter::All;

    /** @var array<string, list<int>> */
    #[State]
    public array $selection = [];

    /** Objects are never carried over. */
    #[State]
    public ?stdClass $model = null;

    /** Not #[State]: plain fields restart with the new code. */
    public int $scroll = 0;

    public HotReloadTestRow $first;
    public HotReloadTestRow $second;
    public HotReloadTestBadge $badge;

    public function __construct(public string $heading)
    {
        $this->first = new HotReloadTestRow('first');
        $this->second = new HotReloadTestRow('second');
        $this->badge = new HotReloadTestBadge();
    }

    public function render(): Element
    {
        return Screen::make(Column::make(
            Text::make("{$this->heading}-{$this->counter}-{$this->filter->name}"),
            $this->first,
            $this->second,
            $this->badge,
        ))->toElement();
    }
}

$hotReloadStateDirectory = sys_get_temp_dir().'/pam-hot-reload-state-'.getmypid().'-'.hrtime(true);
mkdir($hotReloadStateDirectory, 0o700, true);
$previousStateDirectory = getenv('PAM_NATIVE_STATE_DIR');
putenv('PAM_NATIVE_STATE_DIR='.$hotReloadStateDirectory);
$snapshotFile = $hotReloadStateDirectory.'/hot-reload-components.json';

try {
    $before = new HotReloadTestScreen('Conversas');
    App::run($before);
    $before->counter = 7;
    $before->filter = HotReloadTestFilter::Unread;
    $before->selection = ['10' => [1, 2], 'pinned' => []];
    $before->model = new stdClass();
    $before->scroll = 480;
    $before->first->draft = 'hello';
    $before->second->draft = 'world';
    $before->badge->count = 3;
    Runtime::requestRender();
    $assert(str_contains((string) Runtime::lastFrame(), 'Conversas-7-Unread'), 'Hot reload state fixture must render its edited state.');

    Runtime::checkpointHotReload();
    $assert(is_file($snapshotFile), 'A hot reload checkpoint must record mounted component state.');
    Runtime::shutdown();

    // The "new code": a different heading prop (the edit) and fresh instances.
    HotReloadTestRow::$mounts = 0;
    HotReloadTestRow::$mountedWith = null;
    $after = new HotReloadTestScreen('Chats');
    App::run($after);
    $frame = (string) Runtime::lastFrame();
    $assert(!is_file($snapshotFile), 'The next request must consume the hot reload snapshot.');
    $assert(
        $after->counter === 7 && $after->filter === HotReloadTestFilter::Unread,
        'Hot reload must restore scalar and enum #[State] of a component at the same tree position.',
    );
    $assert($after->selection === ['10' => [1, 2], 'pinned' => []], 'Hot reload must restore array state with its keys.');
    $assert($after->model === null && $after->scroll === 0, 'Hot reload must not restore objects or non-#[State] fields.');
    $assert($after->heading === 'Chats' && str_contains($frame, 'Chats-7-Unread'), 'Hot reload must render the edited props with the preserved state.');
    $assert(
        HotReloadTestRow::$mounts === 2 && $after->first->draft === 'hello' && $after->second->draft === 'world',
        'Same-class siblings must restore their own state by index.',
    );
    $assert(HotReloadTestRow::$mountedWith === 'hello', 'mount() must run on the preserved state.');
    $assert($after->badge->count === 3, 'Hot reload must restore nested component state.');

    // A cold start (no checkpoint) never restores development state.
    Runtime::shutdown();
    $cold = new HotReloadTestScreen('Conversas');
    App::run($cold);
    $assert($cold->counter === 0 && $cold->first->draft === 'mounted', 'Without a checkpoint components must start fresh.');

    // A component whose class changed at that position starts fresh.
    $cold->counter = 5;
    Runtime::requestRender();
    Runtime::checkpointHotReload();
    Runtime::shutdown();
    $snapshot = json_decode((string) file_get_contents($snapshotFile), true, 64, JSON_THROW_ON_ERROR);
    $rootKey = array_key_first($snapshot['components']);
    $snapshot['components'][$rootKey][0] = 'App\\Removed\\Screen';
    file_put_contents($snapshotFile, json_encode($snapshot, JSON_THROW_ON_ERROR));
    $renamed = new HotReloadTestScreen('Conversas');
    App::run($renamed);
    $assert($renamed->counter === 0, 'State recorded for another class must not be restored.');
    Runtime::shutdown();
} finally {
    @unlink($snapshotFile);
    @rmdir($hotReloadStateDirectory);
    putenv($previousStateDirectory === false ? 'PAM_NATIVE_STATE_DIR' : 'PAM_NATIVE_STATE_DIR='.$previousStateDirectory);
}
