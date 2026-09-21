# Native module for :core - holds the app-global secret string values OUT of the DEX.
#
# The committed C source (ozsecrets.c) contains NO real secret. The values are injected at
# BUILD TIME from the gitignored secrets.properties into a gitignored header
# (secrets_generated.h) that the :core Gradle script regenerates on every configure and that
# ozsecrets.c #includes. See core/build.gradle.kts.
cmake_minimum_required(VERSION 3.22.1)
project(ozsecrets C)

add_library(ozsecrets SHARED ozsecrets.c)

# The generated header lives next to this file (the source dir is already on the include path).
target_include_directories(ozsecrets PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
