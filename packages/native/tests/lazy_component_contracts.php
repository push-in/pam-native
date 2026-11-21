<?php

declare(strict_types=1);

use Pam\Native\App;
use Pam\Native\Internal\PamPhpRegistry;
use Pam\Native\TemplateRegistry;
use Pam\Native\PropKey;

$assert = require __DIR__.'/arch_v2_bootstrap.php';

// Produção: o boot registra as tags sem carregar as classes nem montar os contratos; o
// primeiro uso de um componente carrega só ele (e os que ele usa). Desenvolvimento segue
// carregando e validando tudo no boot.
$root = sys_get_temp_dir().'/pam-native-lazy-'.getmypid();
@mkdir($root.'/src', 0o755, true);
$component = static fn (string $class, string $template): string => <<<PAM
<?php

declare(strict_types=1);

namespace Pam\Native\Tests\Lazy;

use Pam\Native\Attributes\Prop;
use Pam\Native\Component;

final class {$class} extends Component
{
    public function __construct(#[Prop] public string \$label = '{$class}')
    {
    }
}
?>

<template language="2">
    {$template}
</template>
PAM;
file_put_contents($root.'/src/LazyLeaf.pam.php', $component('LazyLeaf', '<Text>{{ $label }}</Text>'));
file_put_contents($root.'/src/LazyParent.pam.php', $component('LazyParent', '<View><LazyLeaf label="folha" /></View>'));
file_put_contents($root.'/src/LazyUnused.pam.php', $component('LazyUnused', '<Text>nunca</Text>'));

$previous = getenv('PAM_NATIVE_MODE');
try {
    putenv('PAM_NATIVE_MODE=production');
    PamPhpRegistry::reset();
    TemplateRegistry::reset();
    PamPhpRegistry::discover($root.'/src', $root.'/.cache');
    $assert(
        !class_exists('Pam\Native\Tests\Lazy\LazyParent', false)
            && !class_exists('Pam\Native\Tests\Lazy\LazyLeaf', false)
            && !class_exists('Pam\Native\Tests\Lazy\LazyUnused', false),
        'In production the boot must not load component classes.',
    );

    $parent = App::make('Pam\Native\Tests\Lazy\LazyParent');
    $texts = [];
    $walk = static function (mixed $node) use (&$walk, &$texts): void {
        if ($node instanceof Pam\Native\Element) {
            $text = $node->properties()[PropKey::Text->value] ?? null;
            if (is_string($text)) {
                $texts[] = $text;
            }
            foreach ($node->children() as $child) {
                $walk($child);
            }
        } elseif ($node instanceof Pam\Native\Component) {
            $walk($node->toElement());
        }
    };
    $walk($parent->toElement());
    $assert(in_array('folha', $texts, true), 'A lazily loaded parent must render its lazily resolved child with its props.');
    $assert(
        class_exists('Pam\Native\Tests\Lazy\LazyLeaf', false) && !class_exists('Pam\Native\Tests\Lazy\LazyUnused', false),
        'Only the components a screen uses get loaded.',
    );
    $assert(TemplateRegistry::tagContract('LazyLeaf') !== null, 'The contract is built on first use.');
    $assert(TemplateRegistry::tagContract('View') === TemplateRegistry::tagContract('View'), 'Native tags keep their own contracts.');
} finally {
    putenv($previous === false ? 'PAM_NATIVE_MODE' : 'PAM_NATIVE_MODE='.$previous);
    exec('rm -rf '.escapeshellarg($root));
}

echo "LAZY_COMPONENT_CONTRACTS_OK boot-loads-nothing render-on-demand contract-on-first-use\n";
