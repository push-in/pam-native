<?php

declare(strict_types=1);

namespace TestingApp\Screens;

use Pam\Native\Component;
use Pam\Native\Renderable;
use Pam\Native\UI\Text;

use function Pam\Native\onBlur;
use function Pam\Native\onFocus;

final class HomeScreen extends Component
{
    public function setup(): void
    {
        onFocus(static fn () => Log::$entries[] = 'home:focus');
        onBlur(static fn () => Log::$entries[] = 'home:blur');
    }

    public function render(): Renderable
    {
        return Text::make('home');
    }
}
