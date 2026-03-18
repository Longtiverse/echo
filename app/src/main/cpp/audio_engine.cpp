#include "audio_engine.h"
#include <android/log.h>
#include <string>

#define LOG_TAG "AudioEngine_CPP"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

AudioEngine::AudioEngine() {
    LOGD("AudioEngine constructor");
    // 初始化环形缓冲区
    std::memset(circularBuffer, 0, sizeof(circularBuffer));
}

AudioEngine::~AudioEngine() {
    LOGD("AudioEngine destructor");
    release();
}

bool AudioEngine::initialize() {
    LOGD("Initializing AudioEngine...");
    
    if (!setupInputStream()) {
        LOGE("Failed to setup input stream");
        return false;
    }
    
    if (!setupOutputStream()) {
        LOGE("Failed to setup output stream");
        return false;
    }
    
    LOGD("AudioEngine initialized successfully");
    return true;
}

bool AudioEngine::setupInputStream() {
    oboe::AudioStreamBuilder builder;
    
    builder.setDirection(oboe::Direction::Input)
           ->setSharingMode(oboe::SharingMode::Exclusive)
           ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
           ->setFormat(kFormat)
           ->setSampleRate(kSampleRate)
           ->setChannelCount(kChannelCount)
           ->setDataCallback(this);
    
    oboe::Result result = builder.openManagedStream(inputStream);
    if (result != oboe::Result::OK) {
        LOGE("Failed to open input stream: %s", oboe::convertToText(result));
        return false;
    }
    
    // 设置最小缓冲区大小
    int32_t bufferSize = inputStream->getBufferCapacityInFrames() * kBufferSizeInBursts;
    inputStream->setBufferSizeInFrames(bufferSize);
    
    LOGD("Input stream opened: SR=%d, Channels=%d, Buffer=%d",
         inputStream->getSampleRate(),
         inputStream->getChannelCount(),
         inputStream->getBufferSizeInFrames());
    
    return true;
}

bool AudioEngine::setupOutputStream() {
    oboe::AudioStreamBuilder builder;
    
    builder.setDirection(oboe::Direction::Output)
           ->setSharingMode(oboe::SharingMode::Exclusive)
           ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
           ->setFormat(kFormat)
           ->setSampleRate(kSampleRate)
           ->setChannelCount(kChannelCount)
           ->setDataCallback(this);
    
    oboe::Result result = builder.openManagedStream(outputStream);
    if (result != oboe::Result::OK) {
        LOGE("Failed to open output stream: %s", oboe::convertToText(result));
        return false;
    }
    
    // 设置最小缓冲区大小
    int32_t bufferSize = outputStream->getBufferCapacityInFrames() * kBufferSizeInBursts;
    outputStream->setBufferSizeInFrames(bufferSize);
    
    LOGD("Output stream opened: SR=%d, Channels=%d, Buffer=%d",
         outputStream->getSampleRate(),
         outputStream->getChannelCount(),
         outputStream->getBufferSizeInFrames());
    
    return true;
}

bool AudioEngine::start() {
    LOGD("Starting AudioEngine...");
    
    if (isRunning) {
        LOGD("Already running");
        return true;
    }
    
    // 重置缓冲区索引
    writeIndex = 0;
    readIndex = 0;
    
    // 启动输入流
    oboe::Result result = inputStream->start();
    if (result != oboe::Result::OK) {
        LOGE("Failed to start input stream: %s", oboe::convertToText(result));
        return false;
    }
    
    // 启动输出流
    result = outputStream->start();
    if (result != oboe::Result::OK) {
        LOGE("Failed to start output stream: %s", oboe::convertToText(result));
        inputStream->stop();
        return false;
    }
    
    isRunning = true;
    LOGD("AudioEngine started successfully");
    return true;
}

bool AudioEngine::stop() {
    LOGD("Stopping AudioEngine...");
    
    if (!isRunning) {
        return true;
    }
    
    isRunning = false;
    
    if (inputStream) {
        inputStream->stop();
    }
    
    if (outputStream) {
        outputStream->stop();
    }
    
    LOGD("AudioEngine stopped");
    return true;
}

void AudioEngine::release() {
    LOGD("Releasing AudioEngine...");
    
    stop();
    
    if (inputStream) {
        inputStream->close();
        inputStream.reset();
    }
    
    if (outputStream) {
        outputStream->close();
        outputStream.reset();
    }
    
    LOGD("AudioEngine released");
}

void AudioEngine::setVolume(float vol) {
    volume.store(vol);
    LOGD("Volume set to: %.3f", vol);
}

void AudioEngine::setMaxOutputLevel(float maxDb) {
    volumeLimiter.setMaxDb(maxDb);
    LOGD("Max output level set to: %.1f dB", maxDb);
}

double AudioEngine::getLatencyMs() const {
    if (!outputStream) return 0.0;
    
    auto latencyResult = outputStream->calculateLatencyMillis();
    if (latencyResult) {
        return latencyResult.value();
    }
    return 0.0;
}

void AudioEngine::getBufferState(int* bufSize, int* capacity, int* wIdx, int* rIdx) const {
    *bufSize = kBufferCapacity;
    *capacity = kBufferCapacity;
    *wIdx = writeIndex.load();
    *rIdx = readIndex.load();
}

oboe::DataCallbackResult AudioEngine::onAudioReady(
    oboe::AudioStream* audioStream,
    void* audioData,
    int32_t numFrames) {
    
    int16_t* data = static_cast<int16_t*>(audioData);
    
    if (audioStream == inputStream.get()) {
        // 输入流：读取麦克风数据到环形缓冲区
        int currentWrite = writeIndex.load();
        for (int i = 0; i < numFrames; ++i) {
            circularBuffer[currentWrite % kBufferCapacity] = data[i];
            currentWrite++;
        }
        writeIndex.store(currentWrite);
        
    } else if (audioStream == outputStream.get()) {
        // 输出流：从环形缓冲区读取并播放
        int currentRead = readIndex.load();
        int currentWrite = writeIndex.load();
        int available = currentWrite - currentRead;
        
        for (int i = 0; i < numFrames; ++i) {
            if (available > 0) {
                data[i] = circularBuffer[currentRead % kBufferCapacity];
                currentRead++;
                available--;
            } else {
                // 缓冲区欠载，输出静音
                data[i] = 0;
            }
        }
        readIndex.store(currentRead);
        
        // 应用音量和限幅
        processAudio(data, data, numFrames);
    }
    
    return oboe::DataCallbackResult::Continue;
}

void AudioEngine::processAudio(int16_t* inputData, int16_t* outputData, int32_t numFrames) {
    float vol = volume.load();
    
    for (int i = 0; i < numFrames; ++i) {
        // 转换为float进行处理
        float sample = inputData[i] * vol;
        
        // 应用音量限制
        sample = volumeLimiter.process(sample);
        
        // 转换回int16
        outputData[i] = static_cast<int16_t>(std::clamp(sample, -32768.0f, 32767.0f));
    }
}
