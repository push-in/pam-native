<?php

declare(strict_types=1);

/** @return array{sampleCount: int, failureCount: int, p95DurationMicros: int, p95BudgetMicros: int} */
function verifyHotReloadEvidence(
    string $contents,
    int $minimumSamples,
    int $maximumBudgetMicros = 1_000_000,
): array
{
    if ($minimumSamples < 1) {
        throw new InvalidArgumentException('minimum samples must be positive');
    }
    if ($maximumBudgetMicros < 1) {
        throw new InvalidArgumentException('maximum budget must be positive');
    }
    if (strlen($contents) > 1_048_576) {
        throw new InvalidArgumentException('evidence exceeds the 1 MiB limit');
    }
    try {
        $document = json_decode($contents, true, flags: JSON_THROW_ON_ERROR);
    } catch (JsonException $error) {
        throw new InvalidArgumentException('evidence is not valid JSON', previous: $error);
    }
    if (!is_array($document)) {
        throw new InvalidArgumentException('evidence root must be an object');
    }
    requireEvidenceInteger($document, 'schemaVersion', 1);
    requireEvidenceInteger($document, 'surfaceCode', 2);
    $platformCode = requireNonNegativeInteger($document, 'platformCode');
    if (!in_array($platformCode, [1, 2], true)) {
        throw new InvalidArgumentException('platformCode must identify Android (1) or iOS (2)');
    }
    $hotReload = $document['hotReload'] ?? null;
    if (!is_array($hotReload)) {
        throw new InvalidArgumentException('hotReload must be an object');
    }

    $sampleCount = requireNonNegativeInteger($hotReload, 'sampleCount');
    $successfulCount = requireNonNegativeInteger($hotReload, 'successfulCount');
    $failureCount = requireNonNegativeInteger($hotReload, 'failureCount');
    $failureRate = requireNonNegativeInteger($hotReload, 'failureRateBasisPoints');
    $p95Duration = requireNonNegativeInteger($hotReload, 'p95DurationMicros');
    $p95Budget = requirePositiveInteger($hotReload, 'p95BudgetMicros');
    $withinBudget = $hotReload['p95WithinBudget'] ?? null;
    if (!is_bool($withinBudget)) {
        throw new InvalidArgumentException('hotReload.p95WithinBudget must be boolean');
    }
    if ($sampleCount > 64) {
        throw new InvalidArgumentException('hot reload sample count exceeds the 64-sample contract');
    }
    if ($sampleCount !== $successfulCount + $failureCount) {
        throw new InvalidArgumentException('hot reload sample counts are inconsistent');
    }
    $expectedFailureRate = $sampleCount === 0 ? 0 : intdiv($failureCount * 10_000, $sampleCount);
    if ($failureRate !== $expectedFailureRate) {
        throw new InvalidArgumentException('hot reload failure rate is inconsistent');
    }
    if ($withinBudget !== ($p95Duration <= $p95Budget)) {
        throw new InvalidArgumentException('hot reload budget result is inconsistent');
    }
    if ($p95Budget > $maximumBudgetMicros) {
        throw new InvalidArgumentException(
            "hot reload snapshot budget {$p95Budget} µs exceeds CI ceiling {$maximumBudgetMicros} µs",
        );
    }
    if ($sampleCount < $minimumSamples) {
        throw new InvalidArgumentException("hot reload requires at least {$minimumSamples} samples");
    }
    if ($failureCount !== 0) {
        throw new InvalidArgumentException("hot reload contains {$failureCount} failed samples");
    }
    if (!$withinBudget) {
        throw new InvalidArgumentException("hot reload p95 {$p95Duration} µs exceeds {$p95Budget} µs");
    }

    return [
        'sampleCount' => $sampleCount,
        'failureCount' => $failureCount,
        'p95DurationMicros' => $p95Duration,
        'p95BudgetMicros' => $p95Budget,
    ];
}

/** @param array<string, mixed> $object */
function requireEvidenceInteger(array $object, string $field, int $expected): void
{
    $actual = $object[$field] ?? null;
    if (!is_int($actual) || $actual !== $expected) {
        throw new InvalidArgumentException("{$field} must equal {$expected}");
    }
}

/** @param array<string, mixed> $object */
function requireNonNegativeInteger(array $object, string $field): int
{
    $value = $object[$field] ?? null;
    if (!is_int($value) || $value < 0) {
        throw new InvalidArgumentException("hotReload.{$field} must be a non-negative integer");
    }

    return $value;
}

/** @param array<string, mixed> $object */
function requirePositiveInteger(array $object, string $field): int
{
    $value = requireNonNegativeInteger($object, $field);
    if ($value === 0) {
        throw new InvalidArgumentException("hotReload.{$field} must be positive");
    }

    return $value;
}
