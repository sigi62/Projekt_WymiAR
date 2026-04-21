cmake_minimum_required(VERSION 3.22)
project(glbconverter)

# Path to your project-level jniLibs folder
set(ASSIMP_LIBS_DIR ${CMAKE_SOURCE_DIR}/../../../../jniLibs)

find_library(log-lib log)

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
        ${CMAKE_SOURCE_DIR}/include   # assimp headers
)

target_link_libraries(glbconverter
        assimp
        ${log-lib}
)