# cosmocc toolchain file. COSMO_DIR overrides the install path.
set(COSMO_DIR "$ENV{COSMO_DIR}" CACHE PATH "Cosmopolitan 4.0.2 install prefix")
set(APEGET_ARCH "x86_64" CACHE STRING "x86_64 | aarch64 (ELF target for apelink)")
list(APPEND CMAKE_TRY_COMPILE_PLATFORM_VARIABLES COSMO_DIR APEGET_ARCH)
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
# Host archive tools cannot index the target's ELF objects on macOS; the
# cosmo tools are APE executables, which CMake's link-script execve can't
# run without a shell, hence the /bin/sh launcher (same reason package.sh
# shell-launches apelink). Linux keeps its native binutils ar/ranlib.
if(CMAKE_HOST_APPLE)
  set(CMAKE_AR "${COSMO_DIR}/bin/${APEGET_ARCH}-linux-cosmo-ar" CACHE FILEPATH "Cosmo archiver" FORCE)
  set(CMAKE_RANLIB "${COSMO_DIR}/bin/${APEGET_ARCH}-linux-cosmo-ranlib" CACHE FILEPATH "Cosmo archive indexer" FORCE)
  set(CMAKE_C_ARCHIVE_CREATE "/bin/sh <CMAKE_AR> qc <TARGET> <LINK_FLAGS> <OBJECTS>")
  set(CMAKE_C_ARCHIVE_APPEND "/bin/sh <CMAKE_AR> q <TARGET> <LINK_FLAGS> <OBJECTS>")
  set(CMAKE_C_ARCHIVE_FINISH "/bin/sh <CMAKE_RANLIB> <TARGET>")
else()
  # CMake's tool probes discover cosmo's APE ar in PATH; those can't be
  # execve'd here (no binfmt), so pin native binutils explicitly
  set(CMAKE_AR "/usr/bin/ar" CACHE FILEPATH "native archiver" FORCE)
  set(CMAKE_RANLIB "/usr/bin/ranlib" CACHE FILEPATH "native archive indexer" FORCE)
  set(CMAKE_C_ARCHIVE_CREATE "<CMAKE_AR> qc <TARGET> <LINK_FLAGS> <OBJECTS>")
  set(CMAKE_C_ARCHIVE_APPEND "<CMAKE_AR> q <TARGET> <LINK_FLAGS> <OBJECTS>")
  set(CMAKE_C_ARCHIVE_FINISH "<CMAKE_RANLIB> <TARGET>")
endif()

# -mtiny explicit so the cache can't disagree with the env; -Os always.
# gc-sections keeps the fat APE inside the size gates (uncalled mbed TLS code).
set(CMAKE_C_FLAGS_INIT "-Os -mtiny -std=c17 -Wall -Wextra -Werror -ffunction-sections -fdata-sections")
set(CMAKE_EXE_LINKER_FLAGS_INIT "-Os -mtiny -Wl,--gc-sections")
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)
