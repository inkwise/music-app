cmake_minimum_required(VERSION 3.22.1)
project("fingerprint_jni")

set(CMAKE_CXX_STANDARD 14)

add_library(fingerprint_jni SHARED fingerprint_jni.cpp)

add_library(chromaprint SHARED IMPORTED)
set_target_properties(chromaprint PROPERTIES
    IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libchromaprint.so)

target_include_directories(fingerprint_jni PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/include)

target_link_libraries(fingerprint_jni
    chromaprint
    android
    log)
