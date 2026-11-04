cmake_minimum_required(VERSION 3.22)
project(skeldal_android C CXX)

# Repository root: android/app/src/main/cpp -> up 5 levels
get_filename_component(SKELDAL_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../.." ABSOLUTE)

# SDL2 is fetched as a shared library; SDLActivity (Java) loads libSDL2.so
set(SDL_SHARED ON CACHE BOOL "" FORCE)
set(SDL_STATIC OFF CACHE BOOL "" FORCE)
set(SDL_TEST OFF CACHE BOOL "" FORCE)

include(FetchContent)
FetchContent_Declare(SDL2
    GIT_REPOSITORY https://github.com/libsdl-org/SDL.git
    GIT_TAG release-2.30.9
    GIT_SHALLOW TRUE)
FetchContent_MakeAvailable(SDL2)

# Variables read by the game's top-level CMakeLists.txt
set(SDL2_INCLUDE_DIRS "${sdl2_SOURCE_DIR}/include")
set(SDL2_LIBRARIES SDL2)

add_subdirectory(${SKELDAL_ROOT} ${CMAKE_CURRENT_BINARY_DIR}/skeldal)
