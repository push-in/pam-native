#!/usr/bin/env php
<?php

declare(strict_types=1);

require __DIR__.'/lib/hot-reload-evidence.php';

/** @return never */
function hotReloadEvidenceUsage(): void
{
    fwrite(STDERR, "Usage: scripts/check-hot-reload-evidence.php SNAPSHOT.json [MINIMUM_SAMPLES] [MAXIMUM_P95_MS]\n");
    exit(2);
}

if ($argc < 2 || $argc > 4) {
    hotReloadEvidenceUsage();
}
$path = $argv[1];
$minimumSamples = $argc >= 3 ? filter_var($argv[2], FILTER_VALIDATE_INT) : 20;
$maximumP95Ms = $argc === 4 ? filter_var($argv[3], FILTER_VALIDATE_INT) : 1_000;
if (
    !is_int($minimumSamples)
    || $minimumSamples < 1
    || $minimumSamples > 64
    || !is_int($maximumP95Ms)
    || $maximumP95Ms < 1
    || $maximumP95Ms > 60_000
) {
    hotReloadEvidenceUsage();
}
if (is_link($path) || !is_file($path)) {
    fwrite(STDERR, "hot-reload-evidence: snapshot must be a regular non-symlink file\n");
    exit(1);
}
$size = filesize($path);
if (!is_int($size) || $size > 1_048_576) {
    fwrite(STDERR, "hot-reload-evidence: snapshot exceeds the 1 MiB limit\n");
    exit(1);
}
$contents = file_get_contents($path, false, null, 0, 1_048_577);
if ($contents === false) {
    fwrite(STDERR, "hot-reload-evidence: cannot read snapshot\n");
    exit(1);
}

try {
    $result = verifyHotReloadEvidence($contents, $minimumSamples, $maximumP95Ms * 1_000);
} catch (InvalidArgumentException $error) {
    fwrite(STDERR, "hot-reload-evidence: {$error->getMessage()}\n");
    exit(1);
}

printf(
    "Hot reload evidence passed: %d samples, p95 %.1f ms / %.1f ms budget.\n",
    $result['sampleCount'],
    $result['p95DurationMicros'] / 1_000,
    $result['p95BudgetMicros'] / 1_000,
);
