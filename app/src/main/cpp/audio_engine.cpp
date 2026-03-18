#include "audio_engine.h"
#include <android/log.h>
#include <cstring>

#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "EchoAudio", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "EchoAudio", __VA_ARGS__)

namespace echo {

AudioEngine::AudioEngine() {
    // 初始化环形缓冲区为0
    ringBuffer_.fill(0);
    LOGD("AudioEngine created");
}

AudioEngine::~AudioEngine() {
    release();
    LOGD("AudioEngine destroyed");
}

bool AudioEngine::initialize() {
    if (isInitialized_.load()) {
        LOGD("Already initialized");
        return true;
    }

    LOGD("Initializing AudioEngine...");
    LOGD("SampleRate: %d, Channels: %d, Format: I16", kSampleRate, kChannelCount);

    // 初始化音量限制器 (默认70% = -3.1dB)
    volumeLimiter_.setMaxLevel(0.7f);
    volume_.store(0.7f);

    isInitialized_.store(true);
    LOGD("AudioEngine initialized successfully");
    return true;
}

bool AudioEngine::start() {
    if (isRunning_.load()) {
        LOGD("Already running");
        return true;
    }

    if (!isInitialized_.load()) {
        if (!initialize()) {
            LOGE("Failed to initialize");
            return false;
        }
    }

    LOGD("Starting audio engine...");

    // 重置缓冲区
    writeIndex_.store(0);
    readIndex_.store(0);
    ringBuffer_.fill(0);

    // 先启动输出流 (播放), 这样输入有数据时立即可以播放
    if (!setupOutputStream()) {
        LOGE("Failed to setup output stream");
        return false;
    }

    if (!setupInputStream()) {
        LOGE("Failed to setup input stream");
        closeStreams();
        return false;
    }

    isRunning_.store(true);
    LOGD("Audio engine started successfully");
    return true;
}

void AudioEngine::stop() {
    if (!isRunning_.load()) {
        return;
    }

    LOGD("Stopping audio engine...");
    isRunning_.store(false);
    closeStreams();
    
    LOGD("Audio engine stopped. Stats: read=%ld, written=%ld, underruns=%d, overruns=%d",
         framesRead_, framesWritten_, underrunCount_, overrunCount_);
}

void AudioEngine::release() {
    stop();
    isInitialized_.store(false);
    LOGD("AudioEngine released");
}

void AudioEngine::setVolume(float volume) {
    // 限制范围 0.0 - 1.0
    volume = std::max(0.0f, std::min(1.0f, volume));
    volume_.store(volume);
    volumeLimiter_.setMaxLevel(volume);
    LOGD("Volume set to %.2f", volume);
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

    oboe::Result result = builder.openStream(inputStream_);
    if (result != oboe::Result::OK) {
        LOGE("Failed to open input stream: %s", oboe::convertToText(result));
        return false;
    }

    // 设置缓冲区大小为最小延迟
    inputStream_->setBufferSizeInFrames(
        inputStream_->getFramesPerBurst() * kBufferSizeInBursts);

    result = inputStream_->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Failed to start input stream: %s", oboe::convertToText(result));
        return false;
    }

    LOGD("Input stream opened: SR=%d, FPBS=%d, Buf=%d",
         inputStream_->getSampleRate(),
         inputStream_->getFramesPerBurst(),
         inputStream_->getBufferSizeInFrames());

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

    oboe::Result result = builder.openStream(outputStream_);
    if (result != oboe::Result::OK) {
        LOGE("Failed to open output stream: %s", oboe::convertToText(result));
        return false;
    }

    // 设置缓冲区大小为最小延迟
    outputStream_->setBufferSizeInFrames(
        outputStream_->getFramesPerBurst() * kBufferSizeInBursts);

    result = outputStream_->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Failed to start output stream: %s", oboe::convertToText(result));
        return false;
    }

    LOGD("Output stream opened: SR=%d, FPBS=%d, Buf=%d",
         outputStream_->getSampleRate(),
         outputStream_->getFramesPerBurst(),
         outputStream_->getBufferSizeInFrames());

    return true;
}

void AudioEngine::closeStreams() {
    if (inputStream_) {
        inputStream_->stop();
        inputStream_->close();
        inputStream_.reset();
    }
    if (outputStream_) {
        outputStream_->stop();
        outputStream_->close();
        outputStream_.reset();
    }
}

oboe::DataCallbackResult AudioEngine::onAudioReady(
    oboe::AudioStream* stream,
    void* audioData,
    int32_t numFrames) {
    
    if (stream->getDirection() == oboe::Direction::Input) {
        // ===== 输入回调: 麦克风数据写入环形缓冲区 =====
        // 注意: 此回调在音频线程上运行, 禁止JNI调用/锁操作
        
        const int16_t* inputData = static_cast<const int16_t*>(audioData);
        size_t available = getBufferWriteAvailable();
        
        if (available >= static_cast<size_t>(numFrames)) {
            // 有足够空间, 正常写入
            writeToBuffer(inputData, numFrames);
            framesRead_ += numFrames;
        } else {
            // 缓冲区满 (overrun), 跳过最旧的数据
            overrunCount_++;
            
            // 覆盖写入 (丢弃旧数据)
            size_t skipFrames = numFrames - available + kRingBufferCapacity / 4;
            readIndex_.store((readIndex_.load() + skipFrames) % kRingBufferCapacity);
            writeToBuffer(inputData, numFrames);
        }
        
    } else {
        // ===== 输出回调: 从环形缓冲区读取数据到扬声器 =====
        // 注意: 此回调在音频线程上运行, 禁止JNI调用/锁操作
        
        int16_t* outputData = static_cast<int16_t*>(audioData);
        size_t available = getBufferReadAvailable();
        
        if (available >= static_cast<size_t>(numFrames)) {
            // 有足够数据, 正常读取
            readFromBuffer(outputData, numFrames);
            
            // 应用音量限制和软限幅
            for (int32_t i = 0; i < numFrames; i++) {
                outputData[i] = volumeLimiter_.process(outputData[i]);
            }
            
            framesWritten_ += numFrames;
        } else {
            // 缓冲区空 (underrun), 输出静音
            underrunCount_++;
            std::memset(outputData, 0, numFrames * sizeof(int16_t));
        }
    }

    return oboe::DataCallbackResult::Continue;
}

// ========== 无锁环形缓冲区操作 ==========

size_t AudioEngine::getBufferWriteAvailable() const {
    size_t writeIdx = writeIndex_.load(std::memory_order_relaxed);
    size_t readIdx = readIndex_.load(std::memory_order_acquire);
    
    if (writeIdx >= readIdx) {
        return kRingBufferCapacity - (writeIdx - readIdx) - 1;
    } else {
        return readIdx - writeIdx - 1;
    }
}

size_t AudioEngine::getBufferReadAvailable() const {
    size_t writeIdx = writeIndex_.load(std::memory_order_acquire);
    size_t readIdx = readIndex_.load(std::memory_order_relaxed);
    
    if (writeIdx >= readIdx) {
        return writeIdx - readIdx;
    } else {
        return kRingBufferCapacity - (readIdx - writeIdx);
    }
}

void AudioEngine::writeToBuffer(const int16_t* data, size_t numFrames) {
    size_t writeIdx = writeIndex_.load(std::memory_order_relaxed);
    
    for (size_t i = 0; i < numFrames; i++) {
        ringBuffer_[writeIdx] = data[i];
        writeIdx = (writeIdx + 1) % kRingBufferCapacity;
    }
    
    // 使用release语义确保数据写入在索引更新前完成
    writeIndex_.store(writeIdx, std::memory_order_release);
}

void AudioEngine::readFromBuffer(int16_t* data, size_t numFrames) {
    size_t readIdx = readIndex_.load(std::memory_order_relaxed);
    
    for (size_t i = 0; i < numFrames; i++) {
        data[i] = ringBuffer_[readIdx];
        readIdx = (readIdx + 1) % kRingBufferCapacity;
    }
    
    // 使用release语义
    readIndex_.store(readIdx, std::memory_order_release);
}

} // namespace echo
