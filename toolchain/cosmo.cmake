# cosmocc toolchain file. COSMO_DIR overrides the install path.
set(COSMO_DIR "$ENV{COSMO_DIR}" CACHE PATH "Cosmopolitan 4.0.2 install prefix")
set(APEGET_ARCH "x86_64" CACHE STRING "x86_64 | aarch64 (ELF target for apelink)")
if(NOT COSMO_DIR)
  set(COSMO_DIR "$ENV{HOME}/.local/lib/cosmopolitan")
endif()
if(NOT EXISTS "${COSMO_DIR}/bin/cosmocc")
  message(FATAL_ERROR "cosmocc not found under ${COSMO_DIR} (see toolchain/PIN)")
endif()

set(CMAKE_SYSTEM_NAME Generic)
set(CMAKE_SYSTEM_PROCESSOR ${APEGET_ARCH})
# Explicit per-arch driver, never bare cosmocc: fat mode demands per-arch twins
# for every input archive; universality is the apelink step (bin/package.sh).
set(CMAKE_C_COMPILER "${COSMO_DIR}/bin/${APEGET_ARCH}-unknown-cosmo-cc")
set(CMAKE_C_COMPILER_ID Clang)
set(CMAKE_TRY_COMPILE_TARGET_TYPE STATIC_LIBRARY)
set(CMAKE_C_ARCHIVE_CREATE "<CMAKE_AR> qc <TARGET> <LINK_FLAGS> <OBJECTS>")
set(CMAKE_C_ARCHIVE_APPEND "<CMAKE_AR> q <TARGET> <LINK_FLAGS> <OBJECTS>")
set(CMAKE_C_ARCHIVE_FINISH "<CMAKE_RANLIB> <TARGET>")

# -mtiny explicit so the cache can't disagree with the env; -Os always.
# gc-sections keeps the fat APE inside the size gates (uncalled mbed TLS code).
set(CMAKE_C_FLAGS_INIT "-Os -mtiny -std=c17 -Wall -Wextra -Werror -ffunction-sections -fdata-sections")
set(CMAKE_EXE_LINKER_FLAGS_INIT "-Os -mtiny -Wl,--gc-sections")
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)
