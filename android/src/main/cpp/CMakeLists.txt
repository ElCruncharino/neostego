cmake_minimum_required(VERSION 3.22.1)
project(neostego_uniward)

add_library(neostego_uniward SHARED uniward_cost.cpp uniward_cost_core.cpp)

target_compile_options(neostego_uniward PRIVATE -O3 -ffast-math)

find_library(log-lib log)
target_link_libraries(neostego_uniward ${log-lib})
