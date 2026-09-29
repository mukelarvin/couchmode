cmake_minimum_required(VERSION 3.22)
project(gamepad_merger C)

# Standalone executable (not a JNI library) — the app ships it and the root
# script copies it out and runs it. Build with the NDK toolchain, e.g.
#   cmake -S daemon -B build/daemon -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26
add_executable(gamepad_merger gamepad_merger.c)
target_compile_options(gamepad_merger PRIVATE -Wall -Wextra)

# Named lib*.so so Gradle/AGP packages it into the APK's lib/<abi>/ directory.
set_target_properties(gamepad_merger PROPERTIES OUTPUT_NAME gamepad_merger PREFIX "lib" SUFFIX ".so")
