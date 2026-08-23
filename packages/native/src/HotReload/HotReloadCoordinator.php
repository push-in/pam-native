<?php

declare(strict_types=1);

namespace Pam\Native\HotReload;

use Pam\Native\State;

final class HotReloadCoordinator
{
    private const string STATE_KEY = 'runtime.hotReloadSnapshot';
    /** @var array<string, \Closure(): array<string, mixed>> */
    private array $capture = [];
    /** @var array<string, \Closure(array<string, mixed>): void> */
    private array $restore = [];

    /** @param \Closure(): array<string, mixed> $capture @param \Closure(array<string, mixed>): void $restore */
    public function register(string $scope, \Closure $capture, \Closure $restore): void
    {
        if (preg_match('/^[a-z][a-z0-9_.-]{0,63}$/D', $scope) !== 1) {
            throw new \InvalidArgumentException('Hot reload scopes must use portable identifiers.');
        }
        $this->capture[$scope] = $capture;
        $this->restore[$scope] = $restore;
    }

    /** @param list<array<string, mixed>> $actions */
    public function checkpoint(array $actions = []): StateSnapshot
    {
        $state = [];
        foreach ($this->capture as $scope => $capture) {
            $state[$scope] = $capture();
        }
        $snapshot = StateSnapshot::capture($state, $actions);
        State::set(self::STATE_KEY, [
            'schema' => $snapshot->schema,
            'state' => $snapshot->state,
            'actions' => $snapshot->actions,
            'fingerprint' => $snapshot->fingerprint,
        ]);
        return $snapshot;
    }

    public function restore(): bool
    {
        $persisted = State::get(self::STATE_KEY);
        if (!is_array($persisted) || ($persisted['schema'] ?? null) !== 1) {
            return false;
        }
        $state = self::stringMap($persisted['state'] ?? null);
        $actions = self::actionList($persisted['actions'] ?? null);
        $fingerprint = $persisted['fingerprint'] ?? null;
        if ($state === null || $actions === null || !is_string($fingerprint)) {
            return false;
        }
        $snapshot = StateSnapshot::capture($state, $actions);
        if (!hash_equals($snapshot->fingerprint, $fingerprint)) {
            throw new \RuntimeException('Persisted hot reload snapshot failed integrity verification.');
        }
        foreach ($snapshot->state as $scope => $state) {
            $restore = $this->restore[$scope] ?? null;
            $restoredState = self::stringMap($state);
            if ($restore !== null && $restoredState !== null) {
                $restore($restoredState);
            }
        }
        return true;
    }

    public function clear(): void
    {
        State::forget(self::STATE_KEY);
    }

    /** @return array<string, mixed>|null */
    private static function stringMap(mixed $value): ?array
    {
        if (!is_array($value)) {
            return null;
        }
        $normalized = [];
        foreach ($value as $key => $item) {
            if (!is_string($key)) {
                return null;
            }
            $normalized[$key] = $item;
        }
        return $normalized;
    }

    /** @return list<array<string, mixed>>|null */
    private static function actionList(mixed $value): ?array
    {
        if (!is_array($value) || !array_is_list($value)) {
            return null;
        }
        $actions = [];
        foreach ($value as $action) {
            $normalized = self::stringMap($action);
            if ($normalized === null) {
                return null;
            }
            $actions[] = $normalized;
        }
        return $actions;
    }
}
