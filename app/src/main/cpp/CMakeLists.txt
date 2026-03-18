cmake_minimum_required(VERSION 3.22.1)
project("echo_oboe")

# 设置C++标准
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 查找Oboe库
find_package(oboe REQUIRED CONFIG)

# 查找日志库
find_library(log-lib log)

# 创建共享库 - 只编译 oboe_engine.cpp（包含所有实现）
add_library(
    echo_oboe
    SHARED
    oboe_engine.cpp
)

# 链接库
target_link_libraries(
    echo_oboe
    oboe::oboe
    ${log-lib}
    android
)

# 编译选项 - 优化性能
target_compile_options(echo_oboe PRIVATE
    -O3
    -ffast-math
    -fomit-frame-pointer
    -Wall
    -Wextra
)

# 定义宏
target_compile_definitions(echo_oboe PRIVATE
    LOG_TAG=\"EchoOboe\"
)
