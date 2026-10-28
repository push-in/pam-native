// Host PHP extension wrapper around android/app/src/main/cpp/pam_tree_encoder.cpp.
#include "pam_tree_encoder.h"

extern "C" {
#include "php.h"
}

static PHP_RSHUTDOWN_FUNCTION(pam_tree_encoder) {
    pam_tree_encoder_shutdown();
    return SUCCESS;
}

zend_module_entry pam_tree_encoder_module_entry = {
    STANDARD_MODULE_HEADER,
    "pam_tree_encoder",
    pam_tree_encoder_functions,
    nullptr,
    nullptr,
    nullptr,
    PHP_RSHUTDOWN(pam_tree_encoder),
    nullptr,
    "1.0",
    STANDARD_MODULE_PROPERTIES,
};

extern "C" {
ZEND_GET_MODULE(pam_tree_encoder)
}
