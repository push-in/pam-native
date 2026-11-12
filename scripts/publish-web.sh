#!/usr/bin/env bash
# Publishes packages/web as github.com/push-in/pam-native-web (pushinbr/pam-native-web)
# at the given version: splits the directory history, drops the monorepo path
# repository from composer.json, tags and pushes. Run from main after a release.
#
#   scripts/publish-web.sh 1.49.0
set -euo pipefail

version=${1:?usage: scripts/publish-web.sh <version>}
remote=${PAM_NATIVE_WEB_REMOTE:-git@github.com:push-in/pam-native-web.git}
root=$(git rev-parse --show-toplevel)
work=$(mktemp -d)
trap 'rm -rf "$work"; git -C "$root" branch -D pam-native-web-split >/dev/null 2>&1 || true' EXIT

git -C "$root" subtree split -q --prefix packages/web -b pam-native-web-split
git clone -q --no-tags --branch pam-native-web-split "$root" "$work/web"
cd "$work/web"
php -r '
    $file = "composer.json";
    $json = json_decode(file_get_contents($file), true);
    unset($json["repositories"]);
    file_put_contents($file, json_encode($json, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES)."\n");
'
git rm -q --cached composer.lock 2>/dev/null || true
rm -f composer.lock
printf 'vendor/\ncomposer.lock\n.phpunit.result.cache\n' > .gitignore
git add -A
git commit -qm "Release ${version}" || true
git tag -f "v${version}"
git push -q --force "$remote" HEAD:main
git push -q --force "$remote" "v${version}"
echo "Published pushinbr/pam-native-web v${version} to ${remote}"
