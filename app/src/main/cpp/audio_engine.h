#ifndef ECHO_AUDIO_ENGINE_H
#define ECHO_AUDIO_ENGINE_H

#include <oboe/Oboe.h>
#include <array>
#include <atomic>
#include <cstdint>
#include "volume_limiter.h"

namespace echo {

/**
 * Phase 2: C++低延迟音频引擎
 * 
 * 核心特性:
 * - 基于Oboe的双工音频流 (输入+输出)
 * - 无锁环形缓冲区 (SPSC模式: 单生产者单消费者)
 * - 硬性参数: I16格式, 48000Hz, 低延迟模式
 * - 目标延迟: < 20ms
 */
class AudioEngine : public oboe::AudioStreamDataCallback {
public:
    // 音频参数 - 硬性设定
    static constexpr int32_t kSampleRate = 48000;
    static constexpr int32_t kChannelCount = 1;  // 单声道
    static constexpr oboe::AudioFormat kFormat = oboe::AudioFormat::I16;
    static constexpr int32_t kBufferSizeInBursts = 2;  // 最小化缓冲区
    
    // 环形缓冲区大小 (~85ms @ 48kHz mono)
    // 足够大以防止欠载,足够小以保持低延迟
    static constexpr size_t kRingBufferCapacity = 4096;

    AudioEngine();
    ~AudioEngine();

    // 生命周期管理
    bool initialize();
    bool start();
    void stop();
    void release();
    bool isRunning() const { return isRunning_.load(); }

    // 音量控制 (0.0 - 1.0)
    void setVolume(float volume);
    float getVolume() const { return volume_.load(); }

    // Oboe回调 - 音频线程 (禁止JNI/锁操作)
    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream* stream,
        void* audioData,
        int32_t numFrames) override;

private:
    // 音频流
    std::shared_ptr<oboe::AudioStream> inputStream_;
    std::shared_ptr<oboe::AudioStream> outputStream_;

    // 环形缓冲区 - 无锁设计
    std::array<int16_t, kRingBufferCapacity> ringBuffer_;
    std::atomic<size_t> writeIndex_{0};  // 仅由输入回调写入
    std::atomic<size_t> readIndex_{0};   // 仅由输出回调读取

    // 音量控制
    std::atomic<float> volume_{0.7f};  // 默认70% (-3.1dB)
    VolumeLimiter volumeLimiter_;

    // 状态
    std::atomic<bool> isRunning_{false};
    std::atomic<bool> isInitialized_{false};

    // 缓冲区操作
    size_t getBufferWriteAvailable() const;
    size_t getBufferReadAvailable() const;
    void writeToBuffer(const int16_t* data, size_t numFrames);
    void readFromBuffer(int16_t* data, size_t numFrames);
    
    // 流配置
    bool setupInputStream();
    bool setupOutputStream();
    void closeStreams();
    
    // 统计
    int64_t framesRead_ = 0;
    int64_t framesWritten_ = 0;
    int32_t underrunCount_ = 0;
    int32_t overrunCount_ = 0;
};

} // namespace echo

#endif // ECHO_AUDIO_ENGINE_H
