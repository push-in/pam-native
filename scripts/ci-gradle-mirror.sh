#!/usr/bin/env bash
# usage: scripts/ci-gradle-mirror.sh [gradle-user-home]
# Routes Maven Central through Google's public mirror for CI Gradle builds.
# Release runs start dozens of jobs at once, each resolving the same
# artifacts; repo.maven.apache.org answers them 429 for minutes, longer than
# any retry. The mirror serves the same artifacts (checksums unchanged).
set -euo pipefail
# Gradle's own home and the one `pam-native` gives Android builds.
homes=("$@")
if ((${#homes[@]} == 0)); then
  homes=("${GRADLE_USER_HOME:-${HOME}/.gradle}" "${PAM_NATIVE_GRADLE_HOME:-${XDG_CACHE_HOME:-${HOME}/.cache}/pam-native/gradle}")
fi
for home in "${homes[@]}"; do
mkdir -p "${home}/init.d"
cat >"${home}/init.d/pam-maven-central-mirror.gradle" <<'GRADLE'
def mirror = 'https://maven-central.storage-download.googleapis.com/maven2/'
def central = { repo ->
    repo instanceof MavenArtifactRepository &&
        repo.url.toString().replaceAll('/+$', '') == 'https://repo.maven.apache.org/maven2'
}
def redirect = { repositories ->
    repositories.configureEach { repo -> if (central(repo)) repo.url = mirror }
}
beforeSettings { settings ->
    redirect(settings.buildscript.repositories)
    redirect(settings.pluginManagement.repositories)
    redirect(settings.dependencyResolutionManagement.repositories)
}
allprojects {
    redirect(buildscript.repositories)
    redirect(repositories)
}
GRADLE
echo "Maven Central mirror configured in ${home}/init.d"
done
