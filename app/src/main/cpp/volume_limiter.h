#ifndef VOLUME_LIMITER_H
#define VOLUME_LIMITER_H

#include <cmath>
#include <atomic>

/**
 * 音量限制器
 * 软限幅算法，防止音频削波
 */
class VolumeLimiter {
public:
    VolumeLimiter();
    
    // 设置最大输出电平（dB）
    void setMaxDb(float maxDb);
    
    // 处理单个采样
    float process(float input);
    
    // 批量处理
    void process(float* data, int numFrames);

private:
    std::atomic<float> maxLinear{1.0f};  // 最大线性音量
    float threshold{1.0f};               // 限幅阈值
    
    // 软限幅函数
    float softClip(float sample);
};

#endif // VOLUME_LIMITER_H
