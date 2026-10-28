<?php

declare(strict_types=1);

namespace TestingApp\Screens;

use Pam\Native\Component;
use Pam\Native\Renderable;
use Pam\Native\UI\Text;

final class LoginScreen extends Component
{
    public function render(): Renderable
    {
        return Text::make('login');
    }
}
