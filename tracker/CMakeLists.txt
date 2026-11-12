cmake_minimum_required(VERSION 3.22)
project(luxin_tracker VERSION 0.1.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

option(LUXIN_BUILD_TESTS "Build desktop unit tests and CLI" ON)

if(ANDROID)
    find_package(OpenCV REQUIRED CONFIG)
    set(OpenCV_LIBS OpenCV::opencv_java4)
    set(OpenCV_INCLUDE_DIRS "")
else()
    find_package(OpenCV QUIET COMPONENTS core imgproc features2d flann calib3d imgcodecs)
    if(NOT OpenCV_FOUND)
        find_path(OPENCV_INCLUDE_DIR NAMES opencv2/core.hpp PATH_SUFFIXES opencv4)
        if(NOT OPENCV_INCLUDE_DIR)
            message(FATAL_ERROR "OpenCV headers (opencv2/core.hpp) not found")
        endif()
        set(OpenCV_INCLUDE_DIRS "${OPENCV_INCLUDE_DIR}")
        set(OpenCV_LIBS "")
        foreach(mod IN ITEMS core imgproc features2d flann calib3d imgcodecs)
            find_library(OPENCV_${mod}_LIB NAMES "opencv_${mod}")
            if(NOT OPENCV_${mod}_LIB)
                message(FATAL_ERROR "OpenCV library opencv_${mod} not found")
            endif()
            list(APPEND OpenCV_LIBS "${OPENCV_${mod}_LIB}")
        endforeach()
    endif()
endif()

add_library(luxin_tracker STATIC
    src/split_tracker.cpp
    src/bigmap_detector.cpp
    src/bigmap_geometry.cpp
)

target_include_directories(luxin_tracker
    PUBLIC
        $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
        ${OpenCV_INCLUDE_DIRS}
)

target_link_libraries(luxin_tracker
    PUBLIC
        ${OpenCV_LIBS}
)

if(ANDROID)
    add_library(luxin_tracker_jni SHARED
        src/jni_bridge.cpp
    )
    target_link_libraries(luxin_tracker_jni
        PRIVATE
            luxin_tracker
            android
            jnigraphics
            log
    )
endif()

if(LUXIN_BUILD_TESTS AND NOT ANDROID)
    enable_testing()
    add_executable(test_split_tracker
        tests/test_split_tracker.cpp
    )
    target_link_libraries(test_split_tracker PRIVATE luxin_tracker)
    add_test(NAME test_split_tracker COMMAND test_split_tracker)

    add_executable(test_bigmap_detector tests/test_bigmap_detector.cpp)
    target_link_libraries(test_bigmap_detector PRIVATE luxin_tracker)
    add_test(NAME test_bigmap_detector COMMAND test_bigmap_detector)

    add_executable(test_bigmap_geometry tests/test_bigmap_geometry.cpp)
    target_link_libraries(test_bigmap_geometry PRIVATE luxin_tracker)
    add_test(NAME test_bigmap_geometry COMMAND test_bigmap_geometry)

    add_executable(bigmap_geometry_frames tests/bigmap_geometry_frames.cpp)
    target_link_libraries(bigmap_geometry_frames PRIVATE luxin_tracker)

    add_executable(benchmark_frames
        tests/benchmark_frames.cpp
    )
    target_link_libraries(benchmark_frames PRIVATE luxin_tracker)
endif()
