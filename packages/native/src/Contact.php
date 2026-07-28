<?php

declare(strict_types=1);

namespace Pam\Native;

final readonly class Contact
{
    /**
     * @param list<string> $phoneNumbers
     * @param list<string> $emailAddresses
     */
    public function __construct(
        public string $id,
        public string $displayName,
        public string $givenName,
        public string $familyName,
        public array $phoneNumbers,
        public array $emailAddresses,
    ) {
    }
}
