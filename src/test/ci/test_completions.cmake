# Programmable-completion check: generated scripts sourced against a stub
# ape-get, COMP_WORDS fed in, COMPREPLY asserted (test_completions.sh).
if(NOT DEFINED exe OR NOT DEFINED srcdir)
  message(FATAL_ERROR "usage: -Dexe=<ape-get> -Dsrcdir=<repo root>")
endif()
get_filename_component(bindir "${exe}" DIRECTORY)
set(work "${bindir}/completions-test")
execute_process(
  COMMAND bash "${srcdir}/src/test/ci/test_completions.sh" "${exe}" "${work}" "${srcdir}"
  RESULT_VARIABLE rc OUTPUT_VARIABLE out ERROR_VARIABLE err)
if(NOT rc EQUAL 0)
  message(FATAL_ERROR "completion checks failed (rc=${rc}):\n${out}${err}")
endif()
