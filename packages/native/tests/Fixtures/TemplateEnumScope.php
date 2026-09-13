<?php

declare(strict_types=1);

namespace Pam\Native\Tests\Fixtures\Domain;

enum TemplateState: int
{
    case Ready = 1;
}

namespace Pam\Native\Tests\Fixtures\Screen;

use Pam\Native\Tests\Fixtures\Domain\TemplateState as ImportedState;

final class TemplateEnumScope
{
}
