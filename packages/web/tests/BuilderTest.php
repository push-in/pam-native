<?php

declare(strict_types=1);

namespace Pam\Native\Web\Tests;

use Pam\Native\Web\Builder;
use Pam\Native\Web\StyleToCss;
use PHPUnit\Framework\TestCase;
use RuntimeException;

final class BuilderTest extends TestCase
{
    private string $app;

    protected function setUp(): void
    {
        $this->app = sys_get_temp_dir().'/pam-web-'.bin2hex(random_bytes(4));
        mkdir($this->app.'/src', 0o777, true);
        file_put_contents($this->app.'/pam-native.json', json_encode([
            'version' => 1, 'applicationId' => 'dev.pam.counter', 'name' => 'Counter', 'entry' => 'index.php',
            'versionName' => '1.2.0', 'targets' => ['tizen', 'webos'],
        ]));
        file_put_contents($this->app.'/index.php', <<<'PHP'
            <?php
            use App\Counter;
            use Pam\Native\App;
            require __DIR__.'/vendor/autoload.php';
            App::run(Counter::class);
            PHP);
        $this->component(<<<'PHP'
                public int $count = 0;
                /** @var list<string> */
                public array $items = ['a', 'b'];

                public function add(): void
                {
                    $this->count++;
                    $this->items[] = sprintf('item %d', $this->count);
                }
            PHP);
    }

    protected function tearDown(): void
    {
        if (getenv('PAM_WEB_KEEP') !== false) {
            fwrite(STDERR, "kept {$this->app}\n");

            return;
        }
        exec('rm -rf '.escapeshellarg($this->app));
    }

    public function testBuildsATizenApp(): void
    {
        $result = (new Builder($this->app, 'tizen', $this->app.'/dist', true))->build();

        self::assertSame([], $result['warnings']);
        foreach (['index.html', 'app.js', 'app.css', 'icon.png', 'config.xml'] as $file) {
            self::assertFileExists($this->app.'/dist/'.$file);
        }
        $js = (string) file_get_contents($this->app.'/dist/app.js');
        self::assertStringContainsString('class App_Counter', $js);
        self::assertStringNotContainsString('?.', $js, 'Chromium 63 has no optional chaining');
        self::assertStringContainsString('package="Devpamcoun"', (string) file_get_contents($this->app.'/dist/config.xml'));
        $this->assertValidJavaScript($this->app.'/dist/app.js');
    }

    public function testBuildsAWebosApp(): void
    {
        (new Builder($this->app, 'webos', $this->app.'/dist', true))->build();

        $info = json_decode((string) file_get_contents($this->app.'/dist/appinfo.json'), true);
        self::assertSame('dev.pam.counter', $info['id']);
        self::assertSame('1.2.0', $info['version']);
        self::assertFileExists($this->app.'/dist/largeIcon.png');
    }

    public function testRefusesUnsupportedPhpWithFileAndLine(): void
    {
        $this->component(<<<'PHP'
                public function each(): iterable
                {
                    yield 1;
                }
            PHP);

        try {
            (new Builder($this->app, 'tizen', $this->app.'/dist', true))->build();
            self::fail('the build accepted a generator');
        } catch (RuntimeException $error) {
            self::assertMatchesRegularExpression('/Counter\.pam\.php:\d+/', $error->getMessage());
            self::assertStringContainsString('not supported in web builds', $error->getMessage());
        }
    }

    public function testCompilesOnlyTheComposerCodeTheAppUses(): void
    {
        $this->vendorPackage();
        $this->component(<<<'PHP'
                public string $label = '';

                public function add(): void
                {
                    \Pam\Native\useFormFactor();
                    $money = new \Acme\Money\Money(1250);
                    $this->label = $money instanceof \Acme\Money\Formattable ? $money->format() : acme_upper('none');
                }
            PHP);

        (new Builder($this->app, 'tizen', $this->app.'/dist', true))->build();

        $js = (string) file_get_contents($this->app.'/dist/app.js');
        self::assertStringContainsString('class Acme_Money_Money extends Acme_Money_Amount', $js);
        self::assertStringContainsString('var Acme_Money_Formattable = PAM.iface(', $js);
        self::assertStringContainsString('PAM.implement(Acme_Money_Money, [Acme_Money_Formattable])', $js);
        self::assertStringContainsString('cents()', $js, 'the trait method is copied into the class');
        self::assertStringContainsString('function fn_acme_upper', $js);
        self::assertStringNotContainsString('Acme_Money_Unused', $js, 'classes the app never reaches are left out');
        self::assertStringContainsString('PAM.useFormFactor()', $js);
        $this->assertValidJavaScript($this->app.'/dist/app.js');
    }

    public function testRefusesComposerCodeItCannotCompileWithItsFile(): void
    {
        $this->vendorPackage();
        $this->component(<<<'PHP'
                public function add(): void
                {
                    \Acme\Money\Unused::all();
                }
            PHP);

        $this->expectException(RuntimeException::class);
        $this->expectExceptionMessageMatches('#vendor/acme/money/src/Unused\.php:\d+: .*not supported in web builds#');
        (new Builder($this->app, 'tizen', $this->app.'/dist', true))->build();
    }

    public function testResolvesTvMediaQueriesForTheTarget(): void
    {
        $style = '.box { color: red; } @media (device-type: tv) { .box { color: blue; } }';

        $tv = StyleToCss::compile($style, 's1', ['device-type' => 'tv', 'input-mode' => 'remote'], 'X');
        $phone = StyleToCss::compile($style, 's1', ['device-type' => 'phone', 'input-mode' => 'touch'], 'X');

        self::assertStringContainsString('blue', $tv);
        self::assertStringNotContainsString('blue', $phone);
        self::assertStringContainsString('.box.s1', $tv);
    }

    /** A Composer package as `composer install` lays it out: PSR-4 classes and a files autoload. */
    public function testRuntimeFunctionsBehaveLikePhp(): void
    {
        exec('command -v node', $found, $missing);
        if ($missing !== 0) {
            self::markTestIncomplete('node is not installed');
        }
        exec('node '.escapeshellarg(__DIR__.'/runtime.test.js').' 2>&1', $output, $status);
        self::assertSame(0, $status, implode("\n", $output));
        self::assertSame([], preg_grep('/^FAIL/', $output), implode("\n", $output));
    }

    private function vendorPackage(): void
    {
        $package = $this->app.'/vendor/acme/money';
        mkdir($package.'/src', 0o777, true);
        mkdir($this->app.'/vendor/composer', 0o777, true);
        file_put_contents($this->app.'/vendor/composer/autoload_psr4.php', <<<'PHP'
            <?php
            $vendorDir = dirname(__DIR__);
            return array('Acme\\Money\\' => array($vendorDir . '/acme/money/src'));
            PHP);
        file_put_contents($this->app.'/vendor/composer/autoload_files.php', <<<'PHP'
            <?php
            $vendorDir = dirname(__DIR__);
            return array('a1b2' => $vendorDir . '/acme/money/functions.php', 'c3d4' => $vendorDir . '/pushinbr/pam-native/src/functions.php');
            PHP);
        $files = [
            'functions.php' => "<?php\nif (!function_exists('acme_upper')) {\n    function acme_upper(string \$text): string { return strtoupper(\$text); }\n}\n",
            'src/Formattable.php' => "<?php\nnamespace Acme\\Money;\ninterface Formattable { public const CURRENCY = 'BRL'; public function format(): string; }\n",
            'src/HasCents.php' => "<?php\nnamespace Acme\\Money;\ntrait HasCents { public function cents(): int { return \$this->value % 100; } }\n",
            'src/Amount.php' => "<?php\nnamespace Acme\\Money;\nabstract class Amount { public function __construct(protected int \$value) {} abstract public function format(): string; }\n",
            'src/Money.php' => "<?php\nnamespace Acme\\Money;\nfinal class Money extends Amount implements Formattable, \\JsonSerializable {\n    use HasCents;\n    public function format(): string { return acme_upper(self::CURRENCY).' '.intdiv(\$this->value, 100).','.str_pad((string) \$this->cents(), 2, '0', STR_PAD_LEFT); }\n    public function jsonSerialize(): mixed { return \$this->value; }\n}\n",
            'src/Unused.php' => "<?php\nnamespace Acme\\Money;\nfinal class Unused { public static function all(): iterable { yield 1; } }\n",
        ];
        foreach ($files as $name => $contents) {
            file_put_contents($package.'/'.$name, $contents);
        }
        // PAM Native's own functions come from the runtime, never from vendor/.
        mkdir($this->app.'/vendor/pushinbr/pam-native/src', 0o777, true);
        file_put_contents($this->app.'/vendor/pushinbr/pam-native/src/functions.php', "<?php\nnamespace Pam\\Native;\nfunction useFormFactor(): object { return Composables\\Sources::formFactor(); }\n");
    }

    private function component(string $body): void
    {
        file_put_contents($this->app.'/src/Counter.pam.php', <<<PHP
            <?php

            namespace App;

            use Pam\\Native\\Component;

            final class Counter extends Component
            {
            {$body}
            }
            ?>
            <template>
                <View class="box">
                    <Text>Count {{ \$count }}</Text>
                    <Text p-for="\$item in \$items" :key="\$item">{{ \$item }}</Text>
                    <Button title="Add" @press="add" autoFocus />
                </View>
            </template>

            <style scoped>
            .box { padding: 16px; }
            </style>
            PHP);
    }

    private function assertValidJavaScript(string $file): void
    {
        exec('command -v node', $found, $missing);
        if ($missing !== 0) {
            self::markTestIncomplete('node is not installed; app.js was not syntax-checked');
        }
        exec('node --check '.escapeshellarg($file).' 2>&1', $output, $status);
        self::assertSame(0, $status, implode("\n", $output));
    }
}
