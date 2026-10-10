<?php

declare(strict_types=1);

namespace Pam\Native\Notifications;

use BackedEnum;
use Closure;
use InvalidArgumentException;
use LogicException;
use Pam\Native\ModuleResultStatus;
use Pam\Native\Modules\NativeModules;
use Pam\Native\NotificationImportance;
use RuntimeException;

use function count;
use function in_array;
use function is_bool;
use function strlen;

/**
 * Declarative native rendering for one data-push type. Placeholders such as
 * {chat_id} are filled from the push data; {_title}, {_body} and {_id} expose
 * the push envelope.
 */
final class PushRenderingRule
{
    private const int CONVERSATION = 1;
    private const int NOTIFICATION = 2;
    private const int DISMISS = 3;

    private ?int $kind = null;

    /** @var array<string, string> */
    private array $conversation = [];

    /** @var array<string, string> */
    private array $message = [];

    private string $self = 'You';
    private string $title = '';
    private string $body = '';
    private string $deepLink = '';

    /** @var array{label: string, endpoint: ?array<string, mixed>}|null */
    private ?array $reply = null;

    /** @var array{label: string, endpoint: ?array<string, mixed>}|null */
    private ?array $markRead = null;

    private NotificationImportance $importance = NotificationImportance::High;
    private string $channelId = '';
    private string $channelName = '';

    /** @var list<array<string, mixed>> */
    private array $suppress = [];

    /** @var array<string, string> extra data fields the push must match */
    private array $where = [];

    /** @var list<string> data fields the push must carry (non-empty) */
    private array $requires = [];

    private ?ReplyFailures $replyFailures = null;

    /** @var list<array{id: string, label: string, endpoint: array<string, mixed>, dismiss: bool}> */
    private array $actions = [];

    /** @var array{identifier: string, withoutReply: string}|null */
    private ?array $category = null;

    public function __construct(private readonly string $type, private readonly string $field = 'type')
    {
        if ($type === '' || strlen($type) > 128) {
            throw new InvalidArgumentException('Push rendering types must contain between 1 and 128 bytes.');
        }
        if (preg_match('/^[A-Za-z0-9_.-]{1,64}$/D', $field) !== 1) {
            throw new InvalidArgumentException('Push rendering fields must be simple data keys.');
        }
    }

    /**
     * Also require data[$field] === $value. When several rules match a push,
     * the one with the most conditions wins (e.g. a system push with
     * `system_event` = `post_collaboration_invite` over the generic one).
     */
    public function where(string $field, string $value): self
    {
        if (preg_match('/^[A-Za-z0-9_.-]{1,64}$/D', $field) !== 1 || strlen($value) > 1_024) {
            throw new InvalidArgumentException('Push rendering conditions need a simple data key and a value up to 1024 bytes.');
        }
        if (count($this->where) >= 8 && !isset($this->where[$field])) {
            throw new InvalidArgumentException('Push rendering rules support at most 8 conditions.');
        }
        $this->where[$field] = $value;

        return $this;
    }

    /** Also require these data fields to be present and non-empty (counts toward specificity). */
    public function requires(string ...$fields): self
    {
        foreach ($fields as $field) {
            if (preg_match('/^[A-Za-z0-9_.-]{1,64}$/D', $field) !== 1) {
                throw new InvalidArgumentException('Required push fields must be simple data keys.');
            }
            if (!in_array($field, $this->requires, true)) {
                $this->requires[] = $field;
            }
        }
        if (count($this->requires) > 8) {
            throw new InvalidArgumentException('Push rendering rules require at most 8 fields.');
        }

        return $this;
    }

    /** Render as a MessagingStyle conversation keyed by a template such as '{chat_id}'. */
    public function conversation(string $key, ?string $title = null, string|bool $group = false): self
    {
        $this->kind(self::CONVERSATION);
        $this->conversation = [
            'key' => self::template($key),
            'title' => self::template($title ?? ''),
            'group' => is_bool($group) ? ($group ? 'true' : '') : self::template($group),
        ];

        return $this;
    }

    public function message(
        string $sender,
        string $text = '{_body}',
        ?string $avatar = null,
        ?string $timestamp = null,
        ?string $id = null,
        ?string $senderKey = null,
    ): self {
        $this->message = [
            'sender' => self::template($sender),
            'text' => self::template($text),
            'avatar' => self::template($avatar ?? ''),
            'timestamp' => self::template($timestamp ?? ''),
            'id' => self::template($id ?? ''),
            'senderKey' => self::template($senderKey ?? ''),
        ];

        return $this;
    }

    /** Display name of the device user for inline replies. */
    public function self(string $name): self
    {
        if (trim($name) === '' || strlen($name) > 256) {
            throw new InvalidArgumentException('Self names must contain between 1 and 256 bytes.');
        }
        $this->self = $name;

        return $this;
    }

    /** Render as a standard notification. */
    public function notification(string $title = '{_title}', string $body = '{_body}'): self
    {
        $this->kind(self::NOTIFICATION);
        $this->title = self::template($title);
        $this->body = self::template($body);

        return $this;
    }

    /** Remove a conversation notification, e.g. when it was read on another device. */
    public function dismissConversation(string $key): self
    {
        $this->kind(self::DISMISS);
        $this->conversation = ['key' => self::template($key)];

        return $this;
    }

    public function deepLink(string $template): self
    {
        $this->deepLink = self::template($template);

        return $this;
    }

    /**
     * Inline reply. $hideWhen leaves the action out for pushes whose data
     * matches any field => value pair (e.g. ['can_reply' => '0'] when the
     * server says the recipient may not send).
     *
     * @param array<string, string> $hideWhen
     */
    public function reply(string $label = 'Reply', ?ActionEndpoint $endpoint = null, array $hideWhen = []): self
    {
        $this->reply = ['label' => self::label($label), 'endpoint' => $endpoint?->toArray()];
        if ($hideWhen !== []) {
            foreach ($hideWhen as $field => $value) {
                if (!\is_string($field) || preg_match('/^[A-Za-z0-9_.-]{1,64}$/D', $field) !== 1 || strlen((string) $value) > 1_024) {
                    throw new InvalidArgumentException('Reply conditions need simple data keys.');
                }
            }
            $this->reply['hideWhen'] = array_map('strval', $hideWhen);
        }

        return $this;
    }

    /** Send replies first and keep the ones that fail visible (see ReplyFailures). */
    public function replyFailures(ReplyFailures $failures): self
    {
        $this->replyFailures = $failures;

        return $this;
    }

    /**
     * A background button (no app UI): tapping it sends $endpoint natively,
     * app killed or not, and reports a NotificationActionType::Button action
     * with $id. With $dismiss the notification is removed once the endpoint
     * answers 2xx. At most 3 buttons (Android shows three actions).
     */
    public function action(string $id, string $label, ActionEndpoint $endpoint, bool $dismiss = true): self
    {
        if (preg_match('/^[A-Za-z0-9_.-]{1,64}$/D', $id) !== 1) {
            throw new InvalidArgumentException('Notification button ids must match [A-Za-z0-9_.-]{1,64}.');
        }
        $this->actions = array_values(array_filter($this->actions, static fn (array $action): bool => $action['id'] !== $id));
        if (count($this->actions) >= 3) {
            throw new InvalidArgumentException('Notifications support at most 3 buttons.');
        }
        $this->actions[] = ['id' => $id, 'label' => self::label($label), 'endpoint' => $endpoint->toArray(), 'dismiss' => $dismiss];

        return $this;
    }

    /**
     * iOS: the APNs category the server sets on these pushes. The framework
     * registers it with this rule's reply / mark-as-read / buttons and
     * handles them natively. $withoutReply is the category the server uses
     * when the reply is hidden (iOS cannot drop an action per push).
     */
    public function category(string $identifier, ?string $withoutReply = null): self
    {
        foreach ([$identifier, $withoutReply ?? 'x'] as $value) {
            if (preg_match('/^[A-Za-z0-9_.-]{1,64}$/D', $value) !== 1) {
                throw new InvalidArgumentException('Notification categories must match [A-Za-z0-9_.-]{1,64}.');
            }
        }
        $this->category = ['identifier' => $identifier, 'withoutReply' => $withoutReply ?? ''];

        return $this;
    }

    public function markRead(string $label = 'Mark as read', ?ActionEndpoint $endpoint = null): self
    {
        $this->markRead = ['label' => self::label($label), 'endpoint' => $endpoint?->toArray()];

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

    /**
     * Skip the notification while the app is in the foreground on a matching route.
     *
     * Pass a deep-link path pattern ('chat/{chat_id}', matched against the
     * route's deep link) or a route name with parameter templates:
     * suppressWhenRoute(AppRoute::Chat, ['chatId' => '{chat_id}']).
     *
     * @param array<string, string> $params
     */
    public function suppressWhenRoute(string|BackedEnum $route, array $params = []): self
    {
        $route = $route instanceof BackedEnum ? (string) $route->value : $route;
        if ($route === '' || strlen($route) > 1_024) {
            throw new InvalidArgumentException('Suppressed routes must contain between 1 and 1024 bytes.');
        }
        if ($params === [] && str_contains($route, '/')) {
            $this->suppress[] = ['path' => self::template($route)];
        } else {
            $this->suppress[] = [
                'route' => $route,
                'params' => (object) array_map(static fn (mixed $value): string => self::template((string) $value), $params),
            ];
        }
        PushRendering::trackRoutes();

        return $this;
    }

    /**
     * Persists the rule natively. Call it at every boot; registering again replaces it.
     *
     * @param null|Closure(): void $callback
     * @param null|Closure(string): void $failure Receives the reason, e.g. unsupported platform.
     */
    public function register(?Closure $callback = null, ?Closure $failure = null): int
    {
        return NativeModules::call(
            'notifications',
            'registerPushRendering',
            ['rule' => json_encode($this->toArray(), JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE)],
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
        if ($this->kind === null) {
            throw new LogicException('Choose conversation(), notification() or dismissConversation() before registering.');
        }
        if ($this->kind === self::CONVERSATION && $this->message === []) {
            throw new LogicException('Conversation rules need message(sender: ...).');
        }

        return [
            'type' => $this->type,
            'field' => $this->field,
            'kind' => $this->kind,
            'conversation' => (object) $this->conversation,
            'message' => (object) $this->message,
            'self' => $this->self,
            'title' => $this->title,
            'body' => $this->body,
            'deepLink' => $this->deepLink,
            'reply' => $this->reply,
            'markRead' => $this->markRead,
            'importance' => $this->importance->value,
            'channelId' => $this->channelId,
            'channelName' => $this->channelName,
            'suppress' => $this->suppress,
            'where' => (object) $this->where,
            'requires' => $this->requires,
            'replyFailures' => $this->replyFailures?->toArray(),
            'actions' => $this->actions,
            'category' => $this->category,
        ];
    }

    private function kind(int $kind): void
    {
        if ($this->kind !== null && $this->kind !== $kind) {
            throw new LogicException('A push rendering rule has exactly one presentation.');
        }
        $this->kind = $kind;
    }

    private static function template(string $value): string
    {
        if (strlen($value) > 16_384) {
            throw new InvalidArgumentException('Push rendering templates cannot exceed 16 KiB.');
        }

        return $value;
    }

    private static function label(string $label): string
    {
        if (trim($label) === '' || strlen($label) > 64) {
            throw new InvalidArgumentException('Notification action labels must contain between 1 and 64 bytes.');
        }

        return $label;
    }
}
