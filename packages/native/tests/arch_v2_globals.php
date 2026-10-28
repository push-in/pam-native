<?php

declare(strict_types=1);

use Pam\Native\App;
use Pam\Native\Component;
use Pam\Native\Globals;
use Pam\Native\Internal\TemplateCompiler;
use Pam\Native\Internal\TemplateExpression;
use Pam\Native\Internal\TemplateRenderer;
use Pam\Native\Plugin\PluginManager;
use Pam\Native\Plugin\ProvidesGlobals;
use Pam\Native\PropKey;
use Pam\Native\Renderable;
use Pam\Native\Signals\Signal;
use Pam\Native\UI\Text;

use function Pam\Native\computed;
use function Pam\Native\signal;
use function Pam\Native\watch;

$assert = require __DIR__.'/arch_v2_bootstrap.php';

Globals::reset();

// App::globals() adds and overwrites; App::global() registers one; App::forget() removes.
$count = signal(1);
App::globals(['count' => $count, 'brand' => 'Pam', 'user' => ['name' => 'Ana']]);
App::globals(['brand' => 'Pam Native']);
App::global('format', static fn (int $n): string => "#{$n}");
$assert(Globals::get('brand') === 'Pam Native' && Globals::get('count') === $count && Globals::keys() === ['count', 'brand', 'user', 'format'], 'App::globals() must merge and overwrite repeated keys.');

// Development builds reject a silent collision; replace: true overwrites.
$collision = null;
try {
    App::global('brand', 'Other');
} catch (LogicException $error) {
    $collision = $error->getMessage();
}
App::global('brand', 'Replaced', replace: true);
$assert($collision !== null && str_contains($collision, 'Global $brand is already registered') && str_contains($collision, 'replace: true') && Globals::get('brand') === 'Replaced', 'A global collision must be a clear development error; replace: true overwrites.');
$invalid = null;
try {
    App::global('this', 1);
} catch (LogicException $error) {
    $invalid = $error->getMessage();
}
$assert($invalid !== null && str_contains($invalid, 'reserved'), 'Template-reserved names cannot be globals.');

// Globals are reactive: replacing or forgetting re-runs readers.
$reads = [];
$watcher = watch(static fn (): mixed => Globals::get('brand', 'none'), static function (mixed $next) use (&$reads): void {
    $reads[] = $next;
});
App::global('brand', 'Again', replace: true);
App::forget('brand');
App::global('brand', 'Back');
$watcher->dispose();
$assert($reads === ['Again', 'none', 'Back'], 'Replacing, forgetting and re-adding a global must notify readers: '.json_encode($reads));

// $this->name in every component; declared properties win.
final class ArchV2GlobalsReader extends Component
{
    public string $brand = 'own';

    public function render(): Renderable
    {
        return Text::make($this->brand.'|'.$this->format($this->count->value).'|'.$this->user['name']);
    }

    private function format(int $value): string
    {
        return ($this->__get('format'))($value);
    }
}
$reader = new ArchV2GlobalsReader();
$assert($reader->toElement()->properties()[PropKey::Text->value] === 'own|#1|Ana', 'Components must read globals as $this->name, own properties first.');
$unknown = null;
try {
    $reader->__get('nope');
} catch (LogicException $error) {
    $unknown = $error->getMessage();
}
$assert($unknown !== null && str_contains($unknown, "App::global('nope'"), 'An unknown component property must point to App::global().');

// Templates: $global, signal .value, composable-like objects, computed .value, both evaluators.
$double = computed(static fn (): int => $count->value * 2);
App::global('double', $double);
$scope = new class {
    public string $title = 'Inbox';
};
$texts = [];
foreach ([true, false] as $generated) {
    TemplateExpression::useGeneratedCode($generated);
    $count->value = $generated ? 2 : 3;
    $template = TemplateCompiler::compile(
        '<Column><Text>{{ $title }} {{ $count.value }}/{{ $double.value }} {{ $user.name }} {{ $brand }}</Text>'
        .'<Text p-if="$count->value > 1">many</Text><Text>{{ $missing ?? "fallback" }}</Text></Column>',
        'ArchV2Globals.pam',
    );
    $element = TemplateRenderer::render($template, $scope, [])->toElement();
    $collect = static function ($element) use (&$collect): array {
        $found = [];
        $text = $element->properties()[PropKey::Text->value] ?? null;
        if (is_string($text)) {
            $found[] = $text;
        }
        foreach ($element->children() as $child) {
            $found = [...$found, ...$collect($child)];
        }

        return $found;
    };
    $texts[] = $collect($element);
}
TemplateExpression::useGeneratedCode(true);
$assert(
    $texts[0] === ['Inbox 2/4 Ana Back', 'many', 'fallback'] && $texts[1] === ['Inbox 3/6 Ana Back', 'many', 'fallback'],
    'Templates must read $global, signal .value and computed .value in both evaluators: '.json_encode($texts),
);

// Plugins register globals through ProvidesGlobals; the application keeps its own names.
final class ArchV2GlobalsPlugin implements ProvidesGlobals
{
    public function globals(): array
    {
        return ['brand' => 'plugin', 'pluginFlag' => true];
    }
}
PluginManager::registerGlobals(new ArchV2GlobalsPlugin());
$assert(Globals::get('brand') === 'Back' && Globals::get('pluginFlag') === true && str_starts_with((string) Globals::owner('pluginFlag'), 'plugin '), 'Plugin globals must not override the application.');
$pluginCollision = null;
try {
    PluginManager::registerGlobals(new ArchV2GlobalsPlugin());
} catch (LogicException $error) {
    $pluginCollision = $error->getMessage();
}
$assert($pluginCollision !== null && str_contains($pluginCollision, 'plugin ArchV2GlobalsPlugin'), 'Two plugins registering one global must be a clear development error.');

Globals::reset();
$assert(Globals::keys() === [] && $count instanceof Signal, 'Globals::reset() clears the registry.');

echo "ARCH_V2_GLOBALS_OK merge collision reactive this-access templates plugins\n";
