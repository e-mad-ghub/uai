cmake_minimum_required(VERSION 3.22.1)

project("ai-chat" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED true)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS}"   CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS}" CACHE STRING "" FORCE)

# --------------------------------------------------------------------------
# AI Chat library
# --------------------------------------------------------------------------

# Enable the Vulkan backend when the Android NDK shader compiler is available.
# The CPU backends remain enabled as the automatic fallback path.
if(DEFINED ANDROID_NDK AND GGML_VULKAN)
    if(NOT DEFINED Vulkan_GLSLC_EXECUTABLE OR Vulkan_GLSLC_EXECUTABLE STREQUAL "")
        set(_vulkan_glslc_candidates "")
        if(CMAKE_HOST_SYSTEM_NAME STREQUAL "Linux")
            list(APPEND _vulkan_glslc_candidates
                    "${ANDROID_NDK}/shader-tools/linux-x86_64/glslc")
        elseif(CMAKE_HOST_SYSTEM_NAME STREQUAL "Darwin")
            if(CMAKE_HOST_SYSTEM_PROCESSOR STREQUAL "arm64")
                list(APPEND _vulkan_glslc_candidates
                        "${ANDROID_NDK}/shader-tools/darwin-arm64/glslc")
            endif()
            list(APPEND _vulkan_glslc_candidates
                    "${ANDROID_NDK}/shader-tools/darwin-x86_64/glslc")
        elseif(CMAKE_HOST_SYSTEM_NAME STREQUAL "Windows")
            list(APPEND _vulkan_glslc_candidates
                    "${ANDROID_NDK}/shader-tools/windows-x86_64/glslc.exe")
        endif()

        foreach(_candidate IN LISTS _vulkan_glslc_candidates)
            if(EXISTS "${_candidate}")
                set(Vulkan_GLSLC_EXECUTABLE "${_candidate}" CACHE FILEPATH
                        "Android NDK glslc used for Vulkan shader generation" FORCE)
                message(STATUS "Using Vulkan glslc at ${Vulkan_GLSLC_EXECUTABLE}")
                break()
            endif()
        endforeach()
    endif()

    if(NOT DEFINED Vulkan_GLSLC_EXECUTABLE OR Vulkan_GLSLC_EXECUTABLE STREQUAL "")
        message(WARNING "GGML_VULKAN requested but Android NDK glslc was not found; disabling Vulkan backend and continuing with CPU fallback.")
        set(GGML_VULKAN OFF CACHE BOOL "ggml: use Vulkan" FORCE)
    endif()
endif()

if(DEFINED ANDROID_ABI)
    message(STATUS "Detected Android ABI: ${ANDROID_ABI}")
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        # Pinned curated runtime profile: arm64-v8a + KleidiAI + OpenMP.
        set(GGML_SYSTEM_ARCH "ARM")
        set(GGML_CPU_KLEIDIAI ON)
        set(GGML_OPENMP ON)
    elseif(ANDROID_ABI STREQUAL "x86_64")
        set(GGML_SYSTEM_ARCH "x86")
        set(GGML_CPU_KLEIDIAI OFF)
        set(GGML_OPENMP OFF)
    else()
        message(FATAL_ERROR "Unsupported ABI: ${ANDROID_ABI}")
    endif()
endif()

set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp)
include_directories(BEFORE
        ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/vulkan-hpp/include)
add_subdirectory(${LLAMA_SRC} build-llama)
add_subdirectory(${LLAMA_SRC}/tools/mtmd build-mtmd)

add_library(${CMAKE_PROJECT_NAME} SHARED
        ai_chat.cpp)

target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE
        GGML_SYSTEM_ARCH=${GGML_SYSTEM_ARCH}
        GGML_CPU_KLEIDIAI=$<BOOL:${GGML_CPU_KLEIDIAI}>
        GGML_OPENMP=$<BOOL:${GGML_OPENMP}>
)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        ${LLAMA_SRC}
        ${LLAMA_SRC}/common
        ${LLAMA_SRC}/include
        ${LLAMA_SRC}/ggml/include
        ${LLAMA_SRC}/ggml/src
        ${LLAMA_SRC}/tools/mtmd)

target_link_libraries(${CMAKE_PROJECT_NAME}
        llama
        common
        mtmd
        android
        log)
