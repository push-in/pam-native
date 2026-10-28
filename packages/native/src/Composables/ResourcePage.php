<?php

declare(strict_types=1);

namespace Pam\Native\Composables;

/**
 * What a useResource() loader is asked for, passed after the cursor and the
 * cancellation token: `fn (?string $cursor, CancellationToken $t, ResourcePage $page)`.
 */
final readonly class ResourcePage
{
    public const string INITIAL = 'initial';
    public const string OLDER = 'older';
    public const string NEWER = 'newer';

    public function __construct(
        /** 'initial' (first page or reload), 'older' (more()) or 'newer' (more('newer')). */
        public string $direction,
        public ?string $cursor,
        /** Item to open around (`around:` / `reload(around: $id)`), first page only. */
        public mixed $around,
        /** Current value of the `key:` dependencies, null without them. */
        public mixed $key,
    ) {
    }

    public function initial(): bool
    {
        return $this->direction === self::INITIAL;
    }
}
