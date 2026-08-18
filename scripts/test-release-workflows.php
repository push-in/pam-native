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

echo "PAM Native release workflow contracts passed.\n";
