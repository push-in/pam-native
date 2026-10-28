<?php

declare(strict_types=1);

namespace Pam\Native\Plugin;

/**
 * A plugin provider that contributes template globals (`$name` in every
 * template, `$this->name` in every component). Registered after
 * register() and before boot(). The application wins: a name the app already
 * registered with App::globals() is left alone. Two plugins registering the
 * same name is an error in development builds.
 */
interface ProvidesGlobals
{
    /** @return array<string, mixed> */
    public function globals(): array;
}
