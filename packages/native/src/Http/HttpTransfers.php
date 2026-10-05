<?php

declare(strict_types=1);

namespace Pam\Native\Http;

use Closure;
use Pam\Native\Internal\Wire;
use Pam\Native\ModuleResultStatus;
use Pam\Native\Modules\NativeModules;

/** @internal Drives native transfer observation channels. */
final class HttpTransfers
{
    private static int $next = 1;

    /** @var array<int, array{native: ?int, progress: ?Closure, done: Closure}> */
    private static array $active = [];

    private function __construct()
    {
    }

    /**
     * @param array<string, string|int|float|bool> $payload
     * @param Closure(HttpResponse): void $done
     * @param null|Closure(TransferProgress): void $progress
     */
    public static function start(array $payload, Closure $done, ?Closure $progress): HttpTransfer
    {
        $id = self::$next++;
        self::$active[$id] = ['native' => null, 'progress' => $progress, 'done' => $done];
        NativeModules::call('http', 'transferStart', $payload, static function ($result) use ($id): void {
            if ($result->status === ModuleResultStatus::Failure) {
                self::fail($id, $result->payload);

                return;
            }
            $native = (int) (Wire::decodeMap($result->payload)['transfer'] ?? 0);
            if (!isset(self::$active[$id])) {
                self::cancelNative($native);

                return;
            }
            self::$active[$id]['native'] = $native;
            self::next($id);
        });

        return new HttpTransfer($id);
    }

    public static function cancel(int $id): void
    {
        $transfer = self::$active[$id] ?? null;
        unset(self::$active[$id]);
        if (is_array($transfer) && is_int($transfer['native'])) {
            self::cancelNative($transfer['native']);
        }
    }

    public static function active(int $id): bool
    {
        return isset(self::$active[$id]);
    }

    /** @internal */
    public static function resetRuntime(): void
    {
        self::$active = [];
        self::$next = 1;
    }

    private static function next(int $id): void
    {
        $transfer = self::$active[$id] ?? null;
        if (!is_array($transfer) || !is_int($transfer['native'])) {
            return;
        }
        NativeModules::call('http', 'transferNext', ['transfer' => $transfer['native']], static function ($result) use ($id): void {
            $transfer = self::$active[$id] ?? null;
            if (!is_array($transfer)) {
                return;
            }
            if ($result->status === ModuleResultStatus::Failure) {
                self::fail($id, $result->payload);

                return;
            }
            $values = Wire::decodeMap($result->payload);
            switch ((int) ($values['state'] ?? 0)) {
                case 1:
                    if ($transfer['progress'] !== null) {
                        ($transfer['progress'])(new TransferProgress(
                            bytesSent: max(0, (int) ($values['bytesSent'] ?? 0)),
                            totalBytes: max(0, (int) ($values['totalBytes'] ?? 0)),
                        ));
                    }
                    self::next($id);

                    return;
                case 2:
                    unset(self::$active[$id]);
                    self::cancelNative((int) $transfer['native']);
                    ($transfer['done'])(HttpResponse::fromNative($values));

                    return;
                default:
                    self::fail($id, (string) ($values['message'] ?? 'HTTP transfer failed.'));
            }
        });
    }

    private static function fail(int $id, string $message): void
    {
        $transfer = self::$active[$id] ?? null;
        unset(self::$active[$id]);
        if (!is_array($transfer)) {
            return;
        }
        if (is_int($transfer['native'])) {
            self::cancelNative($transfer['native']);
        }
        ($transfer['done'])(new HttpResponse(statusCode: 0, body: '', error: $message));
    }

    private static function cancelNative(int $native): void
    {
        NativeModules::call('http', 'transferCancel', ['transfer' => $native], static fn (): null => null);
    }
}
