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

    public function testResolvesTvMediaQueriesForTheTarget(): void
    {
        $style = '.box { color: red; } @media (device-type: tv) { .box { color: blue; } }';

        $tv = StyleToCss::compile($style, 's1', ['device-type' => 'tv', 'input-mode' => 'remote'], 'X');
        $phone = StyleToCss::compile($style, 's1', ['device-type' => 'phone', 'input-mode' => 'touch'], 'X');

        self::assertStringContainsString('blue', $tv);
        self::assertStringNotContainsString('blue', $phone);
        self::assertStringContainsString('.box.s1', $tv);
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
