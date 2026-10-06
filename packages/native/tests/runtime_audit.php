<?php

declare(strict_types=1);

/*
 * The build-time audit warns about PHP the mobile runtime cannot run. It must
 * resolve namespaces and imports, accept what is declared in the bundle,
 * polyfilled (mb_*) or guarded, and flag the rest with file and line.
 */

use Pam\Native\Tooling\MobileRuntimeAudit;

$audit = new MobileRuntimeAudit();
$audit->scan('src/Text.php', <<<'PHP'
<?php
namespace App;

use Normalizer as N;
use function iconv as convert;

final class Text
{
    use \App\Concerns\Formats;

    public function run(string $value): string
    {
        $length = fn () => grapheme_strlen($value);
        $money = new \NumberFormatter('pt_BR', 2);
        $plain = N::normalize($value);
        $ascii = convert('UTF-8', 'ASCII', $value);
        $fits = mb_strlen($value) + mb_substr_count($value, 'a') + mb_strwidth($value);
        $matches = mb_ereg_replace('a', 'b', $value);
        $name = \IntlChar::class;
        $method = $this->iconv_strlen($value);
        $static = self::gzencode($value);

        return helper($value).local($value).\App\Support\format($value);
    }
}

function local(string $value): string
{
    return $value;
}
PHP);
$audit->scan('src/Guarded.php', <<<'PHP'
<?php
if (extension_loaded('intl')) {
    echo idn_to_ascii('example.com');
}
if (class_exists(\Collator::class)) {
    $collator = new \Collator('pt_BR');
}
if (!function_exists('helper')) {
    function helper(string $value): string { return $value; }
}
PHP);
$audit->scan('vendor/acme/zip/src/Archive.php', <<<'PHP'
<?php
namespace Acme\Zip;
class Archive extends \ZipArchive
{
    public function pack(string $data): string { return gzencode($data); }
}
PHP);
$audit->scan('src/Support/format.php', "<?php\nnamespace App\\Support;\nfunction format(string \$v): string { return \$v; }\n");

$findings = $audit->findings();
$expected = [
    'src/Text.php:13: function grapheme_strlen() comes from ext-intl',
    'src/Text.php:14: class NumberFormatter comes from ext-intl',
    'src/Text.php:15: class Normalizer comes from ext-intl',
    'src/Text.php:16: function iconv() comes from ext-iconv',
    'src/Text.php:18: function mb_ereg_replace() comes from ext-mbstring',
    'vendor/acme/zip/src/Archive.php:3: class ZipArchive comes from ext-zip',
    'vendor/acme/zip/src/Archive.php:5: function gzencode() comes from ext-zlib',
];
$assert(
    count($findings) === count($expected)
        && array_map(static fn (string $finding): string => strstr($finding, ', which', true), $findings) === $expected,
    "The mobile runtime audit must flag exactly the unavailable extension symbols:\n".implode("\n", $findings),
);
$assert(
    str_ends_with($findings[0], 'which the PAM mobile PHP runtime (Android/iOS) does not include'),
    'Audit findings must say the symbol is missing from the Android/iOS runtime.',
);

// The catalog decides the optional extensions: an 8.4 runtime has no uri.
$older = new MobileRuntimeAudit(['ctype', 'filter', 'phar', 'session', 'tokenizer']);
$older->scan('src/Link.php', "<?php\n\$uri = new \\Uri\\Rfc3986\\Uri('https://pam.dev');\n\$ok = ctype_digit('1');\n");
$assert(
    PHP_VERSION_ID < 80500 || count($older->findings()) === 1,
    'A runtime without ext-uri must report Uri\\Rfc3986\\Uri.',
);
$current = new MobileRuntimeAudit();
$current->scan('src/Link.php', "<?php\n\$uri = new \\Uri\\Rfc3986\\Uri('https://pam.dev');\n");
$assert($current->findings() === [], 'The PHP 8.5 runtime includes ext-uri.');

// End to end over a directory, the way the CLI calls it.
$directory = sys_get_temp_dir().'/pam-native-runtime-audit-'.getmypid();
@mkdir($directory.'/src', 0777, true);
@mkdir($directory.'/vendor/pushinbr/pam-native/src', 0777, true);
@mkdir($directory.'/vendor/acme/tool/tests', 0777, true);
file_put_contents($directory.'/index.php', "<?php\necho iconv_strlen('x');\n");
file_put_contents($directory.'/src/Ok.php', "<?php\necho mb_strtoupper('ação');\n");
file_put_contents($directory.'/vendor/pushinbr/pam-native/src/Sdk.php', "<?php\necho sodium_bin2hex('x');\nif (function_exists('openssl_encrypt')) { openssl_encrypt('', 'aes-256-gcm', ''); }\n");
file_put_contents($directory.'/vendor/acme/tool/tests/ToolTest.php', "<?php\necho curl_init();\n");
$directoryFindings = (new MobileRuntimeAudit())->auditDirectory($directory);
array_map('unlink', [
    $directory.'/index.php', $directory.'/src/Ok.php', $directory.'/vendor/pushinbr/pam-native/src/Sdk.php',
    $directory.'/vendor/acme/tool/tests/ToolTest.php',
]);
foreach (['/vendor/acme/tool/tests', '/vendor/acme/tool', '/vendor/acme', '/vendor/pushinbr/pam-native/src', '/vendor/pushinbr/pam-native', '/vendor/pushinbr', '/vendor', '/src', ''] as $child) {
    @rmdir($directory.$child);
}
$assert(
    count($directoryFindings) === 2 && str_starts_with($directoryFindings[0], 'index.php:2: function iconv_strlen() comes from ext-iconv')
        && $directoryFindings[1] === 'vendor/pushinbr/pam-native/src/Sdk.php:2: function sodium_bin2hex() comes from ext-sodium, which the PAM mobile PHP runtime (Android/iOS) does not include; use bin2hex(), which works on the device',
    "The directory audit must skip package tests and audit the SDK like any package (guards trusted):\n".implode("\n", $directoryFindings),
);

// The SDK's own sources are clean: its sodium/openssl use is guarded and
// falls back to the native host (Pam\Native\Crypto), so apps never get a
// warning they cannot act on and the device never meets an undefined function.
$sdkFindings = (new MobileRuntimeAudit())->auditDirectory(dirname(__DIR__).'/src');
$assert($sdkFindings === [], "The PHP SDK must not use extensions the mobile runtime lacks unguarded:\n".implode("\n", $sdkFindings));

// Crypto calls point at the API that works on the device.
$cryptoAudit = new MobileRuntimeAudit();
$cryptoAudit->scan('src/Ota.php', "<?php\n\$ok = sodium_crypto_sign_verify_detached(\$s, \$m, \$k);\n\$c = openssl_encrypt(\$p, 'aes-256-gcm', \$k, OPENSSL_RAW_DATA, \$n, \$t);\n\$x = sodium_crypto_box_seal(\$p, \$k);\n");
$cryptoFindings = $cryptoAudit->findings();
$assert(
    count($cryptoFindings) === 3
        && str_ends_with($cryptoFindings[0], 'ext-sodium, which the PAM mobile PHP runtime (Android/iOS) does not include; use Pam\\Native\\Crypto::ed25519Verify(), which works on the device')
        && str_ends_with($cryptoFindings[1], '; use Pam\\Native\\Crypto::aes256GcmEncrypt() for AES-256-GCM, which works on the device')
        && str_ends_with($cryptoFindings[2], 'ext-sodium, which the PAM mobile PHP runtime (Android/iOS) does not include'),
    "Crypto findings must name Pam\\Native\\Crypto when it covers the call:\n".implode("\n", $cryptoFindings),
);
