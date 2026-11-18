/*
 * Android 7.0/7.1 (API 24/25) support for the prebuilt PHP runtime.
 *
 * libphp.a references mblen() and nl_langinfo(), which bionic only exports
 * from API 26. CMakeLists.txt compiles this file only when the app minSdk is
 * below 26, so builds for API 26+ keep using libc. The bodies mirror bionic's
 * own implementations (bionic/libc/bionic/mblen.cpp and langinfo.cpp): bionic
 * has no locale data, so nl_langinfo() answers for the C/C.UTF-8 locale on
 * every release. Hidden visibility keeps them private to this library.
 */

#include <errno.h>
#include <langinfo.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>

#define PAM_COMPAT __attribute__((visibility("hidden")))

PAM_COMPAT int mblen(const char *s, size_t n)
{
    if (s == NULL) {
        return 0; /* UTF-8 is stateless */
    }
    mbstate_t state;
    memset(&state, 0, sizeof(state));
    size_t result = mbrtowc(NULL, s, n, &state);
    if (result == (size_t)-1 || result == (size_t)-2) {
        errno = EILSEQ;
        return -1;
    }
    return (int)result;
}

PAM_COMPAT char *nl_langinfo(nl_item item)
{
    static const char *const days[] = {
        "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday",
    };
    static const char *const short_days[] = {
        "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat",
    };
    static const char *const months[] = {
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    };
    static const char *const short_months[] = {
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    };
    const char *result = "";
    if (item >= DAY_1 && item <= DAY_7) {
        result = days[item - DAY_1];
    } else if (item >= ABDAY_1 && item <= ABDAY_7) {
        result = short_days[item - ABDAY_1];
    } else if (item >= MON_1 && item <= MON_12) {
        result = months[item - MON_1];
    } else if (item >= ABMON_1 && item <= ABMON_12) {
        result = short_months[item - ABMON_1];
    } else {
        switch (item) {
            case CODESET: result = MB_CUR_MAX == 1 ? "ASCII" : "UTF-8"; break;
            case D_T_FMT: result = "%F %T %z"; break;
            case D_FMT: result = "%F"; break;
            case T_FMT: result = "%T"; break;
            case T_FMT_AMPM: result = "%I:%M:%S %p"; break;
            case AM_STR: result = "AM"; break;
            case PM_STR: result = "PM"; break;
            case RADIXCHAR: result = "."; break;
            case YESEXPR: result = "^[yY]"; break;
            case NOEXPR: result = "^[nN]"; break;
            default: break; /* ERA*, ALT_DIGITS, THOUSEP, CRNCYSTR: "" */
        }
    }
    return (char *)result;
}
