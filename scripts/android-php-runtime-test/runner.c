/*
 * Minimal host for PAM's Android PHP runtime (libphp.a, embed SAPI): runs the
 * PHP file given as the first argument and exits with its status. Used by
 * scripts/android-php-runtime-test.sh to execute SDK tests on a device.
 */
#include <sapi/embed/php_embed.h>

#include <stdlib.h>
#include <string.h>

/* Android has no /tmp; OPcache needs a writable directory for its lock file. */
static void pam_ini_defaults(HashTable *configuration)
{
    const char *temporary = getenv("TMPDIR");
    zval value;

    if (temporary == NULL || temporary[0] == '\0') {
        temporary = "/data/local/tmp";
    }
    ZVAL_NEW_STR(&value, zend_string_init(temporary, strlen(temporary), 1));
    zend_hash_str_update(configuration, "opcache.lockfile_path", sizeof("opcache.lockfile_path") - 1, &value);
}

int main(int argc, char **argv)
{
    int status = 2;

    if (argc < 2) {
        return 64;
    }
    php_embed_module.ini_defaults = pam_ini_defaults;

    PHP_EMBED_START_BLOCK(argc, argv)
        zend_file_handle script;
        zend_stream_init_filename(&script, argv[1]);
        status = php_execute_script(&script) ? EG(exit_status) : 1;
        zend_destroy_file_handle(&script);
    PHP_EMBED_END_BLOCK()

    return status;
}
