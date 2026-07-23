<?php

declare(strict_types=1);

namespace Pam\Native\UI;

use Closure;
use InvalidArgumentException;
use Pam\Native\Element;
use Pam\Native\EventKind;
use Pam\Native\Internal\BinaryValue;
use Pam\Native\Internal\Wire;
use Pam\Native\NodeKind;
use Pam\Native\PropKey;
use Pam\Native\Renderable;

final class CustomView extends Element
{
    /**
     * @param array<string, string|int|float|bool> $properties
     */
    public static function make(
        string $name,
        array $properties = [],
        Renderable ...$children,
    ): self {
        if (preg_match('/^[a-z][a-z0-9_.-]{0,63}$/', $name) !== 1) {
            throw new InvalidArgumentException('Native view names must be safe lowercase identifiers.');
        }

        return (new self(NodeKind::CustomView))
            ->withProperty(PropKey::HostName, $name)
            ->withProperty(PropKey::HostProperties, new BinaryValue(Wire::map($properties)))
            ->withChildren($children);
    }

    /**
     * @param array<string, string|int|float|bool> $properties
     */
    public function hostProperties(array $properties): self
    {
        return $this->withProperty(
            PropKey::HostProperties,
            new BinaryValue(Wire::map($properties)),
        );
    }

    public function onNativeEvent(Closure $handler): self
    {
        return $this->withEvent(EventKind::Native, $handler);
    }
}
