<?php

declare(strict_types=1);

namespace Pam\Native\Notifications;

use InvalidArgumentException;

use function count;
use function in_array;
use function strlen;

/**
 * HTTP request executed natively when a notification action is tapped, so a
 * reply reaches the server even while PHP is suspended.
 *
 * Strings may use placeholders: push data fields such as {chat_id}, {reply},
 * {conversation}, {uuid}, {now} and {storage:key} (a value saved with Storage).
 *
 * Multi-account apps authenticate with the token of the account that received
 * the push: bearerFromCredential('user_id') resolves natively, from the
 * Keystore / Keychain backed NotificationCredentials store, the token saved
 * for the account id in the push data field `user_id`. When that account has
 * no token on the device nothing is sent and the notification is dismissed.
 *
 *     ActionEndpoint::post('https://api.example.com/chats/{chat_id}/messages')
 *         ->bearerFromCredential('user_id', 'recipient_user_id')
 *         ->json(['body' => '{reply}', 'client_id' => '{uuid}']);
 */
final class ActionEndpoint
{
    /** @var array<string, string> */
    private array $headers = [];

    /** @var array<array-key, mixed>|null */
    private ?array $body = null;

    private function __construct(private readonly string $method, private readonly string $url)
    {
        if (str_contains($url, '{credential:')) {
            throw new InvalidArgumentException('Credentials may only be used in headers, never in the URL.');
        }
        $scheme = strtolower((string) parse_url(preg_replace('/\{[^}]*\}/', 'x', $url) ?? '', PHP_URL_SCHEME));
        if (strlen($url) > 8_192 || !in_array($scheme, ['https', 'http'], true)) {
            throw new InvalidArgumentException('Action endpoints require an absolute HTTPS URL.');
        }
    }

    public static function post(string $url): self
    {
        return new self('POST', $url);
    }

    public static function put(string $url): self
    {
        return new self('PUT', $url);
    }

    public static function patch(string $url): self
    {
        return new self('PATCH', $url);
    }

    public static function delete(string $url): self
    {
        return new self('DELETE', $url);
    }

    public function header(string $name, string $value): self
    {
        if (preg_match('/^[A-Za-z0-9-]{1,64}$/D', $name) !== 1 || preg_match('/[\r\n]/', $value) === 1 || strlen($value) > 8_192) {
            throw new InvalidArgumentException('Action endpoint headers must be safe single-line values.');
        }
        if (count($this->headers) >= 32 && !isset($this->headers[$name])) {
            throw new InvalidArgumentException('Action endpoints support at most 32 headers.');
        }
        $this->headers[$name] = $value;

        return $this;
    }

    public function bearer(string $token): self
    {
        return $this->header('Authorization', 'Bearer '.$token);
    }

    /** Reads the token natively from Storage at delivery time. */
    public function bearerFromStorage(string $key): self
    {
        if (preg_match('/^[A-Za-z0-9_.-]{1,128}$/D', $key) !== 1) {
            throw new InvalidArgumentException('Storage keys must match [A-Za-z0-9_.-]{1,128}.');
        }

        return $this->bearer('{storage:'.$key.'}');
    }

    /**
     * Authorization: Bearer with the NotificationCredentials token of the
     * account named by the first non-empty push data field in $fields.
     */
    public function bearerFromCredential(string $field, string ...$fallbacks): self
    {
        $fields = [$field, ...$fallbacks];
        foreach ($fields as $name) {
            if (preg_match('/^[A-Za-z0-9_.-]{1,64}$/D', $name) !== 1) {
                throw new InvalidArgumentException('Credential fields must match [A-Za-z0-9_.-]{1,64}.');
            }
        }
        $placeholder = 'credential:'.implode('|', $fields);
        if (strlen($placeholder) > 128) {
            throw new InvalidArgumentException('Credential placeholders are limited to 128 bytes.');
        }

        return $this->bearer('{'.$placeholder.'}');
    }

    /** @param array<array-key, mixed> $body JSON body; string leaves may contain placeholders. */
    public function json(array $body): self
    {
        if (str_contains(json_encode($body, JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES), '{credential:')) {
            throw new InvalidArgumentException('Credentials may only be used in headers, never in the body.');
        }
        $this->body = $body;

        return $this;
    }

    /** @return array{method: string, url: string, headers: object, body: mixed} */
    public function toArray(): array
    {
        return [
            'method' => $this->method,
            'url' => $this->url,
            'headers' => (object) $this->headers,
            'body' => $this->body,
        ];
    }
}
