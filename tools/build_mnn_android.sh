set -e
python3 -m venv /home/box/cmkenv 2>/dev/null || true
/home/box/cmkenv/bin/pip install -q cmake ninja
export PATH=/home/box/cmkenv/bin:$PATH ANDROID_NDK=/home/box/android/ndk/27.2.12479018
cd /workspace/MNN/project/android && mkdir -p build_64 && cd build_64
cmake ../../../ -G Ninja -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake -DCMAKE_BUILD_TYPE=Release -DANDROID_ABI=arm64-v8a -DANDROID_STL=c++_shared -DANDROID_NATIVE_API_LEVEL=android-26 -DMNN_USE_LOGCAT=true -DMNN_BUILD_BENCHMARK=OFF -DMNN_BUILD_TEST=OFF -DMNN_BUILD_FOR_ANDROID_COMMAND=true -DMNN_LOW_MEMORY=ON -DMNN_CPU_WEIGHT_DEQUANT_GEMM=ON -DMNN_BUILD_LLM=ON -DMNN_SUPPORT_TRANSFORMER_FUSE=ON -DMNN_ARM82=ON -DMNN_OPENCL=ON -DLLM_SUPPORT_VISION=ON -DMNN_BUILD_OPENCV=ON -DMNN_IMGCODECS=ON -DMNN_SEP_BUILD=OFF -DMNN_BUILD_TOOLS=OFF "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384" -DCMAKE_INSTALL_PREFIX=.
ninja -j7
echo BUILD_OK
