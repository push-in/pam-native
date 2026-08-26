<?php

declare(strict_types=1);

namespace Pam\Native\Dom;

final readonly class DocumentSnapshot
{
    public function __construct(
        public string $rootIdentity,
        public int $nodeCount,
        public int $idCount,
        public int $classCount,
        public int $cachedSelectorCount,
        public int $mutationVersion,
        public int $transactionDepth,
    ) {
    }
}
