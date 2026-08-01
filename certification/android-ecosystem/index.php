<?php

declare(strict_types=1);

require __DIR__.'/vendor/autoload.php';

use Pam\Native\App;
use Pam\Native\UI\Screen;
use Pam\Native\UI\Text;

App::run(static fn (): Screen => Screen::make(
    Text::make('PAM Native ecosystem certification'),
));
