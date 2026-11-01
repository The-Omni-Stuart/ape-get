# Smoke: `ape-get` with no arguments prints help and exits 0.
execute_process(COMMAND ${exe} RESULT_VARIABLE rc OUTPUT_VARIABLE out)
if(NOT rc EQUAL 0)
  message(FATAL_ERROR "no-arg run exited ${rc}, want 0 (help)")
endif()
if(NOT out MATCHES "Usage: ")
  message(FATAL_ERROR "no-arg run printed no usage text")
endif()
