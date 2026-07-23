<?php

declare(strict_types=1);

namespace Pam\Native;

interface Renderable
{
    public function toElement(): Element;
}
