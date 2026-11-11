<?php

declare(strict_types=1);

use App\Screens\Home;
use Pam\Native\App;
use Pam\Native\KeyEvent;
use PHPUnit\Framework\TestCase;

use function Pam\Native\Testing\mount;
use function Pam\Native\Testing\reset;
use function Pam\Native\useFormFactor;

final class ApplicationTest extends TestCase
{
    protected function setUp(): void
    {
        reset();
        App::components(dirname(__DIR__).'/src', sys_get_temp_dir().'/pam-tv-components');
        App::globals(['form' => useFormFactor()]);
    }

    public function testHomeShowsTheMenuHeroAndRails(): void
    {
        $home = mount(Home::class);
        $text = $home->text();

        self::assertStringContainsString('PAM TV', $text);
        self::assertStringContainsString('Trending now', $text);
        self::assertStringContainsString('Films for tonight', $text);
        self::assertStringContainsString('Night Shift', $text);
    }

    public function testFocusPreviewsAndTheRemoteDrivesThePlayer(): void
    {
        $home = mount(Home::class);
        $screen = $home->instance;
        self::assertInstanceOf(Home::class, $screen);

        $screen->preview('films-2');
        $home->render();
        self::assertStringContainsString('Iron Valley', $home->text());

        $screen->play('films-2');
        $screen->playerKey(new KeyEvent('ArrowRight'));
        $screen->playerKey(new KeyEvent('MediaPlayPause'));
        $home->render();
        self::assertStringContainsString('Paused · 10%', $home->text());

        $screen->playerKey(new KeyEvent('GoBack'));
        self::assertNull($screen->playing);
        self::assertSame('films-2', $screen->resume);
    }
}
