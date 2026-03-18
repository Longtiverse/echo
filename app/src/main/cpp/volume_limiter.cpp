#include "volume_limiter.h"

VolumeLimiter::VolumeLimiter() {
    setMaxDb(0.0f);  // 默认不限制
}

void VolumeLimiter::setMaxDb(float maxDb) {
    // 将dB转换为线性值: linear = 10^(dB/20)
    float linear = std::pow(10.0f, maxDb / 20.0f);
    maxLinear.store(linear);
    threshold = linear;
}

float VolumeLimiter::process(float input) {
    float maxVol = maxLinear.load();
    
    // 应用音量限制
    float limited = input * maxVol;
    
    // 软限幅处理，防止削波
    return softClip(limited);
}

void VolumeLimiter::process(float* data, int numFrames) {
    for (int i = 0; i < numFrames; ++i) {
        data[i] = process(data[i]);
    }
}

float VolumeLimiter::softClip(float sample) {
    // 软限幅算法
    // 当信号超过阈值时，使用平滑曲线限幅
    
    const float thresholdLevel = threshold * 0.8f;  // 开始压缩的阈值
    
    if (std::abs(sample) < thresholdLevel) {
        // 在阈值范围内，线性通过
        return sample;
    } else if (std::abs(sample) < threshold) {
        // 在压缩区，使用平滑过渡
        float sign = (sample > 0) ? 1.0f : -1.0f;
        float absSample = std::abs(sample);
        float normalized = (absSample - thresholdLevel) / (threshold - thresholdLevel);
        
        // 使用平滑的S曲线压缩
        float compressed = thresholdLevel + (threshold - thresholdLevel) * 
            (normalized * (2.0f - normalized));
        
        return sign * compressed;
    } else {
        // 硬限幅
        return (sample > 0) ? threshold : -threshold;
    }
}
