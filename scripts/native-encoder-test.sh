#!/usr/bin/env bash
# Builds the native tree encoder as a host PHP extension (phpize) and runs
# the PHP/native equivalence suite plus the encoder tests with it loaded.
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
cp "$root/scripts/native-encoder-ext/"* "$work/"
cp "$root/android/app/src/main/cpp/pam_tree_encoder.cpp" "$root/android/app/src/main/cpp/pam_tree_encoder.h" "$work/"
(cd "$work" && phpize >/dev/null && ./configure --enable-pam_tree_encoder >/dev/null && make -j4 >/dev/null)
php=${PHP:-php}
"$php" -d extension="$work/modules/pam_tree_encoder.so" -d memory_limit=2G \
  "$root/packages/native/tests/native_encoder_equivalence.php"
"$php" -d extension="$work/modules/pam_tree_encoder.so" "$root/packages/native/tests/native_style_equivalence.php"
for test in encoder_patches; do
  PAM_NATIVE_ENCODER=native "$php" -d extension="$work/modules/pam_tree_encoder.so" -d memory_limit=1G \
    "$root/packages/native/tests/$test.php"
done
# Extension IDs the renderer contract does not declare are refused natively too.
cat > "$work/autoload.php" <<PHP
<?php
spl_autoload_register(static function (string \$class): void {
    if (str_starts_with(\$class, 'Pam\\\\Native\\\\')) {
        \$path = '$root/packages/native/src/'.str_replace('\\\\', '/', substr(\$class, 11)).'.php';
        if (is_file(\$path)) require \$path;
    }
});
PHP
"$php" -d extension="$work/modules/pam_tree_encoder.so" -d auto_prepend_file="$work/autoload.php" \
  "$root/packages/native/tests/protocol_extension.php"
echo "NATIVE_PROTOCOL_EXTENSION OK"
