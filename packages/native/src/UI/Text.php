<?php

declare(strict_types=1);

namespace Pam\Native\UI;

use Pam\Native\Element;
use Pam\Native\NodeKind;
use Pam\Native\PropKey;

final class Text extends Element
{
    public static function make(string $text): self
    {
        return (new self(NodeKind::Text))->withProperty(PropKey::Text, $text);
    }

    public function numberOfLines(int $lines): self
    {
        return $this->withProperty(PropKey::NumberOfLines, max(0, $lines));
    }
}
