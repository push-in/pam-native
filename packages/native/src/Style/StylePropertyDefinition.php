<?php

declare(strict_types=1);

namespace Pam\Native\Style;

final readonly class StylePropertyDefinition
{
    /**
     * @param list<string> $values
     * @param list<string> $aliases
     */
    public function __construct(
        public int $id,
        public string $cssName,
        public string $nativeName,
        public StyleCompatibility $compatibility,
        public StyleRenderCost $cost,
        public int $minimumAndroidApi = 26,
        public string $iosMinimum = '15.0',
        public array $values = [],
        public array $aliases = [],
        public ?string $fallback = null,
    ) {
    }

    /** @return array<string, int|string|list<string>|null> */
    public function toArray(): array
    {
        return [
            'id' => $this->id,
            'cssName' => $this->cssName,
            'nativeName' => $this->nativeName,
            'compatibility' => $this->compatibility->value,
            'cost' => $this->cost->value,
            'minimumAndroidApi' => $this->minimumAndroidApi,
            'iosMinimum' => $this->iosMinimum,
            'values' => $this->values,
            'aliases' => $this->aliases,
            'fallback' => $this->fallback,
        ];
    }
}
