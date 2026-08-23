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
$androidBuild = file_get_contents("{$root}/android/build.gradle.kts");
if ($androidBuild === false) {
    fail('cannot read android/build.gradle.kts');
}

requireFragments($ci, 'ci.yml', [
    "  workflow_call:\n",
    "php scripts/test-hot-reload-evidence.php\n",
    "python3 -m unittest benchmarks/package/test_reproducibility.py\n",
    "python3 -m json.tool benchmarks/package/reproducibility.schema.json >/dev/null\n",
]);
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
    "          git archive \\\n",
    "              --mtime=\"@\${source_date_epoch}\" \\\n",
    "            gzip -n \"\${output%.gz}\"\n",
    "          cmp \"dist/\${artifact}\" \"\${RUNNER_TEMP}/\${artifact}\"\n",
    "            :plugin-api:clean \\\n",
    "          cmp \"\${artifact}\" android/plugin-api/build/outputs/aar/plugin-api-release.aar\n",
    "            --output \"dist/pam-native-ios-\${version}.reproducibility.json\"\n",
    "            --output \"dist/pam-native-android-\${version}.reproducibility.json\"\n",
    "            --output \"dist/pam-native-php-\${version}.reproducibility.json\"\n",
    "      - name: Reverify downloaded reproducibility evidence\n",
    "      - name: Generate SPDX 2.3 software bill of materials\n",
    "        run: rm -f dist/Info.plist dist/pam-native-android-prerequisites.tar.gz\n",
    "            sha256sum \"\${artifact}\" > \"\${artifact}.sha256\"\n",
    "            --created-epoch \"$(git log -1 --format=%ct)\" \\\n",
    "        uses: actions/attest-sbom@v4\n",
]);
if (substr_count($release, 'cmp "dist/${artifact}" "${RUNNER_TEMP}/${artifact}"') !== 3) {
    fail('release.yml must verify iOS, Android renderer, and PHP SDK archives byte for byte');
}
$pluginRebuild = strpos($release, ':plugin-api:clean');
$pluginChecksum = strpos($release, 'sha256sum "pam-native-android-plugin-api');
if ($pluginRebuild === false || $pluginChecksum === false || $pluginRebuild > $pluginChecksum) {
    fail('release.yml must prove plugin API reproducibility before writing its checksum');
}
requireFragments($androidBuild, 'android/build.gradle.kts', [
    "tasks.withType<AbstractArchiveTask>().configureEach {\n",
    "        isPreserveFileTimestamps = false\n",
    "        isReproducibleFileOrder = true\n",
]);
requireBoundedArtifactRetention($root, ['ci.yml', 'release.yml']);

echo "PAM Native release workflow contracts passed.\n";
