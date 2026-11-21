cmake_minimum_required(VERSION 3.22.1)
project(draborn_local LANGUAGES C CXX)

set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(BUILD_SHARED_LIBS ${ANDROID} CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL ${ANDROID} CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ${ANDROID} CACHE BOOL "" FORCE)

if(DEFINED LLAMA_SOURCE_DIR)
    add_subdirectory("${LLAMA_SOURCE_DIR}" llama)
else()
    include(FetchContent)
    FetchContent_Declare(
        llama_cpp
        URL https://codeload.github.com/ggml-org/llama.cpp/tar.gz/b1b770d90d94cad9e9ea5b993bdbc383a522fb28
        URL_HASH SHA256=ae8556670b17c25931cd39eb7c95617650eaf6091bbcff206750583ddde1acb4
    )
    FetchContent_MakeAvailable(llama_cpp)
endif()

add_library(draborn_local SHARED translation_model.cpp)
target_compile_features(draborn_local PRIVATE cxx_std_17)
target_link_libraries(draborn_local PRIVATE llama)
