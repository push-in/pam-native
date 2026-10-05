<?php

declare(strict_types=1);

namespace Pam\Native\Notifications;

use Closure;
use InvalidArgumentException;
use Pam\Native\ModuleResultStatus;
use Pam\Native\Modules\NativeModules;
use Pam\Native\NotificationImportance;
use RuntimeException;

/**
 * Android MessagingStyle conversation notification with inline reply.
 *
 *     Notifications::conversation('chat:42')
 *         ->title('Weekend trip')->group()
 *         ->message(Person::make('Ana', $avatarUrl), 'See you there?', $sentAtMs)
 *         ->reply('Reply')
 *         ->markRead('Mark as read')
 *         ->deepLink('myapp://chat/42')
 *         ->show();
 *
 * Messages are merged with the conversation's native history, so each new
 * message only needs to be sent once. Replies are delivered to
 * Notifications::onAction() and, optionally, to an ActionEndpoint natively.
 */
final class ConversationNotification
{
    private string $title = '';
    private bool $group = false;
    private Person $self;

    /** @var list<array{id: string, text: string, timestamp: int, sender?: array<string, string>}> */
    private array $messages = [];

    private string $replyLabel = '';
    private ?ActionEndpoint $replyEndpoint = null;
    private string $markReadLabel = '';
    private ?ActionEndpoint $markReadEndpoint = null;
    private string $deepLink = '';

    /** @var array<string, mixed> */
    private array $data = [];

    private NotificationImportance $importance = NotificationImportance::High;
    private string $channelId = 'pam-messages';
    private string $channelName = 'Messages';
    private bool $silent = false;

    public function __construct(private readonly string $key)
    {
        if (preg_match('#^[A-Za-z0-9_.:@\#/-]{1,128}$#D', $key) !== 1) {
            throw new InvalidArgumentException('Conversation keys must contain 1-128 characters from [A-Za-z0-9_.:@#/-].');
        }
        $this->self = Person::make('You');
    }

    public function title(string $title): self
    {
        if (strlen($title) > 512) {
            throw new InvalidArgumentException('Conversation titles cannot exceed 512 bytes.');
        }
        $this->title = $title;

        return $this;
    }

    public function group(bool $group = true): self
    {
        $this->group = $group;

        return $this;
    }

    /** The device user, shown for replies. */
    public function self(Person $person): self
    {
        $this->self = $person;

        return $this;
    }

    /** @param int|null $timestamp Unix milliseconds; defaults to now. */
    public function message(Person $sender, string $text, ?int $timestamp = null, ?string $id = null): self
    {
        return $this->append($text, $timestamp, $id, $sender);
    }

    /** A message written by the device user (for example sent from another device). */
    public function ownMessage(string $text, ?int $timestamp = null, ?string $id = null): self
    {
        return $this->append($text, $timestamp, $id, null);
    }

    public function reply(string $label = 'Reply', ?ActionEndpoint $endpoint = null): self
    {
        $this->replyLabel = self::label($label);
        $this->replyEndpoint = $endpoint;

        return $this;
    }

    public function markRead(string $label = 'Mark as read', ?ActionEndpoint $endpoint = null): self
    {
        $this->markReadLabel = self::label($label);
        $this->markReadEndpoint = $endpoint;

        return $this;
    }

    public function deepLink(string $uri): self
    {
        if (strlen($uri) > 8_192) {
            throw new InvalidArgumentException('Notification deep link exceeds 8192 bytes.');
        }
        $this->deepLink = $uri;

        return $this;
    }

    /** @param array<string, mixed> $data Returned with opens and actions. */
    public function data(array $data): self
    {
        $this->data = $data;

        return $this;
    }

    public function importance(NotificationImportance $importance): self
    {
        $this->importance = $importance;

        return $this;
    }

    public function channel(string $id, string $name): self
    {
        if (preg_match('/^[A-Za-z0-9_.-]{1,64}$/D', $id) !== 1 || $name === '' || strlen($name) > 128) {
            throw new InvalidArgumentException('Notification channels need a safe id and a name up to 128 bytes.');
        }
        $this->channelId = $id;
        $this->channelName = $name;

        return $this;
    }

    /** Update without sound or vibration (only the first alert is audible). */
    public function silent(bool $silent = true): self
    {
        $this->silent = $silent;

        return $this;
    }

    /**
     * @param null|Closure(): void $callback
     * @param null|Closure(string): void $failure
     */
    public function show(?Closure $callback = null, ?Closure $failure = null): int
    {
        if ($this->messages === []) {
            throw new InvalidArgumentException('Conversation notifications need at least one message.');
        }

        return NativeModules::call(
            'notifications',
            'showConversation',
            ['spec' => json_encode($this->toArray(), JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE)],
            static function ($result) use ($callback, $failure): void {
                if ($result->status === ModuleResultStatus::Failure) {
                    if ($failure !== null) {
                        $failure($result->payload);

                        return;
                    }
                    throw new RuntimeException($result->payload);
                }
                $callback?->__invoke();
            },
        );
    }

    /** @return array<string, mixed> @internal */
    public function toArray(): array
    {
        $data = json_encode((object) $this->data, JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
        if (strlen($data) > 262_144) {
            throw new InvalidArgumentException('Notification data exceeds 256 KiB.');
        }

        return [
            'key' => $this->key,
            'title' => $this->title,
            'group' => $this->group,
            'self' => $this->self->toArray(),
            'messages' => $this->messages,
            'replyLabel' => $this->replyLabel,
            'replyEndpoint' => $this->replyEndpoint?->toArray(),
            'markReadLabel' => $this->markReadLabel,
            'markReadEndpoint' => $this->markReadEndpoint?->toArray(),
            'deepLink' => $this->deepLink,
            'data' => $data,
            'importance' => $this->importance->value,
            'channelId' => $this->channelId,
            'channelName' => $this->channelName,
            'silent' => $this->silent,
        ];
    }

    private function append(string $text, ?int $timestamp, ?string $id, ?Person $sender): self
    {
        if (trim($text) === '' || strlen($text) > 16_384) {
            throw new InvalidArgumentException('Conversation messages must contain between 1 and 16384 bytes.');
        }
        if (count($this->messages) >= 25) {
            throw new InvalidArgumentException('Conversation notifications keep at most 25 messages.');
        }
        $message = [
            'id' => $id ?? '',
            'text' => mb_substr($text, 0, 4_096),
            'timestamp' => $timestamp ?? (int) floor(microtime(true) * 1_000),
        ];
        if ($sender !== null) {
            $message['sender'] = $sender->toArray();
        }
        $this->messages[] = $message;

        return $this;
    }

    private static function label(string $label): string
    {
        if (trim($label) === '' || strlen($label) > 64) {
            throw new InvalidArgumentException('Notification action labels must contain between 1 and 64 bytes.');
        }

        return $label;
    }
}
