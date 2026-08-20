<?php

declare(strict_types=1);

require __DIR__.'/lib/hot-reload-evidence.php';

function snapshot(array $overrides = [], int $platformCode = 1): string
{
    return json_encode([
        'schemaVersion' => 1,
        'surfaceCode' => 2,
        'platformCode' => $platformCode,
        'hotReload' => array_replace([
            'sampleCount' => 20,
            'successfulCount' => 20,
            'failureCount' => 0,
            'failureRateBasisPoints' => 0,
            'p95DurationMicros' => 750_000,
            'p95BudgetMicros' => 1_000_000,
            'p95WithinBudget' => true,
        ], $overrides),
    ], JSON_THROW_ON_ERROR);
}

function expectFailure(string $contents, string $fragment): void
{
    try {
        verifyHotReloadEvidence($contents, 20);
    } catch (InvalidArgumentException $error) {
        if (str_contains($error->getMessage(), $fragment)) {
            return;
        }
        throw new RuntimeException("unexpected failure: {$error->getMessage()}");
    }
    throw new RuntimeException("expected failure containing: {$fragment}");
}

$result = verifyHotReloadEvidence(snapshot(), 20);
if ($result['p95DurationMicros'] !== 750_000) {
    throw new RuntimeException('valid p95 was not preserved');
}
$iosResult = verifyHotReloadEvidence(snapshot([], 2), 20);
if ($iosResult['sampleCount'] !== 20) {
    throw new RuntimeException('valid iOS sample count was not preserved');
}
expectFailure(snapshot([], 3), 'platformCode must identify Android (1) or iOS (2)');
expectFailure(snapshot(['sampleCount' => 19, 'successfulCount' => 19]), 'at least 20');
expectFailure(snapshot([
    'successfulCount' => 19,
    'failureCount' => 1,
    'failureRateBasisPoints' => 500,
]), 'failed samples');
expectFailure(snapshot([
    'p95DurationMicros' => 1_000_001,
    'p95WithinBudget' => false,
]), 'exceeds');
expectFailure(snapshot(['p95WithinBudget' => false]), 'budget result is inconsistent');
expectFailure(snapshot([
    'p95BudgetMicros' => 1_000_001,
]), 'exceeds CI ceiling');
expectFailure(snapshot([
    'sampleCount' => 65,
    'successfulCount' => 65,
]), '64-sample contract');
expectFailure('{', 'not valid JSON');

echo "Hot reload evidence verifier contracts passed.\n";
