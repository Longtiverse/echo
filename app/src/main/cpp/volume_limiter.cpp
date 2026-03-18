#include "volume_limiter.h"
#include <cstdint>

namespace echo {

VolumeLimiter::VolumeLimiter() {
    // 默认70% (-3.1dB)
    setMaxLevel(0.7f);
}

void VolumeLimiter::setMaxLevel(float linearLevel) {
    // 限制在有效范围 [0.0, 1.0]
    linearLevel = std::max(0.0f, std::min(1.0f, linearLevel));
    maxLinear_.store(linearLevel);
}

int16_t VolumeLimiter::process(int16_t input) {
    // 转换为归一化浮点数 [-1.0, 1.0]
    float normalized = input / 32768.0f;
    
    // 应用软限幅
    float clipped = softClip(normalized);
    
    // 转换回 int16_t
    int32_t output = static_cast<int32_t>(clipped * 32768.0f);
    
    // 防止溢出
    return static_cast<int16_t>(std::max(-32768, std::min(32767, output)));
}

void VolumeLimiter::process(int16_t* data, int numFrames) {
    for (int i = 0; i < numFrames; i++) {
        data[i] = process(data[i]);
    }
}

float VolumeLimiter::softClip(float normalizedSample) {
    float maxLevel = maxLinear_.load();
    
    // 缩放到目标电平
    float scaled = normalizedSample * maxLevel;
    
    // 软限幅阈值: 开始压缩的点 (目标电平的80%)
    const float threshold = maxLevel * 0.8f;
    
    float absSample = std::abs(scaled);
    
    if (absSample <= threshold) {
        // 线性区域: 直接通过
        return scaled;
    }
    
    // 压缩区域
    float sign = (scaled > 0.0f) ? 1.0f : -1.0f;
    
    // 归一化到 [0, 1] 的压缩区
    float t = (absSample - threshold) / (maxLevel - threshold);
    t = std::max(0.0f, std::min(1.0f, t));  // 限制范围
    
    // 应用平滑曲线
    float compressed = threshold + (maxLevel - threshold) * smoothCurve(t);
    
    return sign * compressed;
}

float VolumeLimiter::smoothCurve(float t) {
    // 3t² - 2t³ 平滑多项式
    // 特性: f(0)=0, f(1)=1, f'(0)=0, f'(1)=0
    return t * t * (3.0f - 2.0f * t);
}

} // namespace echo
