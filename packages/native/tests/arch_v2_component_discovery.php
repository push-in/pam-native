<?php

declare(strict_types=1);

use Pam\Native\App;
use Pam\Native\PropKey;

$assert = require __DIR__.'/arch_v2_bootstrap.php';

// Without App::components(), App::run() registers <root>/src with the cache in <root>/.pam-native/components.
$root = sys_get_temp_dir().'/pam-native-discovery-'.getmypid();
@mkdir($root.'/src/Screens', 0o755, true);
file_put_contents($root.'/src/Screens/DiscoveredCard.pam.php', <<<'PAM'
<?php

declare(strict_types=1);

namespace Pam\Native\Tests\Discovery;

use Pam\Native\Component;

final class DiscoveredCard extends Component
{
    public function __construct(public string $title = 'found')
    {
    }
}
?>

<template>
    <Text>{{ $title }}</Text>
</template>
PAM);
App::resetComponentDiscovery();
$discovered = App::discoverConventionalComponents($root);
$card = App::make('Pam\Native\Tests\Discovery\DiscoveredCard');
$assert(
    $discovered && is_dir($root.'/.pam-native/components')
        && $card->toElement()->properties()[PropKey::Text->value] === 'found',
    'App::run() must discover components in src/ by convention.',
);
$assert(!App::discoverConventionalComponents($root), 'Discovery runs once; an explicit App::components() call also turns it off.');
App::resetComponentDiscovery();
$assert(!App::discoverConventionalComponents(sys_get_temp_dir().'/pam-native-no-src-'.getmypid()), 'A project without src/ is left alone.');
App::components($root.'/src', $root.'/.cache-explicit');
$assert(!App::discoverConventionalComponents($root), 'An explicit App::components() call wins over the convention.');

$remove = static function (string $path) use (&$remove): void {
    foreach (is_dir($path) ? (scandir($path) ?: []) : [] as $entry) {
        if ($entry !== '.' && $entry !== '..') {
            is_dir($path.'/'.$entry) && !is_link($path.'/'.$entry) ? $remove($path.'/'.$entry) : unlink($path.'/'.$entry);
        }
    }
    @rmdir($path);
};
$remove($root);

echo "ARCH_V2_DISCOVERY_OK src-convention cache explicit-wins\n";
