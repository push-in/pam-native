<?php

declare(strict_types=1);

use TestingApp\Middleware\Authenticate;

// Shared by index.php (App::run() loads it) and tests (Testing\routes()).
return ['auth' => Authenticate::class];
