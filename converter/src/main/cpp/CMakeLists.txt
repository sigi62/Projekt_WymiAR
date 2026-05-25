cmake_minimum_required(VERSION 3.22)
project(glbconverter)

# CMAKE_SOURCE_DIR = converter/src/main/cpp
# So ../../.. = converter/src/main  →  then jniLibs lives there
set(ASSIMP_LIBS_DIR ${CMAKE_SOURCE_DIR}/../jniLibs)

find_library(log-lib log)

configure_file(
        ${CMAKE_CURRENT_SOURCE_DIR}/include/assimp/config.h.in
        ${CMAKE_CURRENT_SOURCE_DIR}/include/assimp/config.h
)

add_library(
        glbconverter
        SHARED
        glb_converter.cpp
)

# Import the pre-built libassimp.so
add_library(assimp SHARED IMPORTED)
set_target_properties(assimp PROPERTIES
        IMPORTED_LOCATION ${ASSIMP_LIBS_DIR}/${ANDROID_ABI}/libassimp.so
)

target_include_directories(glbconverter PRIVATE
        ${CMAKE_SOURCE_DIR}/include
)

target_link_libraries(glbconverter
        assimp
        ${log-lib}
)

# Copy libassimp.so next to libglbconverter.so in the build output
# so the APK packager picks both up and Android can find libassimp at runtime
add_custom_command(TARGET glbconverter POST_BUILD
        COMMAND ${CMAKE_COMMAND} -E copy_if_different
        ${ASSIMP_LIBS_DIR}/${ANDROID_ABI}/libassimp.so
        $<TARGET_FILE_DIR:glbconverter>/libassimp.so
)