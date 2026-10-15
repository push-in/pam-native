<?php

declare(strict_types=1);

/*
 * The native tree encoder (android/app/src/main/cpp/pam_tree_encoder.cpp,
 * registered by the Android and iOS bridges) must write exactly the bytes
 * the PHP TreeEncoder writes: the same full frames, the same patches in the
 * same operation order, the same callbacks and the same errors. Run through
 * scripts/native-encoder-test.sh, which loads the encoder as a host
 * extension; skipped when the functions are not available.
 */

use Pam\Native\Element;
use Pam\Native\Internal\BinaryValue;
use Pam\Native\Internal\CompiledTemplateNode;
use Pam\Native\Internal\ScopedStyleCompiler;
use Pam\Native\Internal\TemplateCompiler;
use Pam\Native\Internal\TemplateRenderer;
use Pam\Native\Internal\TreeEncoder;
use Pam\Native\PropKey;
use Pam\Native\Style;
use Pam\Native\UI\Column;
use Pam\Native\UI\Image;
use Pam\Native\UI\Pressable;
use Pam\Native\UI\Row;
use Pam\Native\UI\ScrollView;
use Pam\Native\UI\Text;
use Pam\Native\UI\View;
use Pam\Native\UI\VirtualizedList;

spl_autoload_register(static function (string $class): void {
    $prefix = 'Pam\\Native\\';
    if (str_starts_with($class, $prefix)) {
        $path = __DIR__.'/../src/'.str_replace('\\', '/', substr($class, strlen($prefix))).'.php';
        if (is_file($path)) {
            require $path;
        }
    }
});

if (!function_exists('pam_native_encoder_create')) {
    echo "NATIVE_ENCODER_EQUIVALENCE skipped (no native encoder)\n";

    return;
}

$failures = 0;
$checks = 0;
$fail = static function (string $message) use (&$failures): void {
    $failures++;
    fwrite(STDERR, "FAIL {$message}\n");
};

/** @param list<Element> $frames */
$compare = static function (string $name, array $frames) use (&$checks, $fail): array {
    $php = new TreeEncoder(false);
    $native = new TreeEncoder(true);
    $timing = ['php' => 0, 'native' => 0];
    foreach ($frames as $index => $root) {
        $phpError = $nativeError = null;
        $a = $b = null;
        $started = hrtime(true);
        try {
            $a = $php->encode($root);
        } catch (Throwable $error) {
            $phpError = $error::class.': '.$error->getMessage();
        }
        $timing['php'] += hrtime(true) - $started;
        $started = hrtime(true);
        try {
            $b = $native->encode($root);
        } catch (Throwable $error) {
            $nativeError = $error::class.': '.$error->getMessage();
        }
        $timing['native'] += hrtime(true) - $started;
        $checks++;
        if ($phpError !== null || $nativeError !== null) {
            if ($phpError !== $nativeError) {
                $fail("{$name}#{$index}: error PHP=[{$phpError}] native=[{$nativeError}]");
            }
            // A failed frame leaves both encoders without a valid commit.
            $php->forceFullFrame();
            $native->forceFullFrame();
            continue;
        }
        if ($a['frame'] !== $b['frame']) {
            $fail(sprintf(
                '%s#%d: frame differs (PHP %s bytes, native %s bytes, full %s/%s)',
                $name,
                $index,
                $a['frame'] === null ? 'null' : strlen($a['frame']),
                $b['frame'] === null ? 'null' : strlen($b['frame']),
                var_export($a['full'], true),
                var_export($b['full'], true),
            ));
        }
        if ($a['full'] !== $b['full']) {
            $fail("{$name}#{$index}: full flag differs");
        }
        $ka = array_keys($a['callbacks']);
        $kb = array_keys($b['callbacks']);
        sort($ka);
        sort($kb);
        if ($ka !== $kb) {
            $fail("{$name}#{$index}: callback keys differ");
        } else {
            foreach ($a['callbacks'] as $key => $callback) {
                if ($b['callbacks'][$key] !== $callback) {
                    $fail("{$name}#{$index}: callback {$key} differs");
                    break;
                }
            }
        }
        $na = array_map(static fn ($n) => [$n->id, $n->parent, $n->index, $n->kind, $n->properties], $php->frameNodes());
        $nb = array_map(static fn ($n) => [$n->id, $n->parent, $n->index, $n->kind, $n->properties], $native->frameNodes());
        if ($na !== $nb) {
            $fail("{$name}#{$index}: frameNodes differ");
        }
    }

    return $timing;
};

// --- Deterministic fixtures -------------------------------------------------

/** Sets a raw property (Element::withProperty is protected). */
$with = static fn (Element $element, PropKey $key, mixed $value): Element
    => (fn () => $this->withProperty($key, $value))->call($element);

$lcg = static function (int &$state): int {
    $state = ($state * 1103515245 + 12345) % 2147483648;

    return $state;
};

$row = static function (int $i, int $liked = -1): Element {
    return match ($i % 3) {
        0 => Column::make(
            Text::make("Item {$i}")->style(new Style(fontSize: 16, fontWeight: 700)),
            Text::make("Subtitle for item {$i} ✓ ação")->style(new Style(fontSize: 13)),
        )->style(new Style(height: 64, paddingHorizontal: 16, backgroundColor: $i === $liked ? 0xFFFFE4E6 : 0xFFFFFFFF))->key((string) $i),
        1 => Row::make(
            View::make(Text::make('I'.($i % 10)))->style(new Style(width: 48, height: 48, borderRadius: 24.5)),
            Column::make(Text::make("Item {$i}"), Text::make("Subtitle {$i}")),
            Text::make((string) ($i % 100)),
        )->style(new Style(height: 72, opacity: 0.75))->key((string) $i),
        default => Pressable::make(Text::make("Card {$i}"))
            ->onPress(static fn () => null)
            ->accessibilityLabel("card-{$i}")
            ->key((string) $i),
    };
};

$listFrames = [];
$rows = [];
for ($i = 0; $i < 3000; $i++) {
    $rows[] = $row($i);
}
$listFrames[] = VirtualizedList::make(...$rows)->toElement();
$listFrames[] = VirtualizedList::make(...array_map(static fn ($i) => $row($i, 4), range(0, 2999)))->toElement();
$listFrames[] = VirtualizedList::make(...array_reverse(array_slice($rows, 0, 2000)))->toElement();
$listFrames[] = VirtualizedList::make(...array_slice($rows, 500, 1500), ...array_slice($rows, 0, 10))->toElement();
$listFrames[] = $listFrames[3];
$timing = $compare('list', $listFrames);

// Random trees: keyed/unkeyed children, DOM ids, holes, events, mixed value
// types, nested scroll views with refresh props, moved and dropped subtrees.
$state = 42;
$pool = [];
$random = static function (int $depth) use (&$random, &$state, $lcg, &$pool, $with): Element {
    $pick = $lcg($state) % 9;
    if ($depth <= 0 || $pick < 3) {
        $text = Text::make('t'.($lcg($state) % 50).($pick === 0 ? ' — ñ 日本' : ''));
        if ($pick === 1) {
            $text = $text->style(new Style(fontSize: 12 + $lcg($state) % 6, textColor: 0xFF000000 | ($lcg($state) % 0xFFFFFF)));
        }

        return $text;
    }
    $children = [];
    $count = $lcg($state) % 6;
    for ($c = 0; $c < $count; $c++) {
        if ($pool !== [] && $lcg($state) % 7 === 0) {
            $children[] = $pool[$lcg($state) % count($pool)];
            continue;
        }
        $child = $random($depth - 1);
        $mode = $lcg($state) % 5;
        if ($mode === 0) {
            $child = $child->key('k'.($lcg($state) % 12));
        } elseif ($mode === 1) {
            $child = $child->id('d'.($lcg($state) % 30));
        }
        $children[] = $lcg($state) % 11 === 0 ? null : $child;
    }
    $element = match ($pick) {
        3 => Column::make(...$children),
        4 => Row::make(...$children)->style(new Style(gap: ($lcg($state) % 40) / 4)),
        5 => Pressable::make(Column::make(...$children))->onPress(static fn () => null),
        6 => ScrollView::make(Column::make(...$children))->refreshing($lcg($state) % 2 === 0)->onRefresh(static fn () => null),
        7 => $with(
            Image::make('https://example.com/'.($lcg($state) % 9).'.png'),
            PropKey::Value,
            new BinaryValue(str_repeat("\x00\xFF", $lcg($state) % 4)),
        ),
        default => $with(Column::make(...$children), PropKey::Opacity, ($lcg($state) % 100) / 100),
    };
    if ($lcg($state) % 5 === 0) {
        $pool[] = $element;
        if (count($pool) > 24) {
            array_shift($pool);
        }
    }

    return $element;
};
$fuzzFrames = [];
for ($f = 0; $f < 400; $f++) {
    $fuzzFrames[] = Column::make(...array_map(static fn () => $random(4), range(0, 4)))->toElement();
}
$fuzzTiming = $compare('fuzz', $fuzzFrames);

// Errors: identity collisions and invalid values fail identically.
$compare('errors', [
    Column::make(Text::make('a')->key('same'), Text::make('b')->key('same'))->toElement(),
    Column::make(Text::make('a')->id('dup'), Row::make(Text::make('b')->id('dup')))->toElement(),
    Column::make(Text::make("bad \xC3\x28 utf8"))->toElement(),
    Column::make($with(View::make(), PropKey::Opacity, INF))->toElement(),
    Column::make(Text::make('ok'))->toElement(),
]);

// Template-rendered elements carry pre-encoded properties.
$templateFrames = [];
$template = TemplateCompiler::compile(
    '<Column class="card"><Text class="title">Hello</Text><Text style="font-size: 14px; color: #333">World</Text></Column>',
);
$styled = new CompiledTemplateNode(
    kind: $template->kind,
    name: $template->name,
    attributes: [
        ...$template->attributes,
        '__pamStyles' => json_encode(
            ScopedStyleCompiler::compile('.card { padding: 12px; background-color: #fafafa } .title { font-size: 20px; font-weight: 700 }', 'equivalence.pam'),
            JSON_THROW_ON_ERROR,
        ),
    ],
    source: $template->source,
    line: $template->line,
    column: $template->column,
    value: $template->value,
);
$styled->children = $template->children;
$prebuilt = 0;
for ($i = 0; $i < 6; $i++) {
    $element = TemplateRenderer::render($styled, null, []);
    $walk = static function (Element $element) use (&$walk, &$prebuilt): void {
        if ($element->encodedProperties !== null) {
            $prebuilt++;
        }
        foreach ($element->children as $child) {
            $walk($child);
        }
    };
    $walk($element);
    $templateFrames[] = Column::make($element, Text::make("frame {$i}"))->toElement();
}
$compare('template', $templateFrames);

printf(
    "NATIVE_ENCODER_EQUIVALENCE %s checks=%d failures=%d prebuilt=%d list: php=%.1fms native=%.1fms (%.1fx) fuzz: php=%.1fms native=%.1fms\n",
    $failures === 0 ? 'OK' : 'FAILED',
    $checks,
    $failures,
    $prebuilt,
    $timing['php'] / 1e6,
    $timing['native'] / 1e6,
    $timing['php'] / max(1, $timing['native']),
    $fuzzTiming['php'] / 1e6,
    $fuzzTiming['native'] / 1e6,
);
exit($failures === 0 ? 0 : 1);
