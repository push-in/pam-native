<?php

declare(strict_types=1);

namespace Pam\Native\Notifications;

use Closure;
use InvalidArgumentException;
use Pam\Native\ModuleResultStatus;
use Pam\Native\Modules\NativeModules;
use RuntimeException;

/**
 * Per-account tokens used by notification action endpoints while PHP is not
 * running (ActionEndpoint::bearerFromCredential()).
 *
 * Android seals each token with an Android Keystore AES-GCM key; iOS keeps it
 * in the Keychain (this device only, after first unlock). Keep the store in
 * sync with the signed-in accounts: set() on sign-in / token refresh,
 * remove() on logout of one account and sync() with the full map at boot.
 */
final class NotificationCredentials
{
    private function __construct()
    {
    }

    /**
     * @param null|Closure(): void $callback
     * @param null|Closure(string): void $failure
     */
    public static function set(string $account, string $token, ?Closure $callback = null, ?Closure $failure = null): int
    {
        return self::call('setCredential', ['account' => self::account($account), 'token' => self::token($token)], $callback, $failure);
    }

    /**
     * @param null|Closure(): void $callback
     * @param null|Closure(string): void $failure
     */
    public static function remove(string $account, ?Closure $callback = null, ?Closure $failure = null): int
    {
        return self::call('removeCredential', ['account' => self::account($account)], $callback, $failure);
    }

    /**
     * Replaces every stored credential: accounts missing from $tokens lose theirs.
     *
     * @param array<string, string> $tokens account id => token
     * @param null|Closure(): void $callback
     * @param null|Closure(string): void $failure
     */
    public static function sync(array $tokens, ?Closure $callback = null, ?Closure $failure = null): int
    {
        if (count($tokens) > 64) {
            throw new InvalidArgumentException('At most 64 notification credentials are supported.');
        }
        $normalized = [];
        foreach ($tokens as $account => $token) {
            $normalized[self::account((string) $account)] = self::token($token);
        }

        return self::call(
            'replaceCredentials',
            ['tokens' => json_encode((object) $normalized, JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES)],
            $callback,
            $failure,
        );
    }

    /**
     * @param null|Closure(): void $callback
     * @param null|Closure(string): void $failure
     */
    public static function clear(?Closure $callback = null, ?Closure $failure = null): int
    {
        return self::sync([], $callback, $failure);
    }

    /** Lower-cased, trimmed account id as matched against push data. @internal */
    public static function account(string $account): string
    {
        $account = strtolower(trim($account));
        if ($account === '' || strlen($account) > 128) {
            throw new InvalidArgumentException('Credential account ids must contain between 1 and 128 bytes.');
        }

        return $account;
    }

    private static function token(string $token): string
    {
        if ($token === '' || strlen($token) > 8_192 || preg_match('/[\r\n]/', $token) === 1) {
            throw new InvalidArgumentException('Credential tokens must be single-line values of at most 8192 bytes.');
        }

        return $token;
    }

    /** @param array<string, string> $payload */
    private static function call(string $method, array $payload, ?Closure $callback, ?Closure $failure): int
    {
        return NativeModules::call(
            'notifications',
            $method,
            $payload,
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
}
