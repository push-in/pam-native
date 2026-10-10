<?php

declare(strict_types=1);

namespace Pam\Native\Notifications;

/** A reply, mark-as-read or button tap on a notification. */
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
        /** True when a bearerFromCredential() endpoint found no token for the push's account: nothing was sent. */
        public bool $credentialMissing = false,
        /** Id of the PushRenderingRule::action() button that was tapped ('' for reply / mark-as-read). */
        public string $action = '',
        /** True when a reply failed and ReplyFailures already shows it in the notification (retry is the user's). */
        public bool $failureShown = false,
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
