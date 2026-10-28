<?php

declare(strict_types=1);

namespace RoutesApp\Screens;

use Pam\Native\Element;
use Pam\Native\Renderable;
use Pam\Native\UI\Screen;
use Pam\Native\UI\Text;

abstract class BaseScreen implements Renderable
{
    public function toElement(): Element
    {
        return Screen::make(Text::make(static::class))->toElement();
    }
}
