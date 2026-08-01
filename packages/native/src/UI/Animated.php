<?php

declare(strict_types=1);

namespace Pam\Native\UI;

use Closure;
use InvalidArgumentException;
use JsonException;
use Pam\Native\AnimationEasing;
use Pam\Native\AnimationFillMode;
use Pam\Native\AnimationKeyframe;
use Pam\Native\AnimationPlayState;
use Pam\Native\Element;
use Pam\Native\EventKind;
use Pam\Native\Internal\BinaryValue;
use Pam\Native\NodeKind;
use Pam\Native\PropKey;
use Pam\Native\Renderable;
use Pam\Native\Worklets\Worklet;
use Pam\Native\Worklets\WorkletTarget;

final class Animated extends Element
{
    public static function worklet(
        Renderable $content,
        Worklet $worklet,
        WorkletTarget $target,
        int $durationMs = 300,
    ): self {
        return (new self(NodeKind::View))
            ->withChildren([$content])
            ->withProperty(PropKey::WorkletProgram, new BinaryValue($worklet->bytecode()))
            ->withProperty(PropKey::WorkletTarget, $target->value)
            ->withProperty(PropKey::WorkletDurationMs, max(1, min(60_000, $durationMs)))
            ->withProperty(PropKey::WorkletIterations, 1);
    }

    /** @param list<AnimationKeyframe> $keyframes */
    public static function make(
        Renderable $content,
        array $keyframes,
        int $durationMs = 300,
        AnimationEasing $easing = AnimationEasing::EaseInOut,
    ): self {
        if (
            count($keyframes) < 2
            || count($keyframes) > 64
            || array_filter($keyframes, static fn ($frame): bool => !$frame instanceof AnimationKeyframe)
        ) {
            throw new InvalidArgumentException('Animated requires between two and sixty-four keyframes.');
        }
        $offsets = array_map(static fn (AnimationKeyframe $frame): float => $frame->offset, $keyframes);
        if ($offsets !== array_values(array_unique($offsets, SORT_REGULAR))) {
            throw new InvalidArgumentException('Animation keyframe offsets must be unique.');
        }
        $sorted = $offsets;
        sort($sorted, SORT_NUMERIC);
        if ($sorted !== $offsets) {
            throw new InvalidArgumentException('Animation keyframes must be ordered by offset.');
        }

        try {
            $payload = json_encode($keyframes, JSON_THROW_ON_ERROR | JSON_PRESERVE_ZERO_FRACTION);
        } catch (JsonException $error) {
            throw new InvalidArgumentException('Animation keyframes cannot be encoded.', 0, $error);
        }

        return (new self(NodeKind::View))
            ->withChildren([$content])
            ->withProperty(PropKey::AnimationKeyframes, new BinaryValue($payload))
            ->withProperty(PropKey::AnimationDurationMs, max(1, min(60_000, $durationMs)))
            ->withProperty(PropKey::AnimationEasing, $easing->value)
            ->withProperty(PropKey::AnimationIterations, 1)
            ->withProperty(PropKey::AnimationDelayMs, 0)
            ->withProperty(PropKey::AnimationFillMode, AnimationFillMode::Forwards->value)
            ->withProperty(PropKey::AnimationPlayState, AnimationPlayState::Running->value)
            ->withProperty(PropKey::AnimationAutoReverse, false);
    }

    public function iterations(int $iterations): self
    {
        $bounded = max(0, min(10_000, $iterations));
        return $this
            ->withProperty(PropKey::AnimationIterations, $bounded)
            ->withProperty(PropKey::WorkletIterations, $bounded);
    }

    public function delay(int $milliseconds): self
    {
        return $this->withProperty(PropKey::AnimationDelayMs, max(0, min(60_000, $milliseconds)));
    }

    public function fillMode(AnimationFillMode $mode): self
    {
        return $this->withProperty(PropKey::AnimationFillMode, $mode->value);
    }

    public function playState(AnimationPlayState $state): self
    {
        return $this->withProperty(PropKey::AnimationPlayState, $state->value);
    }

    public function autoReverse(bool $enabled = true): self
    {
        return $this->withProperty(PropKey::AnimationAutoReverse, $enabled);
    }

    public function onComplete(Closure $handler): self
    {
        return $this->withEvent(EventKind::AnimationComplete, $handler);
    }
}
