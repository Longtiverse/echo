cmake_minimum_required(VERSION 3.22.1)
project("echo_audio")

# ========== Phase 1: 基础工程配置 ==========

# 设置C++17标准
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 查找Oboe库 (v1.8.1)
find_package(oboe REQUIRED CONFIG)

# 查找系统库
find_library(log-lib log)
find_library(android-lib android)

# 创建共享库 - Phase 2完整实现
add_library(
    echo_audio
    SHARED
    audio_engine.cpp
    volume_limiter.cpp
    jni_bridge.cpp
)

# 链接库
target_link_libraries(
    echo_audio
    oboe::oboe
    ${log-lib}
    ${android-lib}
    OpenSLES  # 保留作为备用方案
)

# 编译选项 - 低延迟优化
target_compile_options(echo_audio PRIVATE
    -O3                 # 最高优化级别
    -ffast-math         # 快速数学运算
    -fomit-frame-pointer # 省略帧指针
    -Wall               # 所有警告
    -Wextra             # 额外警告
)

# 定义宏用于日志
target_compile_definitions(echo_audio PRIVATE
    LOG_TAG=\"EchoAudio\"
)
