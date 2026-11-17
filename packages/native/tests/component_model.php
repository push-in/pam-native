<?php

declare(strict_types=1);

use Pam\Native\EventKind;
use Pam\Native\Internal\PamPhpRegistry;
use Pam\Native\Internal\TemplateCompiler;
use Pam\Native\Internal\TemplateRenderer;
use Pam\Native\LanguageVersion;
use Pam\Native\NodeKind;
use Pam\Native\Plugin\PluginManager;
use Pam\Native\PropKey;

$assert ??= static function (bool $condition, string $message): void {
    if (!$condition) throw new RuntimeException($message);
};

// p-model on component tags, on:event aliases, typed static props and empty
// lists (1.51.0). The fixture is a Composer package declaring a component
// library in its plugin descriptor, discovered like an installed plugin.
$modelProject = sys_get_temp_dir().'/pam-native-component-model-'.getmypid();
$modelPackage = $modelProject.'/vendor/acme/fields';
foreach ([$modelPackage.'/components', $modelProject.'/vendor/composer'] as $directory) {
    if (!is_dir($directory) && !mkdir($directory, 0o755, true) && !is_dir($directory)) {
        throw new RuntimeException("Cannot create {$directory}.");
    }
}
file_put_contents($modelProject.'/composer.json', '{"name":"acme/app"}');
file_put_contents($modelProject.'/vendor/composer/installed.json', json_encode(['packages' => [[
    'name' => 'acme/fields',
    'install-path' => '../acme/fields',
    'extra' => ['pam-native' => ['plugin' => 'pam-native.plugin.json']],
]]], JSON_THROW_ON_ERROR));
file_put_contents($modelPackage.'/pam-native.plugin.json', json_encode([
    'version' => 1,
    'protocol' => \Pam\Native\Protocol::VERSION,
    'pamNative' => ['minimum' => '1.51.0', 'maximumExclusive' => '2.0.0'],
    'php' => ['components' => 'components'],
], JSON_THROW_ON_ERROR));
file_put_contents($modelPackage.'/components/ModelField.pam', <<<'PAM'
<?php

declare(strict_types=1);

namespace Acme\Fields;

use Pam\Native\Attributes\Action;
use Pam\Native\Attributes\Event;
use Pam\Native\Attributes\Prop;
use Pam\Native\Attributes\Tag;
use Pam\Native\Component;

#[Tag('acme-field')]
#[Event('model', 'string')]
#[Event('change', 'string')]
final class ModelField extends Component
{
    public function __construct(
        #[Prop(immutable: false)] public string $modelValue = '',
        #[Prop(immutable: false)] public string $label = '',
        #[Prop(immutable: false)] public float $size = 0,
    ) {
    }

    #[Action]
    public function input(string $value): void
    {
        $this->modelValue = $value;
        $this->emit('model', $value);
        $this->emit('change', $value);
    }
}
?>

<template language="2">
    <Column>
        <Text>{{ $label }}</Text>
        <Input :value="$modelValue" on:change="input" />
    </Column>
</template>
PAM);

PluginManager::reset();
$assert(
    array_values(PluginManager::componentDirectories($modelProject)) === [realpath($modelPackage.'/components')],
    'A plugin descriptor php.components directory must be reported by PluginManager.',
);
foreach (PluginManager::componentDirectories($modelProject) as $directory) {
    PamPhpRegistry::discover($directory, $modelProject.'/.pam-native/components');
}
PluginManager::reset();

final class ComponentModelForm extends \Pam\Native\Forms\NativeForm
{
    #[\Pam\Native\Forms\Attributes\Required]
    public string $email = '';
}

$modelScope = new class extends \Pam\Native\Component {
    public string $name = 'Ada';
    public int $age = 1;
    public ComponentModelForm $form;
    public array $changes = [];

    public function __construct()
    {
        $this->form = new ComponentModelForm();
    }

    public function changed(string $value): void
    {
        $this->changes[] = $value;
    }
};
$findInputs = static function (\Pam\Native\Element $element, array &$found) use (&$findInputs): void {
    if ($element->kind() === NodeKind::Input) {
        $found[] = $element;
    }
    foreach ($element->children() as $child) {
        if ($child instanceof \Pam\Native\Element) {
            $findInputs($child, $found);
        }
    }
};
$renderModel = static function (string $template, string $source) use ($modelScope, $findInputs): array {
    $tree = TemplateCompiler::compile($template, $source, LanguageVersion::Language1);
    PamPhpRegistry::beginRender();
    $element = TemplateRenderer::render($tree, $modelScope, []);
    PamPhpRegistry::finishRender();
    $inputs = [];
    $findInputs($element, $inputs);

    return [$element, $inputs];
};

[, $inputs] = $renderModel(
    '<Column><acme-field p-model="$name" label="2.0" on:change="changed" /><acme-field p-model="$age" /><acme-field p-model="$form->email" :key="\'mail\'" /></Column>',
    'ComponentModel.pam',
);
$assert(count($inputs) === 3, 'Every component field must render its input.');
$assert(($inputs[0]->properties()[PropKey::Value->value] ?? null) === 'Ada', 'p-model must pass the bound value as modelValue.');
($inputs[0]->events()[EventKind::Change->value])('Grace');
$assert($modelScope->name === 'Grace', 'p-model on a component tag must write the emitted model value back.');
$assert($modelScope->changes === ['Grace'], 'on:change must reach a component that declares a change event, like @change.');
($inputs[1]->events()[EventKind::Change->value])('42');
$assert($modelScope->age === 42, 'p-model must cast the emitted value to the bound property type.');
($inputs[2]->events()[EventKind::Change->value])('dev@pam.dev');
$assert(
    $modelScope->form->email === 'dev@pam.dev' && $modelScope->form->isDirty('email'),
    'p-model="$form->field" must set a NativeForm field through set().',
);
[$element] = $renderModel('<Column><acme-field label="2.0" /></Column>', 'ComponentModelStatic.pam');
$assert(
    str_contains(\Pam\Native\Testing\Mounted::textOf($element), '2.0'),
    'A static attribute must reach a string prop as written ("2.0", not 2).',
);
[, $inputs] = $renderModel('<Column><acme-field p-model="$name" /></Column>', 'ComponentModelEcho.pam');
$assert(($inputs[0]->properties()[PropKey::Value->value] ?? null) === 'Grace', 'A re-render must show the written model value.');

$threw = false;
try {
    $renderModel('<acme-field p-model="$missing" />', 'ComponentModelMissing.pam');
} catch (\Throwable $error) {
    $threw = str_contains($error->getMessage(), 'does not resolve to a property');
}
$assert($threw, 'p-model on a component must explain an unknown target property.');

// An empty p-for (data still loading) keeps the rich list kind, and an empty
// ScrollView is valid: switching kinds later left rows with nowhere to mount.
$emptyScope = new class extends \Pam\Native\Component {
    public array $rows = [];
};
$emptyList = TemplateRenderer::render(
    TemplateCompiler::compile('<VirtualizedList><Text p-for="$row in $rows" :key="$row">{{ $row }}</Text></VirtualizedList>', 'EmptyList.pam'),
    $emptyScope,
    [],
);
$assert($emptyList->kind() === NodeKind::VirtualList, 'An empty VirtualizedList must stay a VirtualizedList.');
$emptyScroll = TemplateRenderer::render(
    TemplateCompiler::compile('<ScrollView><Text p-for="$row in $rows" :key="$row">{{ $row }}</Text></ScrollView>', 'EmptyScroll.pam'),
    $emptyScope,
    [],
);
$assert($emptyScroll->kind() === NodeKind::Scroll, 'An empty ScrollView must render.');

echo "COMPONENT_MODEL_OK p-model scalar cast object-field on-alias static-string empty-list empty-scroll plugin-components\n";

// --- testID (React Native's spelling) is the same test identifier as testId:
// it used to be dropped silently, so components and tests lost their ids.
$testIdTree = \Pam\Native\Internal\TemplateRenderer::render(
    \Pam\Native\Internal\TemplateCompiler::compile('<Column><Text testID="rn-spelling">a</Text><Text testId="pam-spelling">b</Text></Column>'),
    null,
    [],
);
$testIdTexts = $testIdTree->children();
$assert(
    ($testIdTexts[0]->properties()[\Pam\Native\PropKey::TestId->value] ?? null) === 'rn-spelling'
        && ($testIdTexts[1]->properties()[\Pam\Native\PropKey::TestId->value] ?? null) === 'pam-spelling',
    'testID and testId must both set the native test identifier.',
);
echo "COMPONENT_TESTID_OK\n";
