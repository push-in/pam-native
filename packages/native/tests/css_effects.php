<?php

declare(strict_types=1);

use Pam\Native\Internal\CompiledTemplateNode;
use Pam\Native\Internal\ScopedStyleCompiler;
use Pam\Native\Internal\TemplateCompiler;
use Pam\Native\Internal\TemplateRenderer;
use Pam\Native\PropKey;

/**
 * Visual effects: gradients, multiple/inset box shadows, filter and
 * backdrop-filter color matrices, gradient borders and <LinearGradient>.
 *
 * @var Closure(bool, string): void $assert
 * @var Closure(string): array<string, mixed> $css
 */
$wire = static function (array $output, string $key): string {
    $value = $output[$key] ?? null;
    if (!is_string($value)) {
        throw new RuntimeException("{$key} must compile to a wire string.");
    }

    return $value;
};
$layers = static function (array $output, string $key = 'backgroundGradient') use ($wire): array {
    $decoded = json_decode($wire($output, $key), true, 16, JSON_THROW_ON_ERROR);
    if (!is_array($decoded)) {
        throw new RuntimeException("{$key} must decode to a list.");
    }

    return $decoded;
};

// Linear gradients: default direction, sides, corners, angle units and stops.
$default = $css('background-image: linear-gradient(#FF0000, #0000FF);');
$assert(
    $layers($default) == [[
        't' => 1, 'r' => 0, 'm' => 1, 'a' => 180,
        's' => [[0xFFFF0000, 0, 0], [0xFF0000FF, 0, 0]],
    ]],
    'linear-gradient() must default to "to bottom" with auto stops.',
);
$assert($layers($css('background-image: linear-gradient(to right, red, blue);'))[0]['a'] === 90, 'to right must be 90deg.');
$assert($layers($css('background-image: linear-gradient(to left, red, blue);'))[0]['a'] === 270, 'to left must be 270deg.');
$assert($layers($css('background-image: linear-gradient(to top, red, blue);'))[0]['a'] === 0, 'to top must be 0deg.');
$corner = $layers($css('background-image: linear-gradient(to left top, red, blue);'))[0];
$assert($corner['m'] === 2 && $corner['x'] === -1 && $corner['y'] === -1, 'Corner directions must stay magic corners resolved with the box.');
$assert($layers($css('background-image: linear-gradient(0.25turn, red, blue);'))[0]['a'] == 90, 'turn angles must convert to degrees.');
$assert($layers($css('background-image: linear-gradient(-90deg, red, blue);'))[0]['a'] == 270, 'Negative angles must normalize.');
$assert(abs($layers($css('background-image: linear-gradient(3.14159265rad, red, blue);'))[0]['a'] - 180) < 0.001, 'rad angles must convert.');
$assert($layers($css('background-image: -webkit-linear-gradient(top, red, blue);'))[0]['a'] === 180, 'Legacy -webkit- side keywords use the origin side.');
$stops = $layers($css('background-image: linear-gradient(90deg, rgba(0,0,0,.6) 0%, transparent 35%, #00FF00 20px 40px, blue);'))[0]['s'];
$assert(
    $stops == [
        [0x99000000, 0, 1], [0x00000000, 0.35, 1],
        [0xFF00FF00, 20, 2], [0xFF00FF00, 40, 2], [0xFF0000FF, 0, 0],
    ],
    'Color stops must keep fractions, dp lengths, double positions, transparent and auto positions.',
);
$assert(
    $layers($css('background-image: repeating-linear-gradient(45deg, red 0 10px, blue 10px 20px);'))[0]['r'] === 1,
    'repeating-linear-gradient() must set the repeat flag.',
);

// Radial gradients.
$radial = $layers($css('background-image: radial-gradient(red, blue);'))[0];
$assert(
    $radial == [
        't' => 2, 'r' => 0, 'e' => 1, 'z' => 4, 'c' => [[0.5, 1], [0.5, 1]],
        's' => [[0xFFFF0000, 0, 0], [0xFF0000FF, 0, 0]],
    ],
    'radial-gradient() must default to a centered farthest-corner ellipse.',
);
$circle = $layers($css('background-image: radial-gradient(circle closest-side at 20% bottom, red, blue);'))[0];
$assert($circle['e'] === 0 && $circle['z'] === 1 && $circle["c"] == [[0.2, 1], [1, 1]], 'Circle shape, size keyword and position must compile.');
$explicit = $layers($css('background-image: radial-gradient(40px 50% at top, red, blue);'))[0];
$assert(
    $explicit['e'] === 1 && $explicit['z'] === 5 && $explicit["w"] == [40, 2] && $explicit["h"] == [0.5, 1]
        && $explicit["c"] == [[0.5, 1], [0, 1]],
    'Explicit ellipse radii and single keyword positions must compile.',
);
$assert($layers($css('background-image: radial-gradient(12px, red, blue);'))[0]['e'] === 0, 'A single radius makes a circle.');
$assert($layers($css('background-image: repeating-radial-gradient(circle, red 0 4px, blue 4px 8px);'))[0]['r'] === 1, 'repeating-radial-gradient() must compile.');

// Background shorthand and layering.
$stacked = $css('background: linear-gradient(red, blue), radial-gradient(white, black) no-repeat, #112233;');
$assert(
    $stacked['backgroundColor'] === 0xFF112233 && count($layers($stacked)) === 2 && $layers($stacked)[1]['t'] === 2,
    'background must keep gradient layers in paint order and the final color.',
);
$gradientOnly = $css('background: linear-gradient(red, blue);');
$assert($gradientOnly['backgroundColor'] === 0, 'A gradient background shorthand resets the color like CSS.');
$assert($css('background: #FF0000;') === ['backgroundColor' => 0xFFFF0000, 'backgroundGradient' => ''], 'A color background shorthand clears gradients.');
$assert($css('background-color: #FF0000;') === ['backgroundColor' => 0xFFFF0000], 'background-color keeps gradients.');
$assert($css('background-image: none;') === ['backgroundGradient' => ''], 'background-image: none clears gradients.');
$assert($css('background-repeat: no-repeat; background-size: cover; background-clip: border-box;') === [], 'Neutral background-* values must compile as no-ops.');

// Box shadows.
$multi = $css('box-shadow: 0 1px 2px rgba(0, 0, 0, .2), 0 8px 24px -4px #00000033, inset 0 0 0 1px #FFFFFF;');
$assert(
    $multi['shadowOffsetY'] === '1' && $multi['shadowBlurRadius'] === '2' && $multi['shadowColor'] === 0x33000000
        && json_decode($wire($multi, "boxShadows"), true) == [
            [0, 1, 2, 0, 0x33000000, 0],
            [0, 8, 24, -4, 0x33000000, 0],
            [0, 0, 0, 1, 0xFFFFFFFF, 1],
        ],
    'Multiple shadows must keep the first outer shadow in the legacy keys and the full list.',
);
$inset = $css('box-shadow: inset 2px 3px 4px red;');
$assert(
    $inset['shadowColor'] === 0 && json_decode($wire($inset, "boxShadows"), true) == [[2, 3, 4, 0, 0xFFFF0000, 1]],
    'An inset-only shadow must not paint an outer legacy shadow.',
);
$assert($css('box-shadow: none;')['boxShadows'] === '' && $css('box-shadow: none;')['shadowColor'] === 0, 'box-shadow: none must clear every shadow.');

// filter and backdrop-filter.
$gray = $css('filter: grayscale(100%);');
$assert(
    $gray['blurRadius'] === '0'
        && $gray['filterColorMatrix'] === '0.2126,0.7152,0.0722,0,0,0.2126,0.7152,0.0722,0,0,0.2126,0.7152,0.0722,0,0,0,0,0,1,0',
    'grayscale() must compile to the CSS luminance matrix.',
);
$assert(
    $css('filter: brightness(.5) opacity(50%);')['filterColorMatrix'] === '0.5,0,0,0,0,0,0.5,0,0,0,0,0,0.5,0,0,0,0,0,0.5,0',
    'Chained filter functions must compose into one matrix.',
);
$assert(
    $css('filter: contrast(2);')['filterColorMatrix'] === '2,0,0,0,-127.5,0,2,0,0,-127.5,0,0,2,0,-127.5,0,0,0,1,0',
    'contrast() must scale around mid-gray.',
);
$assert(
    $css('filter: invert(1);')['filterColorMatrix'] === '-1,0,0,0,255,0,-1,0,0,255,0,0,-1,0,255,0,0,0,1,0',
    'invert() must compile.',
);
$assert($css('filter: saturate(1);')['filterColorMatrix'] === '1,0,0,0,0,0,1,0,0,0,0,0,1,0,0,0,0,0,1,0', 'saturate(1) is the identity.');
$assert(str_starts_with($wire($css('filter: hue-rotate(90deg) sepia(.4);'), 'filterColorMatrix'), '0.'), 'hue-rotate() and sepia() must compile.');
$assert($css('filter: none;') === ['blurRadius' => '0', 'filterColorMatrix' => ''], 'filter: none must clear blur and color effects.');
$backdrop = $css('-webkit-backdrop-filter: blur(20px) saturate(180%);');
$assert(
    $backdrop['backdropBlurRadius'] === '20' && substr_count($wire($backdrop, 'backdropColorMatrix'), ',') === 19,
    'backdrop-filter must compile blur and a color matrix.',
);
$assert($css('backdrop-filter: none;') === ['backdropBlurRadius' => '0', 'backdropColorMatrix' => ''], 'backdrop-filter: none must compile.');

// Gradient borders (story rings).
$ring = $css('border: 3px solid transparent; border-radius: 50%; border-image: linear-gradient(45deg, #FFD23F, #2BB673) 1;');
$assert(
    $layers($ring, 'borderGradient')[0]['a'] == 45 && $ring['borderWidth'] === '3',
    'border-image gradients must compile to a gradient stroke.',
);
$assert($css('border-image-source: none;') === ['borderGradient' => ''], 'border-image-source: none must clear the stroke.');

// Diagnostics for what stays unsupported.
foreach ([
    'background: conic-gradient(red, blue);' => 'conic-gradient',
    'background-image: url(a.png);' => 'ImageBackground',
    'background-image: linear-gradient(red, 40%, blue);' => 'color hints',
    'filter: drop-shadow(0 0 2px red);' => 'drop-shadow',
    'background-size: 20px 20px;' => 'background-size',
    'border-image-slice: 30;' => 'initial value',
] as $declaration => $message) {
    $error = '';
    try {
        $css($declaration);
    } catch (RuntimeException $exception) {
        $error = $exception->getMessage();
    }
    $assert(str_contains($error, $message), "{$declaration} must fail with a clear diagnostic, got: {$error}");
}

// Golden IR: compiled classes reach the native property contract unchanged.
$effectsCss = ScopedStyleCompiler::compile(
    '.scrim { background: linear-gradient(to bottom, rgba(0, 0, 0, .55), transparent 70%); }'
        .' .card { border-radius: 16px; box-shadow: 0 2px 4px #0000001A, inset 0 1px 0 #FFFFFF; filter: grayscale(1) blur(2px); }'
        .' .glass { backdrop-filter: blur(12px); }',
    'CssEffectsRender.pam.php',
);
$effectsTemplate = TemplateCompiler::compile(
    '<Column><View class="scrim"/><View class="card"/><View class="glass"/>'
    .'<LinearGradient colors="#000000, transparent" start="0, 1" end="1, 0" locations="0, 0.55"/></Column>',
);
$effectsStyled = new CompiledTemplateNode(
    kind: $effectsTemplate->kind,
    name: $effectsTemplate->name,
    attributes: [...$effectsTemplate->attributes, '__pamStyles' => json_encode($effectsCss, JSON_THROW_ON_ERROR)],
    source: $effectsTemplate->source,
    line: $effectsTemplate->line,
    column: $effectsTemplate->column,
    value: $effectsTemplate->value,
);
$effectsStyled->children = $effectsTemplate->children;
[$scrim, $card, $glass, $component] = TemplateRenderer::render($effectsStyled, null, [])->children();
$assert(
    $scrim->properties() === [
        PropKey::BackgroundColor->value => 0,
        PropKey::BackgroundGradient->value => '[{"t":1,"r":0,"m":1,"a":180,"s":[[2348810240,0,0],[0,0.7,1]]}]',
    ],
    'Golden IR: gradient scrim.',
);
$assert(
    $card->properties()[PropKey::BoxShadows->value] === '[[0.0,2.0,4.0,0.0,436207616,0],[0.0,1.0,0.0,0.0,4294967295,1]]'
        && $card->properties()[PropKey::ShadowColor->value] === 0x1A000000
        && $card->properties()[PropKey::BlurRadius->value] == 2
        && $card->properties()[PropKey::FilterColorMatrix->value] === '0.2126,0.7152,0.0722,0,0,0.2126,0.7152,0.0722,0,0,0.2126,0.7152,0.0722,0,0,0,0,0,1,0',
    'Golden IR: multiple shadows and filters.',
);
$assert(
    $glass->properties() === [PropKey::BackdropBlurRadius->value => 12, PropKey::BackdropColorMatrix->value => ''],
    'Golden IR: backdrop blur.',
);
$assert(
    $component->properties() === [
        PropKey::BackgroundGradient->value => '[{"t":1,"r":0,"m":3,"p":[0.0,1.0,1.0,0.0],"s":[[4278190080,0.0,1],[0,0.55,1]]}]',
    ],
    'Golden IR: <LinearGradient> maps expo-linear-gradient props.',
);
