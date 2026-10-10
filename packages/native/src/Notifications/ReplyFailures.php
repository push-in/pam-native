<?php

declare(strict_types=1);

namespace Pam\Native\Notifications;

use InvalidArgumentException;

use function count;
use function strlen;

/**
 * How a conversation notification shows an inline reply its endpoint could
 * not deliver. Without it the reply is appended optimistically; with it the
 * reply is sent first and, on failure, the unsent text stays in the
 * notification with the reason, an optional retry button that re-sends the
 * same request ({uuid} and {action_id} keep their values, so a server that
 * dedupes on them never duplicates) and, when the notification is opened,
 * the text in the push data field `draftField`.
 *
 *     ReplyFailures::make(sender: 'Not sent', retryLabel: 'Try again', draftField: 'reply_draft')
 *         ->offline('No connection. Tap Try again.')
 *         ->status(401, 'Your session expired. Open the app to send.')
 *         ->status(403, 'You cannot reply here.', serverMessage: true)
 *         ->status([409, 422, 429], 'Could not send.', retry: true, keepReply: true, serverMessage: true)
 *         ->otherwise('The server did not answer. Tap Try again.');
 *
 * A missing account credential (bearerFromCredential) counts as 401. With
 * `serverMessage`, the JSON `message` of the error response (up to 160
 * characters) replaces the text.
 */
final class ReplyFailures
{
    /** @var array{text: string, retry: bool, keepReply: bool, serverMessage: bool} */
    private array $offline;

    /** @var list<array{codes: list<int>, text: string, retry: bool, keepReply: bool, serverMessage: bool}> */
    private array $statuses = [];

    /** @var array{text: string, retry: bool, keepReply: bool, serverMessage: bool} */
    private array $otherwise;

    private function __construct(
        private readonly string $sender,
        private readonly string $retryLabel,
        private readonly string $draftField,
    ) {
        $this->offline = self::entry('Could not send. Check the connection.', true, true, false);
        $this->otherwise = self::entry('Could not send.', true, true, false);
    }

    /**
     * @param string $sender Name shown on the failure line (e.g. "Not sent").
     * @param string $retryLabel Label of the retry button.
     * @param string $draftField Push data field that carries the unsent text to the opened app ('' disables it).
     */
    public static function make(string $sender = 'Not sent', string $retryLabel = 'Try again', string $draftField = ''): self
    {
        if (trim($sender) === '' || strlen($sender) > 128) {
            throw new InvalidArgumentException('Reply failure senders must contain between 1 and 128 bytes.');
        }
        if (trim($retryLabel) === '' || strlen($retryLabel) > 64) {
            throw new InvalidArgumentException('Retry labels must contain between 1 and 64 bytes.');
        }
        if ($draftField !== '' && preg_match('/^[A-Za-z0-9_.-]{1,64}$/D', $draftField) !== 1) {
            throw new InvalidArgumentException('Draft fields must be simple data keys.');
        }

        return new self($sender, $retryLabel, $draftField);
    }

    /** No HTTP response (offline, timeout). */
    public function offline(string $text, bool $retry = true, bool $keepReply = true): self
    {
        $this->offline = self::entry($text, $retry, $keepReply, false);

        return $this;
    }

    /** @param int|list<int> $codes HTTP status codes (400–599) */
    public function status(int|array $codes, string $text, bool $retry = false, bool $keepReply = false, bool $serverMessage = false): self
    {
        $codes = array_values((array) $codes);
        if ($codes === [] || count($codes) > 32) {
            throw new InvalidArgumentException('A failure needs between 1 and 32 status codes.');
        }
        foreach ($codes as $code) {
            if (!\is_int($code) || $code < 400 || $code > 599) {
                throw new InvalidArgumentException('Failure status codes must be HTTP errors (400–599).');
            }
        }
        $this->statuses[] = ['codes' => $codes, ...self::entry($text, $retry, $keepReply, $serverMessage)];

        return $this;
    }

    /** Any other error status. */
    public function otherwise(string $text, bool $retry = true, bool $keepReply = true, bool $serverMessage = false): self
    {
        $this->otherwise = self::entry($text, $retry, $keepReply, $serverMessage);

        return $this;
    }

    /** @return array<string, mixed> @internal */
    public function toArray(): array
    {
        return [
            'sender' => $this->sender,
            'retryLabel' => $this->retryLabel,
            'draftField' => $this->draftField,
            'offline' => $this->offline,
            'statuses' => $this->statuses,
            'otherwise' => $this->otherwise,
        ];
    }

    /** @return array{text: string, retry: bool, keepReply: bool, serverMessage: bool} */
    private static function entry(string $text, bool $retry, bool $keepReply, bool $serverMessage): array
    {
        if (trim($text) === '' || strlen($text) > 512) {
            throw new InvalidArgumentException('Failure texts must contain between 1 and 512 bytes.');
        }

        return ['text' => $text, 'retry' => $retry, 'keepReply' => $keepReply, 'serverMessage' => $serverMessage];
    }
}
