#ifndef AUDIO_ENGINE_H
#define AUDIO_ENGINE_H

#include <oboe/Oboe.h>
#include <atomic>
#include <cmath>
#include "volume_limiter.h"

class AudioEngine : public oboe::AudioStreamDataCallback {
public:
    AudioEngine();
    ~AudioEngine();

    // 初始化音频引擎
    bool initialize();
    
    // 开始/停止音频流
    bool start();
    bool stop();
    void release();
    
    // 设置音量
    void setVolume(float volume);
    void setMaxOutputLevel(float maxDb);
    
    // 获取延迟信息
    double getLatencyMs() const;
    
    // 获取缓冲区状态
    void getBufferState(int* bufferSize, int* capacity, int* writeIndex, int* readIndex) const;

    // Oboe回调函数
    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream* audioStream,
        void* audioData,
        int32_t numFrames) override;

private:
    // 音频流
    oboe::ManagedStream inputStream;
    oboe::ManagedStream outputStream;
    
    // 音频参数
    static constexpr int kSampleRate = 48000;
    static constexpr int kChannelCount = 1;  // 单声道
    static constexpr oboe::AudioFormat kFormat = oboe::AudioFormat::I16;
    static constexpr int kBufferSizeInBursts = 2;  // 最小缓冲区
    
    // 缓冲区
    static constexpr int kBufferCapacity = 4096;
    int16_t circularBuffer[kBufferCapacity];
    std::atomic<int> writeIndex{0};
    std::atomic<int> readIndex{0};
    
    // 音量控制
    std::atomic<float> volume{1.0f};
    VolumeLimiter volumeLimiter;
    
    // 状态
    std::atomic<bool> isRunning{false};
    
    // 私有方法
    bool setupInputStream();
    bool setupOutputStream();
    void processAudio(int16_t* inputData, int16_t* outputData, int32_t numFrames);
};

#endif // AUDIO_ENGINE_H
