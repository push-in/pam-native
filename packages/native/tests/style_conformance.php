<?php

declare(strict_types=1);

use Pam\Native\Internal\ScopedStyleCompiler;

/**
 * Executable CSS-to-native documentation matrix. Every spelling documented as
 * supported must compile here; unsupported browser semantics remain fail-fast.
 *
 * @var array<string, string> $styleConformance
 */
$styleConformance = [
    'align-items' => 'center', 'align-self' => 'stretch', 'aspect-ratio' => '16 / 9',
    'background' => '#112233', 'background-color' => 'rgba(1, 2, 3, .5)',
    'border' => '1dp solid #334455', 'border-top' => '2dp solid #334455',
    'border-right' => '2dp solid #334455', 'border-bottom' => '2dp solid #334455',
    'border-left' => '2dp solid #334455', 'border-color' => '#334455',
    'border-top-color' => '#334455', 'border-right-color' => '#334455',
    'border-bottom-color' => '#334455', 'border-left-color' => '#334455',
    'border-width' => '1dp', 'border-top-width' => '1dp', 'border-right-width' => '1dp',
    'border-bottom-width' => '1dp', 'border-left-width' => '1dp',
    'border-radius' => '4dp 8dp 12dp 16dp', 'border-top-left-radius' => '4dp',
    'border-top-right-radius' => '4dp', 'border-bottom-right-radius' => '4dp',
    'border-bottom-left-radius' => '4dp', 'border-style' => 'dashed',
    'bottom' => '2vh', 'box-shadow' => '0 4dp 12dp 0 rgba(0,0,0,.3)',
    'box-sizing' => 'border-box', 'color' => 'white', 'column-gap' => '12dp',
    'display' => 'grid', 'elevation' => '4', 'flex' => '1', 'flex-direction' => 'row',
    'flex-grow' => '1', 'flex-shrink' => '1', 'flex-wrap' => 'wrap',
    'font-family' => 'Inter', 'font-size' => '16sp', 'font-style' => 'italic',
    'font-weight' => '700', 'gap' => '8dp', 'grid-column' => 'span 2',
    'grid-template-columns' => 'repeat(4, 1fr)', 'height' => '50vh',
    'inset' => '1dp 2dp 3dp 4dp', 'inset-inline' => '2dp 4dp',
    'inset-block' => '3dp 6dp', 'justify-content' => 'space-between', 'left' => '10%',
    'letter-spacing' => '.2sp', 'line-height' => '24sp', 'margin' => '1dp 2dp 3dp 4dp',
    'margin-inline' => '2dp 4dp', 'margin-block' => '3dp 6dp', 'margin-top' => '1dp',
    'margin-right' => '1dp', 'margin-bottom' => '1dp', 'margin-left' => '1dp',
    'max-height' => '90vh', 'max-width' => '100%', 'min-height' => '44dp',
    'min-width' => '44dp', 'object-fit' => 'cover', 'opacity' => '72%',
    'overflow' => 'clip', 'padding' => '1dp 2dp 3dp 4dp',
    'padding-inline' => '2dp 4dp', 'padding-block' => '3dp 6dp',
    'padding-top' => '1dp', 'padding-right' => '1dp', 'padding-bottom' => '1dp',
    'padding-left' => '1dp', 'place-items' => 'center start', 'position' => 'absolute',
    'right' => '10%', 'row-gap' => '12dp', 'text-align' => 'center',
    'text-decoration' => 'underline', 'text-transform' => 'uppercase', 'top' => '2vh',
    'transform' => 'translateX(2dp) translateY(3dp) scale(1.02) rotate(2deg)',
    'translation-x' => '2dp', 'translation-y' => '3dp', 'visibility' => 'visible',
    'width' => 'clamp(240dp, 80vw, 720dp)', 'z-index' => '3',
    '-pam-native-background-color' => 'colorSurface',
    '-pam-native-text-color' => 'labelPrimary',
    '-pam-native-border-color' => 'colorAccent',
];

foreach ($styleConformance as $property => $value) {
    ScopedStyleCompiler::compileDeclarations(
        "{$property}: {$value};",
        [],
        "CSS conformance: {$property}",
    );
    $assert(true, "Documented CSS property {$property} must compile.");
}

$invalidStyleConformance = [
    'float: left;',
    'display: block;',
    'position: fixed;',
    'box-sizing: content-box;',
    'grid-template-columns: subgrid;',
    'border-radius: 50% / 25%;',
];
foreach ($invalidStyleConformance as $declaration) {
    $rejected = false;
    try {
        ScopedStyleCompiler::compileDeclarations($declaration, [], 'CSS rejection contract');
    } catch (RuntimeException) {
        $rejected = true;
    }
    $assert($rejected, "Unsupported native CSS must fail closed: {$declaration}");
}
