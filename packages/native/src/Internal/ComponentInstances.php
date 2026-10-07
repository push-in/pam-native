<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

use Pam\Native\Component;

/**
 * Compiled component instances created under one owner (scope), mutated in
 * place so a list of N child components costs O(N) per render, not O(N^2).
 */
final class ComponentInstances
{
    /** @var array<string, Component> */
    public array $instances = [];

    /** @var array<string, true> cache keys used during $pass */
    public array $active = [];

    public int $pass = -1;

    /** Render pass in which every instance was retained (the walk ran). */
    public int $retained = -1;

    /** @var array<string, ComponentCall> last inputs of each call site, by node path */
    public array $calls = [];

    /** @var array<string, string> node path of each instance's call site, by cache key */
    public array $callPaths = [];
}
