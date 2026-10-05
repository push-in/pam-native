<?php

declare(strict_types=1);

namespace Pam\Native\Http;

/** Handle for a streamed upload started by Http::multipart() or Http::upload(progress: ...). */
final readonly class HttpTransfer
{
    public function __construct(public int $id)
    {
    }

    /** Cancels the native transfer. The response callback is not invoked afterwards. */
    public function cancel(): void
    {
        HttpTransfers::cancel($this->id);
    }

    public function active(): bool
    {
        return HttpTransfers::active($this->id);
    }
}
