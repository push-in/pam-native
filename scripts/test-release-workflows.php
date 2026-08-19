<?php

declare(strict_types=1);

$root = dirname(__DIR__);

/** @return never */
function fail(string $message): void
{
    fwrite(STDERR, "release-workflows: {$message}\n");
    exit(1);
}

function workflow(string $root, string $name): string
{
    $path = "{$root}/.github/workflows/{$name}";
    $contents = file_get_contents($path);
    if ($contents === false) {
        fail("cannot read {$name}");
    }

    return $contents;
}

function requireFragments(string $workflow, string $name, array $fragments): void
{
    foreach ($fragments as $fragment) {
        if (!str_contains($workflow, $fragment)) {
            fail("{$name} is missing required contract: {$fragment}");
        }
    }
}

/** @param list<string> $names */
function requireBoundedArtifactRetention(string $root, array $names): void
{
    foreach ($names as $name) {
        $contents = workflow($root, $name);
        preg_match_all(
            '/^\s*- uses: actions\/upload-artifact@[^\n]+\n(?<body>(?:(?!^\s*- (?:uses|name):).*(?:\n|$))*)/m',
            $contents,
            $uploads,
        );
        foreach ($uploads['body'] as $body) {
            if (preg_match('/^\s+retention-days: (\d+)$/m', $body, $retention) !== 1) {
                fail("{$name} contains an artifact without explicit retention");
            }
            $days = (int) $retention[1];
            if ($days < 1 || $days > 30) {
                fail("{$name} artifact retention must be between 1 and 30 days");
            }
            if (str_contains($body, 'prerequisites') && $days !== 1) {
                fail("{$name} transient prerequisites must retain for 1 day");
            }
        }
    }
}

$ci = workflow($root, 'ci.yml');
$android = workflow($root, 'ecosystem-android.yml');
$ios = workflow($root, 'ecosystem-ios.yml');
$release = workflow($root, 'release.yml');

requireFragments($ci, 'ci.yml', ["  workflow_call:\n"]);
requireFragments($android, 'ecosystem-android.yml', [
    "  workflow_call:\n",
    "      - android/**\n",
    "      - crates/**\n",
    "      - native/**\n",
    "      - packages/native/**\n",
]);
requireFragments($ios, 'ecosystem-ios.yml', [
    "  workflow_call:\n",
    "      - ios/**\n",
    "      - packages/native/**\n",
]);
requireFragments($release, 'release.yml', [
    "  source-contracts:\n",
    "    uses: ./.github/workflows/ci.yml\n",
    "  ecosystem-android:\n",
    "    uses: ./.github/workflows/ecosystem-android.yml\n",
    "  ecosystem-ios:\n",
    "    uses: ./.github/workflows/ecosystem-ios.yml\n",
    "      - source-contracts\n",
    "      - ecosystem-compatibility\n",
    "      - ecosystem-android\n",
    "      - ecosystem-ios\n",
]);
requireBoundedArtifactRetention($root, ['ci.yml', 'release.yml']);

echo "PAM Native release workflow contracts passed.\n";
