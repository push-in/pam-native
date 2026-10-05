<?php

declare(strict_types=1);

namespace Pam\Native\Notifications;

/** A reply or mark-as-read tap on a conversation notification. */
final readonly class NotificationAction
{
    /** @param array<string, mixed> $data Push/notification data of the conversation. */
    public function __construct(
        public NotificationActionType $type,
        public string $conversation,
        public string $text,
        public array $data,
        public ?string $deepLink,
        public bool $handledNatively,
        public int $statusCode,
        public int $timestamp,
    ) {
    }

    /** True when an ActionEndpoint already delivered the action successfully. */
    public function delivered(): bool
    {
        return $this->handledNatively && $this->statusCode >= 200 && $this->statusCode < 300;
    }

    public function isReply(): bool
    {
        return $this->type === NotificationActionType::Reply;
    }
}
