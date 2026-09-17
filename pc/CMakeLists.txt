# SPDX-License-Identifier: GPL-2.0-or-later
# Copyright (C) 2026 Sirga Studio contributors
#
# Sirga Studio PC: envía la pantalla y el sonido del ordenador a la fuente «PC» de Sirga Studio.
# Solo usa el SDK de Windows (Windows.Graphics.Capture, Media Foundation, WASAPI): sin dependencias.
#
#   cmake -B build -A x64
#   cmake --build build --config Release

cmake_minimum_required(VERSION 3.24)
project(SirgaStudioPC VERSION 0.5.0 LANGUAGES CXX RC)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
# CRT estático: el .exe funciona sin instalar redistribuibles de Visual C++
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")

add_executable(SirgaStudioPC WIN32
    src/main.cpp
    src/Common.cpp
    src/Link.cpp
    src/ScreenCapture.cpp
    src/VideoEncoder.cpp
    src/AudioCapture.cpp
    src/Streamer.cpp
    src/Devices.cpp
    src/CameraCapture.cpp
    res/app.rc
)

target_compile_definitions(SirgaStudioPC PRIVATE UNICODE _UNICODE WIN32_LEAN_AND_MEAN NOMINMAX _WIN32_WINNT=0x0A00 NTDDI_VERSION=0x0A000007)
target_compile_options(SirgaStudioPC PRIVATE /W4 /permissive- /EHsc /utf-8 /bigobj /Zc:__cplusplus)
# El manifiesto propio (PerMonitorV2, controles comunes v6) va en app.rc
target_link_options(SirgaStudioPC PRIVATE /MANIFEST:NO)
target_link_libraries(SirgaStudioPC PRIVATE
    d3d11 dxgi
    mfplat mfuuid mf mfreadwrite
    ws2_32 iphlpapi
    ole32 oleaut32 shell32 windowsapp
    avrt winmm
    comctl32 uxtheme dwmapi gdiplus
)
