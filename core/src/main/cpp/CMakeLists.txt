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

# 16 KB page-size support (required by Google Play for Android 15+ from Nov 2025). Force the
# ELF LOAD segments to a 16 KB max page size so the .so loads on 16 KB-page devices. NDK r27+
# does this by default, but we set it explicitly so the alignment is correct regardless of the
# NDK / AGP version doing the build. See developer.android.com/16kb-page-size.
target_link_options(ozsecrets PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
