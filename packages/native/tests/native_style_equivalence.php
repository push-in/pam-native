<?php

declare(strict_types=1);

/*
 * The native Style reader (pam_native_style_properties, registered by the
 * Android/iOS bridges) must return exactly what Style::properties() returns
 * in PHP: the non-null fields as wire values, in Style::KEYS order. Run by
 * scripts/native-encoder-test.sh; skipped without the native functions.
 */

use Pam\Native\GridTemplate;
use Pam\Native\Style;

spl_autoload_register(static function (string $class): void {
    $prefix = 'Pam\\Native\\';
    if (str_starts_with($class, $prefix)) {
        $path = __DIR__.'/../src/'.str_replace('\\', '/', substr($class, strlen($prefix))).'.php';
        if (is_file($path)) {
            require $path;
        }
    }
});

if (!function_exists('pam_native_style_properties')) {
    echo "NATIVE_STYLE_EQUIVALENCE skipped (no native reader)\n";

    return;
}

$keys = (new ReflectionClassConstant(Style::class, 'KEYS'))->getValue();
$expected = static function (Style $style) use ($keys): array {
    $properties = [];
    foreach ($keys as $field => $key) {
        $value = $style->$field;
        if ($value === null) {
            continue;
        }
        if ($value instanceof BackedEnum) {
            $value = $value->value;
        } elseif ($value instanceof GridTemplate) {
            $value = $value->toWire();
        }
        $properties[$key] = $value;
    }

    return $properties;
};

$parameters = (new ReflectionClass(Style::class))->getConstructor()->getParameters();
mt_srand(11);
$checks = 0;
$failures = 0;
for ($n = 0; $n < 20_000; $n++) {
    $arguments = [];
    foreach ($parameters as $parameter) {
        if (mt_rand(0, 9) !== 0 && $n > 0) {
            continue;
        }
        $type = $parameter->getType()->getName();
        $value = match (true) {
            $type === 'int' => mt_rand(-5, 5000),
            $type === 'float' => mt_rand(0, 1000) / 7,
            $type === 'bool' => (bool) mt_rand(0, 1),
            $type === 'string' => 'v'.mt_rand(0, 9),
            $type === GridTemplate::class => GridTemplate::fromWire('0,2,8,4;640,3,12,8'),
            enum_exists($type) => $type::cases()[mt_rand(0, count($type::cases()) - 1)],
            default => null,
        };
        if ($value !== null) {
            $arguments[$parameter->getName()] = $value;
        }
    }
    $style = new Style(...$arguments);
    $checks++;
    if ($style->properties() !== $expected($style)) {
        $failures++;
        fwrite(STDERR, 'FAIL style '.json_encode(array_map(static fn ($v) => $v instanceof UnitEnum ? $v->name : $v, $arguments))."\n");
    }
}
$checks++;
if ((new Style())->properties() !== []) {
    $failures++;
}

echo 'NATIVE_STYLE_EQUIVALENCE '.($failures === 0 ? 'OK' : 'FAILED')." checks={$checks} failures={$failures}\n";
exit($failures === 0 ? 0 : 1);
