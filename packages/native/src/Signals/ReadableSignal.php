<?php

declare(strict_types=1);

namespace Pam\Native\Signals;

/**
 * A reactive value: reading it inside a render, computed() or watch()
 * subscribes the reader. Templates read it as `$count.value` (or
 * `$count->value`); PHP code reads `->value` or `->get()`.
 *
 * @template T
 */
interface ReadableSignal
{
    /** @return T */
    public function get(): mixed;

    /** @return T the current value without subscribing the reader */
    public function peek(): mixed;
}
