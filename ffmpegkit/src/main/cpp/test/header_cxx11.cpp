// Compiles include/ffmpegkitx.h as strict C++11 (-std=c++11 -pedantic-errors -Wall -Wextra -Werror), for
// C++ bindings such as Node-API addons; the JNI binding includes it as C++17.
#include "ffmpegkitx.h"

int ffkx_header_cxx11_check() {
    ffkx_trim_options options{};
    options.struct_size = sizeof(options);
    return ffkx_abi_is_compatible() && options.struct_size != 0;
}
