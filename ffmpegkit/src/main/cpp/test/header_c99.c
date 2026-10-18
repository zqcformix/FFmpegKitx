/* Compiles include/ffmpegkitx.h as strict C99 (-std=c99 -pedantic-errors -Wall -Wextra -Werror): the oldest
   C a binding may use, and the language Swift's Clang importer and Node-API addons read it as. */
#include "ffmpegkitx.h"

int ffkx_header_c99_check(void) {
    ffkx_trim_options options = {.struct_size = sizeof(ffkx_trim_options)};
    return ffkx_abi_is_compatible() && options.struct_size != 0;
}
