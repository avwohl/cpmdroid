cmake_minimum_required(VERSION 3.22.1)
project("cpmdroid")

# Path to shared emulator source files
# From cpp/ go up 5 levels to src/, then into sibling directories
set(ROMWBW_EMU_SRC "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../romwbw_emu/src")
set(CPMEMU_SRC "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../cpmemu/src")

# Verify paths exist
if(NOT EXISTS "${ROMWBW_EMU_SRC}")
    message(FATAL_ERROR "romwbw_emu source not found at ${ROMWBW_EMU_SRC}")
endif()

if(NOT EXISTS "${CPMEMU_SRC}")
    message(FATAL_ERROR "cpmemu source not found at ${CPMEMU_SRC}")
endif()

# Build the native library
add_library(${CMAKE_PROJECT_NAME} SHARED
    # Android-specific JNI bridge
    emu_io_android.cpp

    # Shared emulator core from romwbw_emu
    ${ROMWBW_EMU_SRC}/hbios_dispatch.cc
    ${ROMWBW_EMU_SRC}/hbios_cpu.cc
    ${ROMWBW_EMU_SRC}/emu_init.cc

    # CPU core from cpmemu (portable Z80 implementation)
    ${CPMEMU_SRC}/qkz80.cc
    ${CPMEMU_SRC}/qkz80_reg_set.cc
    ${CPMEMU_SRC}/qkz80_mem.cc
    ${CPMEMU_SRC}/qkz80_errors.cc
)

# Include directories
target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${ROMWBW_EMU_SRC}
    ${CPMEMU_SRC}
)

# Compiler flags
target_compile_options(${CMAKE_PROJECT_NAME} PRIVATE
    -Wall
    -Wextra
    -fvisibility=hidden
)

# Link libraries
target_link_libraries(${CMAKE_PROJECT_NAME}
    android
    log
)
