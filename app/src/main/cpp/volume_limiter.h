#ifndef ECHO_VOLUME_LIMITER_H
#define ECHO_VOLUME_LIMITER_H

#include <cmath>
#include <atomic>
#include <algorithm>

namespace echo {

/**
 * Phase 2/3: 音量限制器与软限幅
 * 
 * 核心特性:
 * - 软限幅 (Soft Clipping): 防止音频削波，产生平滑失真
 * - 线性音量控制: 0.0 - 1.0 映射到 0% - 100%
 * - 默认限制: 70% (-3.1dB) 保护听力和扬声器
 * 
 * 软限幅算法:
 * 1. 对于 |x| < threshold: y = x (线性区域)
 * 2. 对于 |x| >= threshold: y = sign(x) * (threshold + (1-threshold) * curve(t))
 *    其中 t = (|x| - threshold) / (1 - threshold)
 *    curve(t) = 3t² - 2t³ (平滑多项式)
 */
class VolumeLimiter {
public:
    VolumeLimiter();
    
    /**
     * 设置最大输出电平 (0.0 - 1.0)
     * 0.0 = 静音, 1.0 = 0dB (满音量)
     * 默认: 0.7 (-3.1dB)
     */
    void setMaxLevel(float linearLevel);
    
    /**
     * 获取当前最大电平
     */
    float getMaxLevel() const { return maxLinear_.load(); }
    
    /**
     * 处理单个采样点
     * 输入/输出: int16_t 范围 [-32768, 32767]
     */
    int16_t process(int16_t input);
    
    /**
     * 批量处理
     */
    void process(int16_t* data, int numFrames);

private:
    std::atomic<float> maxLinear_{0.7f};  // 默认70% = -3.1dB
    
    /**
     * 软限幅核心算法
     * 输入: 归一化到 [-1.0, 1.0] 的浮点样本
     * 输出: 限幅后的样本
     */
    float softClip(float normalizedSample);
    
    /**
     * 平滑过渡曲线: 3t² - 2t³
     * 保证在 t=0 处斜率为0, 在 t=1 处斜率为0, 中间平滑
     */
    static float smoothCurve(float t);
};

} // namespace echo

#endif // ECHO_VOLUME_LIMITER_H
