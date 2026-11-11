<?php

declare(strict_types=1);

use App\Screens\Home;
use Pam\Native\App;

use function Pam\Native\useFormFactor;

require __DIR__.'/vendor/autoload.php';

App::globals(['form' => useFormFactor()]);
App::run(Home::class);
