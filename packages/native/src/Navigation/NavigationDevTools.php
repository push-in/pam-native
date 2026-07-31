<?php

declare(strict_types=1);

namespace Pam\Native\Navigation;

use JsonException;

final class NavigationDevTools
{
    /** @var list<array{id: int, kind: int, timestampNs: int, target: string, data: array<string, mixed>, state: array<string, mixed>}> */
    private array $timeline = [];
    /** @var list<NavigationSubscription> */
    private array $subscriptions = [];
    private int $nextId = 1;

    public function __construct(
        private readonly NavigationContainer $container,
        private readonly int $capacity = 256,
    ) {
        $events = [
            [NavigationEventType::State, NavigationTraceKind::State],
            [NavigationEventType::Action, NavigationTraceKind::Action],
            [NavigationEventType::UnhandledAction, NavigationTraceKind::UnhandledAction],
            [NavigationEventType::TransitionStart, NavigationTraceKind::TransitionStart],
            [NavigationEventType::TransitionEnd, NavigationTraceKind::TransitionEnd],
            [NavigationEventType::GestureStart, NavigationTraceKind::Gesture],
            [NavigationEventType::GestureEnd, NavigationTraceKind::Gesture],
            [NavigationEventType::GestureCancel, NavigationTraceKind::Gesture],
        ];
        foreach ($events as [$type, $kind]) {
            $this->subscriptions[] = $container->addListener(
                $type,
                fn (NavigationEvent $event) => $this->record($kind, $event),
            );
        }
    }

    /** @return list<array{id: int, kind: int, timestampNs: int, target: string, data: array<string, mixed>, state: array<string, mixed>}> */
    public function timeline(): array
    {
        return $this->timeline;
    }

    /** @return array<string, mixed> */
    public function tree(): array
    {
        return $this->container->getRootState();
    }

    /** @throws JsonException */
    public function exportJson(): string
    {
        return json_encode(
            ['version' => 1, 'state' => $this->tree(), 'timeline' => $this->timeline],
            JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES,
        );
    }

    public function clear(): void
    {
        $this->timeline = [];
    }

    public function detach(): void
    {
        foreach ($this->subscriptions as $subscription) $subscription->unsubscribe();
        $this->subscriptions = [];
    }

    private function record(NavigationTraceKind $kind, NavigationEvent $event): void
    {
        $this->timeline[] = [
            'id' => $this->nextId++,
            'kind' => $kind->value,
            'timestampNs' => hrtime(true),
            'target' => $event->target,
            'data' => self::serializableData($event->data),
            'state' => $this->container->getRootState(),
        ];
        $capacity = max(16, min(4_096, $this->capacity));
        if (count($this->timeline) > $capacity) array_shift($this->timeline);
    }

    /** @return array<string, mixed> */
    private static function serializableData(array $data): array
    {
        return json_decode(
            json_encode(self::normalize($data), JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES),
            true,
            flags: JSON_THROW_ON_ERROR,
        );
    }

    private static function normalize(mixed $value): mixed
    {
        if ($value instanceof RouteContext) {
            return [
                'name' => $value->name,
                'key' => $value->key,
                'path' => $value->path,
                'params' => $value->all(),
            ];
        }
        if (is_array($value)) {
            $normalized = [];
            foreach ($value as $key => $item) $normalized[$key] = self::normalize($item);
            return $normalized;
        }
        return $value;
    }
}
