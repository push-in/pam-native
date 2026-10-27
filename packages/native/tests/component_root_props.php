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
echo "COMPONENT_ROOT_PROPS_OK height-prop=not-root-height\n";
