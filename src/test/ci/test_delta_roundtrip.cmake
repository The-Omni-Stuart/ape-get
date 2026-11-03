# delta round-trip gate: the vendored xdelta3 (test-only encoder build)
# produces a real patch; the product decode path must return the target
# bytes exactly. Usage:
#   cmake -DENC=<exe> -DDEC=<exe> -P test_delta_roundtrip.cmake
if(NOT DEFINED ENC OR NOT DEFINED DEC)
  message(FATAL_ERROR "set -DENC and -DDEC")
endif()

set(d "${CMAKE_CURRENT_BINARY_DIR}/delta-roundtrip")
file(REMOVE_RECURSE "${d}")
file(MAKE_DIRECTORY "${d}")

execute_process(COMMAND "${ENC}" "${d}" RESULT_VARIABLE rc)
if(NOT rc EQUAL 0)
  message(FATAL_ERROR "encoder failed (${rc})")
endif()

execute_process(COMMAND "${DEC}" "${d}/src.bin" "${d}/patch.bin" "${d}/out.bin"
                RESULT_VARIABLE rc)
if(NOT rc EQUAL 0)
  message(FATAL_ERROR "product decode failed (${rc})")
endif()

file(SHA256 "${d}/dst.bin" want)
file(SHA256 "${d}/out.bin" got)
if(NOT want STREQUAL got)
  message(FATAL_ERROR "round-trip bytes differ: want ${want} got ${got}")
endif()

# an unrelated source must stop the apply, never emit target bytes: the
# copy windows of a real patch point outside a 1-byte source
file(WRITE "${d}/bad-src.bin" "X")
execute_process(COMMAND "${DEC}" "${d}/bad-src.bin" "${d}/patch.bin"
                "${d}/bad-out.bin" RESULT_VARIABLE rc)
if(rc EQUAL 0)
  message(FATAL_ERROR "apply on a foreign source must fail")
endif()
if(EXISTS "${d}/bad-out.bin")
  message(FATAL_ERROR "failed apply left output behind")
endif()

# multi-window patch (small source windows via the CLI encoder): the
# per-window cap check must not refuse an honest multi-window delta
find_program(XDELTA3_CLI xdelta3)
if(XDELTA3_CLI)
  execute_process(COMMAND ${XDELTA3_CLI} -f -e -S none -B 65536
                  -s "${d}/src.bin" "${d}/dst.bin" "${d}/multi.patch"
                  RESULT_VARIABLE rc)
  if(NOT rc EQUAL 0)
    message(FATAL_ERROR "multi-window encode failed (${rc})")
  endif()
  execute_process(COMMAND "${DEC}" "${d}/src.bin" "${d}/multi.patch"
                  "${d}/multi-out.bin" RESULT_VARIABLE rc)
  if(NOT rc EQUAL 0)
    message(FATAL_ERROR "multi-window decode failed (${rc})")
  endif()
  file(SHA256 "${d}/dst.bin" want)
  file(SHA256 "${d}/multi-out.bin" got)
  if(NOT want STREQUAL got)
    message(FATAL_ERROR "multi-window bytes differ: want ${want} got ${got}")
  endif()
endif()

message(STATUS "delta round-trip ok (${want})")
