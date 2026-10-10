<?php

declare(strict_types=1);

namespace Pam\Native\UI;

use Closure;
use Pam\Native\Element;
use Pam\Native\EventKind;
use Pam\Native\NodeKind;
use Pam\Native\PropKey;
use Pam\Native\RefreshIndicatorSize;
use Pam\Native\Renderable;

final class RefreshControl extends Element
{
    public static function make(Renderable $content, bool $refreshing = false): self
    {
        $control = (new self(NodeKind::RefreshControl))
            ->withChildren([$content])
            ->withProperty(PropKey::Refreshing, $refreshing);
        // RN `refreshControl` is a prop of the ScrollView, whose own flex
        // sizing decides the box: the wrapper adopts it (its own authored
        // style, applied afterwards, still wins).
        if ($content instanceof Element) {
            $properties = $content->properties();
            foreach (self::ADOPTED_SIZING as $key) {
                if (array_key_exists($key->value, $properties)) {
                    $control = $control->withProperty($key, $properties[$key->value]);
                }
            }
        }

        return $control;
    }

    /** Flex item sizing a RefreshControl takes from its scroll content. */
    private const array ADOPTED_SIZING = [
        PropKey::FlexGrow,
        PropKey::FlexShrink,
        PropKey::FlexBasis,
        PropKey::FlexBasisPercent,
        PropKey::FlexBasisContent,
        PropKey::MinHeight,
        PropKey::AlignSelf,
    ];

    public function onRefresh(Closure $handler): self
    {
        return $this->withEvent(EventKind::Refresh, $handler);
    }

    public function colors(int ...$colors): self
    {
        return $this->withProperty(PropKey::RefreshColors, implode(',', $colors));
    }

    public function progressBackgroundColor(int $color): self
    {
        return $this->withProperty(PropKey::RefreshProgressBackgroundColor, $color);
    }

    public function progressViewOffset(float $offset): self
    {
        return $this->withProperty(PropKey::RefreshProgressViewOffset, $offset);
    }

    public function size(RefreshIndicatorSize $size): self
    {
        return $this->withProperty(PropKey::RefreshIndicatorSize, $size->value);
    }
}
