// Native tree encoder functions registered into the embedded PHP runtime.
#pragma once

#include "php.h"

extern const zend_function_entry pam_tree_encoder_functions[];

// Releases every encoder. Call while the PHP request is still alive (before
// php_request_shutdown()/php_embed_shutdown()): it releases PHP values.
void pam_tree_encoder_shutdown();
