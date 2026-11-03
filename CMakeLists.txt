cmake_minimum_required(VERSION 3.20)
project(ape_get C)

# One static library per module, one `ape-get` executable, sizes gated per
# release. A module with no sources yet builds as an empty archive.

if(NOT CMAKE_TOOLCHAIN_FILE)
  message(FATAL_ERROR "configure with -DCMAKE_TOOLCHAIN_FILE=toolchain/cosmo.cmake")
endif()

set(CMAKE_C_STANDARD 17)
set(CMAKE_C_STANDARD_REQUIRED ON)

# Self-integration: ON for direct releases; packagers (rpm/nix/brew) build
# -DAPEGET_SELF=OFF and the mechanism is absent from the binary.
option(APEGET_SELF "Compile self-integration in " ON)
set(APEGET_VERSION "2.0.0" CACHE STRING
    "Version baked into the self manifest ")
if(APEGET_SELF)
  add_compile_definitions(APEGET_SELF=1)
endif()
add_compile_definitions(APEGET_VERSION="${APEGET_VERSION}")

file(READ ${CMAKE_SOURCE_DIR}/src/main/deps.mk DEPS_MK)

# Vendored mbed TLS 3.6.x (PIN file), trimmed by toolchain/mbedtls_apeget.h
# to a TLS 1.2 client. Only `crypto` may link this.
file(GLOB MBED_SRCS ${CMAKE_SOURCE_DIR}/vendor/mbedtls/library/*.c)
# error.c is the full mbedtls_strerror string table (~14 KB/arch retained);
# crypto.c carries a compact mapper for the reachable codes instead.
list(FILTER MBED_SRCS EXCLUDE REGEX "/error\\.c$")
add_library(mbedall STATIC ${MBED_SRCS})
target_include_directories(mbedall PUBLIC ${CMAKE_SOURCE_DIR}/vendor/mbedtls/include)
target_include_directories(mbedall PRIVATE ${CMAKE_SOURCE_DIR}/vendor/mbedtls/library)
target_compile_definitions(mbedall PUBLIC
  MBEDTLS_CONFIG_FILE="${CMAKE_SOURCE_DIR}/toolchain/mbedtls_apeget.h")
target_compile_options(mbedall PRIVATE -w)

# Vendored liblzma 5.8.4 decoder subset (PIN file): .xz container,
# LZMA1+LZMA2, small CRC32/CRC64/SHA-256 checks. No encoders at all.
file(GLOB LZMA_SRCS ${CMAKE_SOURCE_DIR}/vendor/liblzma/lib/*.c)
add_library(lzmadec STATIC ${LZMA_SRCS})
target_include_directories(lzmadec PUBLIC ${CMAKE_SOURCE_DIR}/vendor/liblzma/include)
target_include_directories(lzmadec PRIVATE ${CMAKE_SOURCE_DIR}/vendor/liblzma/lib
  ${CMAKE_SOURCE_DIR}/vendor/liblzma/tuklib)
target_compile_options(lzmadec PRIVATE -w
  -include ${CMAKE_SOURCE_DIR}/toolchain/lzma_apeget.h)

# Vendored xdelta3 3.2.2 decoder subset (PIN file): VCDIFF decode only;
# the encoder, secondary compressors, CLI and tests compile out or are
# not vendored (toolchain/xdelta3_apeget.h).
add_library(xdelta3dec STATIC ${CMAKE_SOURCE_DIR}/vendor/xdelta3/xdelta3.c)
target_include_directories(xdelta3dec PUBLIC ${CMAKE_SOURCE_DIR}/vendor/xdelta3)
# the -include is INTERFACE so consumers that include xdelta3.h get the
# same config macros the header probes (liblzma needs no such thing;
# xdelta3.h checks SIZEOF_* at include time)
target_compile_options(xdelta3dec PRIVATE -w
  -include ${CMAKE_SOURCE_DIR}/toolchain/xdelta3_apeget.h)
target_compile_options(xdelta3dec INTERFACE
  -include ${CMAKE_SOURCE_DIR}/toolchain/xdelta3_apeget.h)
string(REGEX REPLACE "\n" ";" DEPS_LINES "${DEPS_MK}")
set(APESRC_MODULES "")
foreach(line ${DEPS_LINES})
  string(REGEX REPLACE "#.*" "" line "${line}")
  string(REGEX MATCH "^ *([a-z_]+):" hit "${line}")
  if(hit)
    list(APPEND APESRC_MODULES "${CMAKE_MATCH_1}")
  endif()
endforeach()

set(APESRC_LIBS "")
foreach(mod ${APESRC_MODULES})
  if(mod STREQUAL "main")
    continue()
  endif()
  # Module = flat src/main/<mod>.c plus optional src/main/<mod>/*.c parts;
  # CONFIGURE_DEPENDS re-runs the glob as sources appear between configures.
  file(GLOB mod_sources CONFIGURE_DEPENDS
       ${CMAKE_SOURCE_DIR}/src/main/${mod}.c
       ${CMAKE_SOURCE_DIR}/src/main/${mod}/*.c)
  if(NOT mod_sources)
    # modules gain sources test-first; an empty module is a valid empty
    # archive so the DAG and link structure exist before behaviour does
    set(mod_sources ${CMAKE_BINARY_DIR}/gen/${mod}_stub.c)
    file(WRITE ${mod_sources} "/* placeholder: no sources yet */\n")
  endif()
  add_library(m_${mod} STATIC ${mod_sources})
  target_include_directories(m_${mod} PUBLIC ${CMAKE_SOURCE_DIR}/src/main)
  list(APPEND APESRC_LIBS m_${mod})
endforeach()

# dispatch lives in main.c; command bodies in main/*.c
file(GLOB MAIN_PARTS CONFIGURE_DEPENDS ${CMAKE_SOURCE_DIR}/src/main/main/*.c)
add_executable(ape-get ${CMAKE_SOURCE_DIR}/src/main/main.c ${MAIN_PARTS})

# the one module allowed to consume mbed TLS (crypto row)
target_link_libraries(m_crypto PUBLIC mbedall)

# the one module allowed to consume liblzma and xdelta3 (the decode stage)
target_link_libraries(m_pipeline PUBLIC lzmadec xdelta3dec)

# Embedded trust store: the CA bundle compiles into http;
# $APEGET_CA_BUNDLE overrides it at runtime.
find_program(PYTHON3_EMBED python3 REQUIRED)
set(CA_GEN ${CMAKE_BINARY_DIR}/gen/bundled_ca.c)
execute_process(COMMAND ${PYTHON3_EMBED} ${CMAKE_SOURCE_DIR}/toolchain/embed_ca.py
  ${CMAKE_SOURCE_DIR}/vendor/ca-bundle/cacert.pem ${CA_GEN}
  COMMAND_ERROR_IS_FATAL ANY)
target_sources(m_http PRIVATE ${CA_GEN})
target_link_libraries(m_http PUBLIC m_crypto)

# --no-undefined everywhere: an undeclared-but-used symbol fails the link.
target_link_options(ape-get PRIVATE -Wl,--no-undefined)
# deps.mk order is topological for headers, but static archives may reference
# each other both ways; the link group resolves the closure in any order.
target_link_libraries(ape-get PRIVATE LINKER:--start-group ${APESRC_LIBS} LINKER:--end-group)

# Packaging: per-arch ELF -> apelink with bundled loaders; bin/package.sh
# drives the three artefacts, CI gates the sizes (1.5 MB fat / 1 MB single).

enable_testing()
find_program(PYTHON3 python3 REQUIRED)
add_test(NAME deps_graph_check
         COMMAND ${PYTHON3} ${CMAKE_SOURCE_DIR}/src/test/ci/check_deps_graph.py
                 ${CMAKE_SOURCE_DIR}/src/main/deps.mk)
add_test(NAME free_order_check
         COMMAND ${PYTHON3} ${CMAKE_SOURCE_DIR}/src/test/ci/check_free_order.py
                 ${CMAKE_SOURCE_DIR}/src/main)
add_test(NAME provenance_check
         COMMAND ${PYTHON3} ${CMAKE_SOURCE_DIR}/src/test/ci/check_provenance.py)
add_test(NAME smoke_help
         COMMAND ${CMAKE_COMMAND}
           -Dexe=$<TARGET_FILE:ape-get>
           -P ${CMAKE_SOURCE_DIR}/src/test/ci/test_help.cmake)
add_test(NAME completions_check
         COMMAND ${CMAKE_COMMAND}
           -Dexe=$<TARGET_FILE:ape-get>
           -Dsrcdir=${CMAKE_SOURCE_DIR}
           -P ${CMAKE_SOURCE_DIR}/src/test/ci/test_completions.cmake)
add_test(NAME completions_pty_check
         COMMAND ${PYTHON3} ${CMAKE_SOURCE_DIR}/src/test/ci/test_completions_pty.py
                 $<TARGET_FILE:ape-get>)

# delta round-trip: vendored xdelta3 encoder (test-only build; never
# linked into the product) -> product decode path (xdelta_apply_fd).
add_library(xdelta3enc STATIC ${CMAKE_SOURCE_DIR}/vendor/xdelta3/xdelta3.c)
target_include_directories(xdelta3enc PUBLIC ${CMAKE_SOURCE_DIR}/vendor/xdelta3)
target_compile_options(xdelta3enc PRIVATE -w
  -include ${CMAKE_SOURCE_DIR}/toolchain/xdelta3_enctest.h)
add_executable(rt_xdelta_enc ${CMAKE_SOURCE_DIR}/src/test/ci/test_delta_rt_enc.c)
target_link_libraries(rt_xdelta_enc PRIVATE xdelta3enc)
target_compile_options(rt_xdelta_enc PRIVATE -w
  -include ${CMAKE_SOURCE_DIR}/toolchain/xdelta3_enctest.h)
add_executable(rt_xdelta_dec ${CMAKE_SOURCE_DIR}/src/test/ci/test_delta_rt_dec.c)
target_link_libraries(rt_xdelta_dec PRIVATE m_pipeline)
add_test(NAME delta_roundtrip
         COMMAND ${CMAKE_COMMAND}
           -DENC=$<TARGET_FILE:rt_xdelta_enc>
           -DDEC=$<TARGET_FILE:rt_xdelta_dec>
           -P ${CMAKE_SOURCE_DIR}/src/test/ci/test_delta_roundtrip.cmake)
