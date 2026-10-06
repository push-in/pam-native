<?php

declare(strict_types=1);

use Pam\Native\App;
use Pam\Native\EventKind;
use Pam\Native\Internal\Runtime;

// Pressable gesture handlers (doubleTap, pressIn/out/move, longPress) are
// wrapped by the element to decode their payload; the wrapped template
// handler still marks only its own component, so one double tap re-renders
// one memoized row instead of the whole tree.
Runtime::shutdown();
$gestureDirectory = sys_get_temp_dir().'/pam-native-gesture-scope-'.getmypid();
if (!is_dir($gestureDirectory)) {
    mkdir($gestureDirectory, 0o755, true);
}
file_put_contents($gestureDirectory.'/GestureScopeRow.pam.php', <<<'PAM'
<?php

declare(strict_types=1);

namespace Pam\Native\Tests\GestureScope;

use Pam\Native\Component;

final class GestureScopeRow extends Component
{
    /** @var array<string, int> */
    public static array $renders = [];
    public int $likes = 0;

    public function __construct(public string $name = '')
    {
    }

    public function rendering(): void
    {
        self::$renders[$this->name] = (self::$renders[$this->name] ?? 0) + 1;
    }

    public function like(): void
    {
        $this->likes++;
    }
}
?>

<template>
    <Pressable
        :accessibilityLabel="$name"
        on:press="like"
        on:longPress="like"
        on:pressIn="like"
        on:pressOut="like"
        on:pressMove="like"
        on:doubleTap="like"
        on:gestureBegin="like"
        on:gestureUpdate="like"
        on:gestureEnd="like"
        on:gestureCancel="like"
        on:gestureSettle="like"
        p-touch-start="like"
        p-touch-move="like"
        p-touch-end="like"
    >
        <Text>{{ $name }} {{ $likes }}</Text>
    </Pressable>
</template>
PAM);
file_put_contents($gestureDirectory.'/GestureScopeFeed.pam.php', <<<'PAM'
<?php

declare(strict_types=1);

namespace Pam\Native\Tests\GestureScope;

use Pam\Native\Component;

final class GestureScopeFeed extends Component
{
    public static int $renders = 0;

    /** @var list<string> */
    public array $names = ['a', 'b', 'c'];

    public function rendering(): void
    {
        self::$renders++;
    }
}
?>

<template>
    <Column>
        <GestureScopeRow p-for="$name in $names" p-key="$name" :name="$name" />
    </Column>
</template>
PAM);
App::components($gestureDirectory, $gestureDirectory.'/.cache');
$gestureFeed = App::make('Pam\\Native\\Tests\\GestureScope\\GestureScopeFeed');
App::run($gestureFeed);
$gestureCallbacks = (new ReflectionProperty(Runtime::class, 'eventCallbacks'))->getValue();
$rowRenders = 'Pam\\Native\\Tests\\GestureScope\\GestureScopeRow';
foreach ([
    EventKind::Press,
    EventKind::LongPress,
    EventKind::PressIn,
    EventKind::PressOut,
    EventKind::PressMove,
    EventKind::DoubleTap,
    EventKind::GestureBegin,
    EventKind::GestureUpdate,
    EventKind::GestureEnd,
    EventKind::GestureCancel,
    EventKind::GestureSettle,
    EventKind::TouchStart,
    EventKind::TouchMove,
    EventKind::TouchEnd,
] as $gestureKind) {
    $gestureNode = null;
    foreach (array_keys($gestureCallbacks) as $gestureKey) {
        [$nodeId, $kind] = array_map(intval(...), explode(':', (string) $gestureKey));
        if ($kind === $gestureKind->value) {
            $gestureNode = $nodeId;
            break;
        }
    }
    $rowsBefore = $rowRenders::$renders;
    Runtime::dispatchEvent(
        (int) $gestureNode,
        $gestureKind->value,
        \Pam\Native\Internal\Wire::map(['x' => 1.0, 'y' => 1.0, 'pageX' => 1.0, 'pageY' => 1.0, 'timestamp' => 1, 'pointerId' => 0]),
    );
    $changedRows = array_keys(array_filter(
        $rowRenders::$renders,
        static fn (int $count, string $row): bool => $count !== ($rowsBefore[$row] ?? 0),
        ARRAY_FILTER_USE_BOTH,
    ));
    // Its ancestors re-render to reach it; its memoized siblings do not.
    $assert(
        $gestureNode !== null && count($changedRows) === 1,
        "A {$gestureKind->name} on one memoized row must re-render only that row (re-rendered: ".implode(',', $changedRows).').',
    );
}
Runtime::shutdown();
exec('rm -rf '.escapeshellarg($gestureDirectory));
