cmake_minimum_required(VERSION 3.22)
project(glbconverter)

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

add_custom_command(TARGET glbconverter POST_BUILD
        COMMAND ${CMAKE_COMMAND} -E copy_if_different
        ${ASSIMP_LIBS_DIR}/${ANDROID_ABI}/libassimp.so
        $<TARGET_FILE_DIR:glbconverter>/libassimp.so
)