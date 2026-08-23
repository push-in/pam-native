<?php

declare(strict_types=1);

namespace Pam\Native\HotReload;

final readonly class StateSnapshot
{
    /**
     * @param array<string, mixed> $state
     * @param list<array<string, mixed>> $actions
     */
    public function __construct(
        public int $schema,
        public array $state,
        public array $actions,
        public string $fingerprint,
    ) {
    }

    /**
     * @param array<string, mixed> $state
     * @param list<array<string, mixed>> $actions
     */
    public static function capture(array $state, array $actions = []): self
    {
        $canonical = json_encode(['schema' => 1, 'state' => $state, 'actions' => $actions], JSON_THROW_ON_ERROR);
        if (strlen($canonical) > 8_388_608) {
            throw new \OverflowException('Hot reload snapshot exceeds 8 MiB.');
        }
        return new self(1, $state, $actions, hash('sha256', $canonical));
    }

    /** @return array<string, mixed> */
    public function restore(): array
    {
        return $this->state;
    }
}
