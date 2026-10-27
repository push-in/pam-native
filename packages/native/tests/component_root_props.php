<?php

declare(strict_types=1);

use Pam\Native\App;
use Pam\Native\Internal\TemplateCompiler;
use Pam\Native\Internal\TemplateRenderer;
use Pam\Native\PropKey;

$assert ??= static function (bool $condition, string $message): void {
    if (!$condition) throw new RuntimeException($message);
};

// A component prop that shares a layout attribute name (height, width...) is
// the component's prop only, not also its root's layout attribute.
$propsDirectory = sys_get_temp_dir().'/pam-native-root-props-'.getmypid();
if (!is_dir($propsDirectory) && !mkdir($propsDirectory, 0o755, true) && !is_dir($propsDirectory)) {
    throw new RuntimeException('Cannot create the root props fixture directory.');
}
file_put_contents($propsDirectory.'/RootPropsCard.pam.php', <<<'PAM'
<?php

declare(strict_types=1);

namespace Pam\Native\Tests\RootProps;

use Pam\Native\Component;

final class RootPropsCard extends Component
{
    public function __construct(public float $height = 0.0, public string $title = '')
    {
    }
}
?>

<template>
    <Column :minHeight="$height">
        <Text>{{ $title }}</Text>
    </Column>
</template>
PAM);
App::components($propsDirectory, $propsDirectory.'/.cache');
$rootProps = static function (string $source): array {
    $root = TemplateRenderer::render(TemplateCompiler::compile($source, 'root-props.pam.php'), new stdClass(), []);

    return $root->toElement()->properties();
};
foreach ([
    '<RootPropsCard height="240" title="a" />',
    '<RootPropsCard :height="240.0" title="a" />',
] as $source) {
    $props = $rootProps($source);
    $assert(
        !isset($props[PropKey::Height->value]) && ($props[PropKey::MinHeight->value] ?? null) == 240.0,
        "A component's height prop is not its root's height ({$source}).",
    );
}

// Element-attribute names (translationX, opacity, visible, maxWidth) declared
// as props reach only the component, through the template path and the
// App::make host path alike; key still reaches the root, and what the root
// template computed from the prop is not overridden.
file_put_contents($propsDirectory.'/RootPropsZoomSurface.pam.php', <<<'PAM'
<?php

declare(strict_types=1);

namespace Pam\Native\Tests\RootProps;

use Pam\Native\Component;

final class RootPropsZoomSurface extends Component
{
    public function __construct(
        public float $translationX = 0.0,
        public float $opacity = 1.0,
        public bool $visible = true,
        public float $maxWidth = 0.0,
    ) {
    }
}
?>

<template>
    <View :maxWidth="$maxWidth > 0 ? $maxWidth / 2 : 1000">
        <View :translationX="$translationX" :opacity="$opacity" :visible="$visible" />
    </View>
</template>
PAM);
file_put_contents($propsDirectory.'/RootPropsZoomHost.pam.php', <<<'PAM'
<?php

declare(strict_types=1);

namespace Pam\Native\Tests\RootProps;

use Pam\Native\Component;

final class RootPropsZoomHost extends Component
{
    public float $x = 40.0;
}
?>

<template>
    <Column>
        <RootPropsZoomSurface key="zoom-surface" :translationX="$x" opacity="0.5" visible="false" maxWidth="300" />
    </Column>
</template>
PAM);
App::components($propsDirectory, $propsDirectory.'/.cache');
$zoomChecks = static function (?\Pam\Native\Element $root, string $path) use ($assert): void {
    $inner = $root?->children()[0] ?? null;
    $assert(
        $root instanceof \Pam\Native\Element
            && $inner instanceof \Pam\Native\Element
            && !array_key_exists(PropKey::TranslationX->value, $root->properties())
            && !array_key_exists(PropKey::Opacity->value, $root->properties())
            && !array_key_exists(PropKey::Visible->value, $root->properties())
            && (float) ($root->properties()[PropKey::MaxWidth->value] ?? 0) === 150.0
            && (float) ($inner->properties()[PropKey::TranslationX->value] ?? 0) === 40.0
            && (float) ($inner->properties()[PropKey::Opacity->value] ?? 0) === 0.5
            && ($inner->properties()[PropKey::Visible->value] ?? null) === false
            && $root->elementKey() === 'zoom-surface',
        "Declared component props reach only the component, never its root element ({$path}).",
    );
};
$zoomHost = App::make('Pam\\Native\\Tests\\RootProps\\RootPropsZoomHost')->toElement();
$zoomChecks($zoomHost->children()[0] ?? null, 'App::make host');
$zoomTemplate = TemplateRenderer::render(
    TemplateCompiler::compile('<Column><RootPropsZoomSurface key="zoom-surface" :translationX="40.0" opacity="0.5" visible="false" maxWidth="300" /></Column>', 'root-props-zoom.pam.php'),
    new stdClass(),
    [],
)->toElement();
$zoomChecks($zoomTemplate->children()[0] ?? null, 'template');
echo "COMPONENT_ROOT_PROPS_OK height-prop=not-root-height attribute-props=component-only\n";
