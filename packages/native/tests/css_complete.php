<?php

declare(strict_types=1);

use Pam\Native\Internal\CompiledTemplateNode;
use Pam\Native\Internal\CssColor;
use Pam\Native\Internal\ScopedStyleCompiler;
use Pam\Native\Internal\StyleQueryCompiler;
use Pam\Native\Internal\StyleSelectorCompiler;
use Pam\Native\Internal\TemplateCompiler;
use Pam\Native\Internal\TemplateRenderer;
use Pam\Native\PropKey;

/**
 * Complete CSS-to-native contract: flexbox, box model, borders, typography,
 * transforms, units, colors, selectors and at-rules. See docs/css.md.
 *
 * @var Closure(bool, string): void $assert
 */
$css = static fn (string $declarations): array => ScopedStyleCompiler::compileDeclarations(
    $declarations,
    [],
    'CssComplete.pam.php',
);

// Flexbox: flex shorthand and flex-basis (px / % / auto / content).
$assert($css('flex: 1 1 0;') === ['flexGrow' => '1', 'flexShrink' => '1', 'flexBasis' => '0'], 'flex: 1 1 0 must compile to grow/shrink/basis.');
$assert($css('flex: 1;') === ['flexGrow' => '1', 'flexShrink' => '1', 'flexBasis' => '0'], 'flex: <n> must imply a zero basis.');
$assert($css('flex: 2 0 120px;') === ['flexGrow' => '2', 'flexShrink' => '0', 'flexBasis' => '120'], 'flex with a length basis must compile.');
$assert($css('flex: 1 1 30%;') === ['flexGrow' => '1', 'flexShrink' => '1', 'flexBasisPercent' => '30'], 'flex with a percentage basis must compile.');
$assert($css('flex: auto;') === ['flexGrow' => '1', 'flexShrink' => '1', 'flexBasisContent' => true], 'flex: auto must use the content basis.');
$assert($css('flex: none;') === ['flexGrow' => '0', 'flexShrink' => '0', 'flexBasisContent' => true], 'flex: none must be rigid.');
$assert($css('flex: 0;')['flexBasisContent'] === true, 'flex: 0 keeps the content size natively.');
$assert($css('flex: 80px;') === ['flexGrow' => '1', 'flexShrink' => '1', 'flexBasis' => '80'], 'flex: <basis> must grow and shrink.');
$assert($css('flex-basis: 25%;') === ['flexBasisPercent' => '25'], 'flex-basis percentages must compile.');
$assert($css('flex-basis: 64px;') === ['flexBasis' => '64'], 'flex-basis lengths must compile.');
$assert($css('flex-basis: content;') === ['flexBasisContent' => true], 'flex-basis: content must compile.');
$assert($css('flex-basis: auto;') === ['flexBasisContent' => true], 'flex-basis: auto must compile.');
$assert($css('flex-flow: row wrap-reverse;') === ['flexDirection' => 'row', 'flexWrap' => 'wrap-reverse'], 'flex-flow must expand.');
$assert($css('order: 2;') === ['order' => 2], 'order must compile to an integer.');
$assert($css('align-content: space-between;') === ['alignContent' => 'space-between'], 'align-content must compile.');
$assert($css('gap: 8px 12px;') === ['gridRowGap' => '8', 'gridColumnGap' => '12'], 'gap with two values must set row and column gaps.');
$assert(
    $css('margin: 0 auto;') === [
        'marginTop' => '0', 'marginRight' => '0', 'marginRightAuto' => true,
        'marginBottom' => '0', 'marginLeft' => '0', 'marginLeftAuto' => true,
    ],
    'margin: auto must compile to native auto margins on each side.',
);
$assert($css('margin-top: auto;') === ['marginTop' => '0', 'marginTopAuto' => true], 'margin-top: auto must compile.');

// Box model.
$assert($css('min-width: 30%; min-height: 10%;') === ['minWidthPercent' => '30', 'minHeightPercent' => '10'], 'min-* percentages must compile.');
$assert($css('width: auto; max-width: none;') === [], 'auto/none sizes must leave the native default.');
$assert($css('position: fixed; inset: 0 auto auto 10%;') === ['position' => 'fixed', 'top' => '0', 'leftPercent' => '10'], 'fixed position and inset auto must compile.');
$assert($css('position: static;') === ['position' => 'relative'], 'static position is the native flow position.');
$assert($css('display: block;') === ['visible' => true], 'display: block must render as a flex column.');
$assert($css('overflow-x: hidden;') === ['overflow' => 'hidden'], 'overflow-x must compile.');
$assert($css('aspect-ratio: 4 / 3;')['aspectRatio'] === (string) (4 / 3), 'aspect-ratio ratios must compile.');
$assert($css('z-index: auto;') === [], 'z-index: auto must keep the default stacking.');

// Borders.
$assert(
    $css('border: dashed 2px #FF0000;') === [
        'borderWidth' => '2', 'borderColor' => 0xFFFF0000, 'borderTopColor' => 0xFFFF0000,
        'borderRightColor' => 0xFFFF0000, 'borderBottomColor' => 0xFFFF0000,
        'borderLeftColor' => 0xFFFF0000, 'borderStyle' => 2,
    ],
    'border shorthand must accept any token order and patterned styles.',
);
$sides = $css('border-color: #FF0000 #00FF00 #0000FF #FFFFFF;');
$assert(
    $sides['borderTopColor'] === 0xFFFF0000 && $sides['borderRightColor'] === 0xFF00FF00
        && $sides['borderBottomColor'] === 0xFF0000FF && $sides['borderLeftColor'] === 0xFFFFFFFF,
    'border-color with four values must compile per side.',
);
$bottom = $css('border-bottom: 1px solid #123456;');
$assert(
    $bottom === ['borderBottomWidth' => '1', 'borderBottomColor' => 0xFF123456, 'borderColor' => 0xFF123456],
    'border-<side> must set that side color and keep the legacy single color.',
);
$assert($css('border-width: 1px 2px;') === ['borderTopWidth' => '1', 'borderRightWidth' => '2', 'borderBottomWidth' => '1', 'borderLeftWidth' => '2'], 'border-width with multiple values must compile per side.');
$assert($css('border-width: thin;') === ['borderWidth' => '1'], 'border-width keywords must compile.');
$assert($css('border-radius: 50%;') === ['borderRadius' => '9999'], 'border-radius: 50% must produce a circle/pill.');
$assert($css('border-top: none;') === ['borderTopWidth' => '0'], 'border-<side>: none must clear that side.');

// Paint.
$assert(
    $css('text-shadow: 0 1px 2px rgba(0, 0, 0, .5);') === [
        'textShadowOffsetX' => '0', 'textShadowOffsetY' => '1', 'textShadowRadius' => '2', 'textShadowColor' => 0x80000000,
    ],
    'text-shadow must compile to the native text shadow.',
);
$assert($css('filter: blur(4px);') === ['blurRadius' => '4', 'filterColorMatrix' => ''], 'filter: blur() must compile.');
require __DIR__.'/css_effects.php';
$assert($css('pointer-events: none;') === ['pointerEvents' => 'none'], 'pointer-events must compile.');
$assert($css('cursor: pointer; -webkit-tap-highlight-color: transparent; will-change: transform;') === [], 'Browser-only properties are documented no-ops.');
$assert($css('user-select: none;') === ['selectable' => false], 'user-select must map to text selection.');
$assert($css('caret-color: #00FF00;') === ['cursorColor' => 0xFF00FF00], 'caret-color must map to the input cursor.');

// Typography.
$assert($css('font-size: 20px; line-height: 1.5;') === ['fontSize' => '20', 'lineHeight' => '30'], 'Unitless line-height must multiply the font size.');
$assert($css('line-height: 1.25;') === ['lineHeightMultiplier' => '1.25'], 'Unitless line-height without a font size resolves at render time.');
$assert($css('line-height: 120%; font-size: 10px;')['lineHeightMultiplier'] === '1.2', 'Percentage line-height must become a multiplier.');
$assert(
    $css('font: italic 600 16px/24px "Space Grotesk", sans-serif;') === [
        'fontStyle' => 'italic', 'fontWeight' => '600', 'fontSize' => '16', 'lineHeight' => '24', 'fontFamily' => 'Space Grotesk',
    ],
    'font shorthand must expand.',
);
$assert($css('font-variant-numeric: tabular-nums;') === ['fontFeatureSettings' => "'tnum' 1"], 'tabular-nums must compile to an OpenType feature.');
$assert($css('font-feature-settings: "liga" off, "ss01";') === ['fontFeatureSettings' => "'liga' 0, 'ss01' 1"], 'font-feature-settings must compile.');
$assert($css('font-weight: lighter;') === ['fontWeight' => '300'], 'font-weight keywords must compile.');
$assert($css('letter-spacing: normal;') === ['letterSpacing' => '0'], 'letter-spacing: normal must compile.');
$assert($css('text-align: justify;') === ['textAlign' => 'justify'], 'text-align: justify must compile.');
$assert($css('text-decoration: underline line-through;') === ['textDecoration' => 'underline-line-through'], 'Combined text decorations must compile.');
$assert($css('text-overflow: ellipsis; white-space: nowrap;') === ['ellipsizeMode' => 'tail', 'numberOfLines' => 1], 'Single-line ellipsis must compile.');
$assert($css('-webkit-line-clamp: 3; -webkit-box-orient: vertical;') === ['numberOfLines' => 3, 'ellipsizeMode' => 'tail'], 'line clamp must compile.');
$assert($css('-webkit-line-clamp: 2;') === ['numberOfLines' => 2, 'ellipsizeMode' => 'tail'], '-webkit-line-clamp must set lines and ellipsis.');
$assert($css('hyphens: auto;') === ['androidHyphenationFrequency' => 'full'], 'hyphens must compile.');
$assert($css('direction: rtl;') === ['layoutDirection' => 'rtl'], 'direction must compile.');

// Transforms.
$assert(
    $css('transform: translate(10px, -50%) scale(0.9, 1.1);') === [
        'translationX' => '10', 'translationY' => '0', 'translationYPercent' => '-50', 'scaleX' => '0.9', 'scaleY' => '1.1',
    ],
    'translate()/scale() with two arguments and percentages must compile.',
);
$assert($css('transform-origin: top left;') === ['transformOriginX' => '0', 'transformOriginY' => '0'], 'transform-origin keywords must compile.');
$assert($css('transform-origin: 25% 75%;') === ['transformOriginX' => '25', 'transformOriginY' => '75'], 'transform-origin percentages must compile.');
$matrix = $css('transform: matrix(0, 1, -1, 0, 10, 20);');
$assert($matrix['rotation'] === '90' && $matrix['translationX'] === '10' && $matrix['scaleX'] === '1', 'Non-skewing matrix() must decompose.');
$assert($css('rotate: 45deg;') === ['rotation' => '45'], 'The individual rotate property must compile.');

// Transitions run as native property animations.
$assert($css('transition: opacity 200ms ease-out;') === ['animate' => true, 'animationDuration' => 200, 'animationEasing' => 3], 'transition shorthand must compile.');
$assert($css('transition: none;') === ['animate' => false], 'transition: none must disable implicit animation.');

// Units and functions.
$assert(str_starts_with($css('padding-top: 2em;')['paddingTop'], '@pam-style:'), 'em lengths must compile to a native expression.');
$assert(str_starts_with($css('height: 100dvh;')['height'], '@pam-style:'), 'Dynamic viewport units must compile.');
$assert(str_starts_with($css('padding-bottom: env(safe-area-inset-bottom, 12px);')['paddingBottom'], '@pam-style:'), 'env() fallbacks must compile.');

// Duplicate declarations are CSS fallbacks; !important wins.
$assert($css('color: red; color: #00FF00;') === ['textColor' => 0xFF00FF00], 'The last duplicate declaration must win.');
$assert($css('color: red !important; color: #00FF00;') === ['textColor' => 0xFFFF0000], 'An earlier !important declaration must win.');
$assert($css('--gap: 6px; gap: var(--gap);') === ['gap' => '6'], 'Rule-scoped custom properties must resolve.');

// Colors.
$assert(CssColor::parse('hwb(0 0% 0%)') === 0xFFFF0000, 'hwb() must compile.');
$near = static function (int $actual, int $expected): bool {
    foreach ([24, 16, 8, 0] as $shift) {
        if (abs((($actual >> $shift) & 0xFF) - (($expected >> $shift) & 0xFF)) > 1) {
            return false;
        }
    }
    return true;
};
$assert($near(CssColor::parse('oklch(0.627955 0.257683 29.2339)'), 0xFFFF0000), 'oklch() must compile.');
$assert($near(CssColor::parse('oklab(1 0 0)'), 0xFFFFFFFF), 'oklab() must compile.');
$assert($near(CssColor::parse('lab(100% 0 0)'), 0xFFFFFFFF), 'lab() must compile.');
$assert(CssColor::parse('lch(0% 0 0)') === 0xFF000000, 'lch() must compile.');
$assert(CssColor::parse('color-mix(in srgb, #FF0000, #0000FF)') === 0xFF800080, 'color-mix() in srgb must compile.');
$assert(CssColor::parse('color-mix(in srgb, #000000 25%, transparent)') === 0x40000000, 'color-mix() must interpolate alpha.');
$assert(CssColor::parse('color(srgb 1 0 0 / 50%)') === 0x80FF0000, 'color(srgb) must compile.');
$assert(CssColor::parse('hsl(120 100 25)') === 0xFF008000, 'Unitless modern hsl() must compile.');

// Selectors.
$assert(StyleSelectorCompiler::expand('.a:is(.b, .c) Text, .d') === ['.a.b Text', '.a.c Text', '.d'], ':is() must expand into a selector list.');
$negated = StyleSelectorCompiler::compile('.item:not(.active)', 'CssComplete.pam.php');
$assert(($negated['compounds'][0]['nots'][0]['classes'] ?? null) === ['active'], ':not() must compile to a negated compound.');
foreach (['.a + .b', '.a ~ .b', '.a:first-child', '.a:nth-child(2n+1)', '.a::before'] as $unsupportedSelector) {
    $rejected = false;
    try {
        StyleSelectorCompiler::compile($unsupportedSelector, 'CssComplete.pam.php');
    } catch (RuntimeException) {
        $rejected = true;
    }
    $assert($rejected, "Unsupported selector {$unsupportedSelector} must fail at compile time.");
}

// Queries.
$range = StyleQueryCompiler::compile('screen and (min-width: 600px) and (max-width: 900px)', 'CssComplete.pam.php');
$assert(
    StyleQueryCompiler::matches($range, ['width' => 700.0])
        && !StyleQueryCompiler::matches($range, ['width' => 1000.0]),
    '@media and-lists must compile and evaluate.',
);
$between = StyleQueryCompiler::compile('(400px <= width <= 700px)', 'CssComplete.pam.php');
$assert(StyleQueryCompiler::matches($between, ['width' => 500.0]) && !StyleQueryCompiler::matches($between, ['width' => 800.0]), 'Range media syntax must compile.');
$either = StyleQueryCompiler::compile('(prefers-color-scheme: dark), (prefers-reduced-motion: reduce)', 'CssComplete.pam.php');
$assert(
    StyleQueryCompiler::matches($either, ['colorScheme' => 'light', 'reducedMotion' => 'reduce'])
        && !StyleQueryCompiler::matches($either, ['colorScheme' => 'light', 'reducedMotion' => 'no-preference']),
    '@media lists must evaluate as or.',
);
$negatedQuery = StyleQueryCompiler::compile('not (min-width: 40em)', 'CssComplete.pam.php');
$assert(StyleQueryCompiler::matches($negatedQuery, ['width' => 600.0]) && !StyleQueryCompiler::matches($negatedQuery, ['width' => 700.0]), 'not and em media queries must compile.');

// Sheets: variables inside @media, @supports, ::placeholder, states lists.
$sheet = ScopedStyleCompiler::compile(
    <<<'CSS'
    :root { --brand: #1B7A4E; }
    .field::placeholder { color: var(--brand); }
    .a:pressed, .b:pressed { opacity: 0.5; }
    @supports (display: grid) { .grid { gap: 4px; } }
    @supports (backdrop-filter: url(#x)) { .glass { opacity: 0.1; } }
    @media (prefers-color-scheme: dark) { .card { background-color: var(--brand); } }
    CSS,
    'CssCompleteSheet.pam.php',
);
$assert($sheet['classes']['field']['placeholderColor'] === 0xFF1B7A4E, '::placeholder color must compile with variables.');
$assert(isset($sheet['states']['.a']['pressed'], $sheet['states']['.b']['pressed']), 'Comma-separated state selectors must compile.');
$assert(($sheet['classes']['grid']['gap'] ?? null) === '4' && !isset($sheet['classes']['glass']), '@supports must be evaluated against the native compiler.');
$assert($sheet['queries'][0]['styles']['classes']['card']['backgroundColor'] === 0xFF1B7A4E, ':root variables must resolve inside @media.');

// Diagnostics carry file:line.
$diagnostic = '';
try {
    ScopedStyleCompiler::compile(
        ScopedStyleCompiler::sourceMarker('src/Screens/Broken.pam.php', 40)
            ."\n.ok { color: red; }\n.broken {\n    color: red;\n    float: left;\n}\n",
        'Broken.pam.php',
    );
} catch (RuntimeException $error) {
    $diagnostic = $error->getMessage();
}
$assert(str_starts_with($diagnostic, 'src/Screens/Broken.pam.php:44: '), "CSS diagnostics must report file:line, got {$diagnostic}.");

// Render: CSS reaches native layout properties.
$flexCss = ScopedStyleCompiler::compile(
    '.row { flex-direction: row; flex-wrap: wrap; align-content: center; }'
        .' .cell { flex: 1 1 30%; margin-left: auto; order: 1; }'
        .' .label { font-size: 18px; line-height: 1.5; border-bottom: 1px solid #FF0000; text-shadow: 0 1px 0 #000000; font-variant-numeric: tabular-nums; }',
    'CssCompleteRender.pam.php',
);
$flexTemplate = TemplateCompiler::compile('<Row class="row"><Column class="cell"><Text class="label">A</Text></Column></Row>');
$flexStyled = new CompiledTemplateNode(
    kind: $flexTemplate->kind,
    name: $flexTemplate->name,
    attributes: [
        ...$flexTemplate->attributes,
        '__pamStyles' => json_encode($flexCss, JSON_THROW_ON_ERROR),
    ],
    source: $flexTemplate->source,
    line: $flexTemplate->line,
    column: $flexTemplate->column,
    value: $flexTemplate->value,
);
$flexStyled->children = $flexTemplate->children;
$flexRow = TemplateRenderer::render($flexStyled, null, []);
$flexCell = $flexRow->children()[0];
$flexLabel = $flexCell->children()[0];
$assert(
    $flexRow->properties()[PropKey::AlignContent->value] === 2
        && $flexCell->properties()[PropKey::FlexBasisPercent->value] == 30
        && $flexCell->properties()[PropKey::FlexGrow->value] == 1
        && $flexCell->properties()[PropKey::MarginLeftAuto->value] === true
        && $flexCell->properties()[PropKey::GridOrder->value] === 1,
    'flex-basis, align-content, order and auto margins must reach native properties.',
);
$assert(
    $flexLabel->properties()[PropKey::LineHeight->value] == 27
        && $flexLabel->properties()[PropKey::BorderBottomColor->value] === 0xFFFF0000
        && $flexLabel->properties()[PropKey::TextShadowColor->value] === 0xFF000000
        && $flexLabel->properties()[PropKey::FontFeatureSettings->value] === "'tnum' 1",
    'Line-height multipliers, side colors, text shadows and font features must reach native properties.',
);
