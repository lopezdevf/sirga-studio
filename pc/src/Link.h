// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Sirga Studio contributors
#pragma once

#include "Common.h"
#include "Devices.h"

#include <winsock2.h>

#include <array>
#include <atomic>
#include <condition_variable>
#include <deque>
#include <functional>
#include <mutex>
#include <thread>
#include <vector>

namespace sirga {

/** Móvil con Sirga Studio que respondió a la búsqueda en la red local. */
struct PhoneInfo {
    std::string ip;
    uint16_t port = 0;
    std::wstring device;
    std::wstring source;
    /** Encontrado por la red del anclaje USB del móvil (cable), no por WiFi. */
    bool usb = false;
};

/** Cámara o micrófono del PC que el móvil quiere recibir en la señal [stream] (1-255; la 0 es la pantalla). */
struct Subscription {
    uint8_t stream = 0;
    std::wstring deviceId;
};

/** Envía «SGL1?» por difusión y a cada equipo de la red local, y recoge las respuestas durante [timeoutMs]. */
std::vector<PhoneInfo> DiscoverPhones(int timeoutMs);

/**
 * ¿Hay ahora mismo una red de anclaje USB? Es la que crea el móvil al compartir su conexión por cable
 * (Remote NDIS o NCM). Sin ella no existe camino por USB, por mucho que el cable esté puesto.
 */
bool UsbTetheringActive();

enum class ConnectResult { Ok, WrongCode, Unreachable, NotSirga };

/**
 * Conexión Sirga Link con el móvil (ver engine/.../pclink/SirgaLink.kt). Un hilo escribe en orden
 * lo que llega a la cola y otro lee las peticiones del móvil.
 *
 * Para el menor retraso, si la red no da abasto no se acumula vídeo: los fotogramas atrasados se
 * descartan y se pide un fotograma clave, así el móvil siempre muestra lo más reciente.
 */
class LinkSession {
public:
    LinkSession();
    ~LinkSession();

    ConnectResult Connect(const std::string& ip, uint16_t port, uint16_t code, const std::wstring& pcName, std::wstring& deviceName);
    void Close();
    bool IsConnected() const { return connected_; }

    // Señal 0: la pantalla y el sonido del PC. Las demás, cámaras y micrófonos que pide el móvil
    void SendVideoFormat(uint32_t width, uint32_t height, uint32_t fps);
    void SendVideoFrame(const uint8_t* data, size_t size, bool keyframe, int64_t captureUs);
    void SendAudio(const int16_t* pcm, size_t frames, uint32_t sampleRate, uint32_t channels, int64_t captureUs);
    void SendDeviceList(const std::vector<PcDevice>& devices);
    void SendStreamVideoFormat(uint8_t stream, uint32_t width, uint32_t height, uint32_t fps);
    void SendStreamVideoFrame(uint8_t stream, const uint8_t* data, size_t size, bool keyframe, int64_t captureUs);
    void SendStreamAudio(uint8_t stream, const int16_t* pcm, size_t frames, uint32_t sampleRate, uint32_t channels, int64_t captureUs);

    /** El móvil necesita un fotograma clave de la señal [stream] (decodificador nuevo o fotogramas perdidos). */
    std::function<void(uint8_t stream)> onKeyframeRequest;
    /** El móvil cambió las cámaras y micrófonos que quiere: la lista completa. */
    std::function<void(std::vector<Subscription>)> onSubscribe;
    /** La conexión se cerró sin llamar a Close(). */
    std::function<void()> onDisconnected;

    uint64_t TakeSentBytes() { return sentBytes_.exchange(0); }
    /** Retraso de punta a punta en ms (captura → imagen en el móvil → aviso de vuelta), o -1 sin datos. */
    int LatencyMs() const { return latencyMs_; }
    /** Veces que la red no dio abasto y se saltaron fotogramas desde la última llamada. */
    uint32_t TakeCongestionEvents() { return congestionEvents_.exchange(0); }

private:
    struct Packet {
        std::vector<uint8_t> data;
        bool video = false;
        bool keyframe = false;
        uint8_t stream = 0;
    };

    void Enqueue(Packet&& packet);
    void WriterLoop();
    void ReaderLoop();
    bool SendAll(const uint8_t* data, size_t size);
    bool RecvAll(uint8_t* data, size_t size);
    void Fail();

    SOCKET socket_ = INVALID_SOCKET;
    std::atomic<bool> connected_{false};
    std::atomic<bool> closing_{false};
    std::thread writer_;
    std::thread reader_;
    std::mutex queueMutex_;
    std::condition_variable queueSignal_;
    std::deque<Packet> queue_;
    std::array<uint16_t, 256> queuedVideoFrames_{};
    std::array<bool, 256> dropUntilKeyframe_{};
    std::mutex sendMutex_;
    std::atomic<uint64_t> sentBytes_{0};
    std::atomic<int> latencyMs_{-1};
    std::atomic<uint32_t> congestionEvents_{0};
    int64_t windowSumUs_ = 0;
    int windowSamples_ = 0;
    int64_t windowStartUs_ = 0;
};

}  // namespace sirga
