dnl Host build of the native tree encoder (Android/iOS bridge source) as a
dnl PHP extension, only for scripts/native-encoder-test.sh.
PHP_ARG_ENABLE([pam_tree_encoder], [whether to enable the PAM native tree encoder], [--enable-pam_tree_encoder])
if test "$PHP_PAM_TREE_ENCODER" != "no"; then
  PHP_REQUIRE_CXX()
  PHP_ADD_LIBRARY(stdc++, 1, PAM_TREE_ENCODER_SHARED_LIBADD)
  PHP_SUBST(PAM_TREE_ENCODER_SHARED_LIBADD)
  PHP_NEW_EXTENSION(pam_tree_encoder, pam_tree_encoder_ext.cpp pam_tree_encoder.cpp, $ext_shared,, -std=c++20)
fi
