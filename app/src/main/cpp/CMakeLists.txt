cmake_minimum_required(VERSION 3.22.1)
project(tsuki_llm CXX)
set(CMAKE_CXX_STANDARD 17)
# libMNN.so is prebuilt from alibaba/MNN (commit in third_party/mnn/MNN_COMMIT) by tools/build_mnn_android.sh
# with MNN_BUILD_LLM + LLM_SUPPORT_VISION + MNN_ARM82 + MNN_OPENCL + MNN_LOW_MEMORY.
add_library(MNN SHARED IMPORTED)
set_target_properties(MNN PROPERTIES IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libMNN.so)
add_library(tsuki_llm SHARED tsuki_llm.cpp)
target_include_directories(tsuki_llm PRIVATE ${CMAKE_SOURCE_DIR}/third_party/mnn/include)
target_link_options(tsuki_llm PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(tsuki_llm MNN log)
