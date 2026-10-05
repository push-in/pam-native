<?php

declare(strict_types=1);

namespace Pam\Native\Diagnostics;

/** Where an uncaught runtime error escaped from. */
enum RuntimeErrorPhase: string
{
    /** App bootstrap (App::run, component discovery). The app never rendered. */
    case Boot = 'boot';
    /** A render, theme or encode pass. The committed UI is stale. */
    case Render = 'render';
    /** An element, back, lifecycle or dimension event handler. */
    case Event = 'event';
    /** A native module result callback that has no failure handler. */
    case ModuleResult = 'module';
    /** Any other runtime path (observers, schedulers). */
    case Other = 'other';

    /** Fatal phases leave the screen unusable and need a reload. */
    public function fatal(): bool
    {
        return $this === self::Boot || $this === self::Render;
    }
}
