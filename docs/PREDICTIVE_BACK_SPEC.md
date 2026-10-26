# 小蝠预测式返回（Predictive Back）物理动力学与动效规范

> **版本**：v1.1.5  
> **所属模块**：`SmoothBackHost.kt` / `PredictiveMotion.kt` / `TranscriptionScreen.kt`  
> **设计基线**：高阻尼机械感 + 非线性多层视差 + 缩短手势行程 + 容器变形（Container Morphing）

---

## 1. 核心手感设计哲学

小蝠的预测式返回不采用原生的单层缩放，而是采用**双层视差位移 + 对数阻尼加减速 + 独立页面遮罩**架构。  
核心手感特点：
1. **触感紧实、高级阻尼**：物理弹簧模拟带质量物体的粘滞阻尼，消除轻浮的线性机械感。
2. **短程触发**：手势有效行程收窄至约 **48% 屏幕宽度**，单手大拇指轻拉 20%~30% 即可清晰预览上一级，无需横跨全屏。
3. **两套返回语义**：
   - **普通路由（设置 / 历史）**：双层视差平移 + 遮罩渐隐。
   - **详细页（音频转文字）**：卡片冲顶连续曲率形变（Container Morphing）+ 阻尼滑落归位。

---

## 2. 动力学与手势行程配置

### 2.1 手势行程映射（Gesture Travel Gain）
- **文件**：`PredictiveMotion.kt` $\to$ `dampedBackTarget(raw)`

```kotlin
internal fun dampedBackTarget(raw: Double): Double {
    // 将物理滑动行程压缩至约 48% 屏幕宽度，拉动 1/3 屏幕即可达到 ~75% 进度
    val effectiveRaw = (raw / 0.48).coerceIn(0.0, 1.0)
    return (ln(1.0 + 2.4 * effectiveRaw) / ln(3.4)).coerceIn(0.0, .96)
}
```

- **数学公式**：
  $$p_{\text{target}} = \frac{\ln(1 + 2.4 \cdot \text{clamp}(raw / 0.48, 0, 1))}{\ln(3.4)}$$
- **行程对照表**：
  | 用户实际滑过屏幕比例 ($raw$) | 映射有效输入 ($effectiveRaw$) | 最终动画目标进度 ($p_{\text{target}}$) | 底层上一级状态 |
  | :--- | :--- | :--- | :--- |
  | **10%**（大拇指微动） | 0.21 | 0.33 | 蓄势，遮罩开始减退 |
  | **20%**（轻拉） | 0.42 | 0.53 | 上一级开始清晰显现 ($\alpha \approx 20\%$) |
  | **30%**（适度拉动） | 0.63 | 0.70 | 上一级大面积透出 ($\alpha \approx 71\%$) |
  | **35%** | 0.73 | 0.78 | 上一级完全就绪 ($\alpha \approx 89\%$) |
  | **48%** | 1.00 | 0.96 | 达到物理最大目标上限 |

---

### 2.2 物理阻尼弹簧（BackProgressSpring）
- **阻尼方程**：
  $$a = k \cdot (p_{\text{target}} - p) - c \cdot v$$
  $$v_{t+1} = v_t + a \cdot \Delta t, \quad p_{t+1} = p_t + v_{t+1} \cdot \Delta t$$
- **参数规格**：
  - **刚度系数（Stiffness $k$）**：`115.0`
  - **阻尼系数（Damping $c$）**：`19.0`
  - **阻尼比（$\zeta$）**：
    $$\zeta = \frac{c}{2\sqrt{k}} = \frac{19.0}{2\sqrt{115.0}} \approx 0.886$$
    处于轻微欠阻尼（subcritical）临界区间，既能极其紧密地咬合手指，又带有一种柔韧的惯性缓冲感。
  - **时间步长截断**：$\Delta t \in [0.001\text{s}, 0.033\text{s}]$。

---

### 2.3 视差位移对数曲线（dampedDisplacement）
- **公式**：
  $$\text{dampedDisplacement}(p) = \frac{\ln(1 + 4.5 \cdot p)}{\ln(5.5)}$$
- 模拟高粘性阻尼位移，前快后慢，在屏幕边缘呈现自然刹车效果。

---

## 3. 多层视差与淡入淡出（BackLayers）

- **文件**：`PredictiveMotion.kt` $\to$ `backLayers(progress)`

```kotlin
internal fun backLayers(progress: Double): BackLayers {
    val p = progress.coerceIn(0.0, 1.0)
    val backgroundStage = ((p - .36) / .64).coerceIn(0.0, 1.0)
    val backgroundAlpha = cubicProgress(backgroundStage, .645, .045, .355, 1.0)
    val foregroundAlpha = cubicProgress((1.0 - p / .46).coerceIn(0.0, 1.0), .215, .61, .355, 1.0)
    return BackLayers(
        foregroundAlpha.toFloat(), 
        (.13 * dampedDisplacement(p)).toFloat(),
        backgroundAlpha.toFloat(), 
        (-.08 * (1.0 - dampedDisplacement(backgroundStage))).toFloat(),
        (1.0 - backgroundAlpha).toFloat(),
    )
}
```

### 参数与几何说明：
1. **前景退出页（Foreground）**：
   - **X 轴位移**：向右位移 $+13\% \times \text{dampedDisplacement}(p)$。
   - **透明度曲线**：`cubicProgress((1 - p / 0.46), 0.215, 0.61, 0.355, 1.0)`。
2. **后景进入页（Background）**：
   - **触发起点**：当内部进度达到 $p > 0.36$（由于 0.48 行程放大，实际手势仅需 $\approx 12\%$ 屏幕宽度）时启动。
   - **X 轴位移**：从 $-8\%$ 屏幕宽度微视差向右平滑推进至 $0\%$。
   - **透明度曲线**：`cubicProgress(backgroundStage, 0.645, 0.045, 0.355, 1.0)`。
3. **分层遮罩（Themed Mask）**：
   - 不采用简单的两层交叉淡化，而是在两层之间夹入动态背景色遮罩，遮罩不透明度为 `1.0 - backgroundAlpha`，彻底避免两层文字过早交叉导致的脏乱重影。

---

## 4. 详细页容器变形返回（Container Morphing Exit）

当用户从 `transcript:`（详细页）向主页预测式返回时，触发专属的卡片形态还原：
- **返回动画弹簧（morphExitSpring）**：
  - `dampingRatio = 0.82f`（重阻尼滑落）
  - `stiffness = 260f`
- **底层承托卡片动画（SmoothBackHost.kt）**：
  - **缩放**：`scale = 0.94f + 0.06f * p`
  - **透明度**：`alpha = 0.45f + 0.55f * p`
- **详细页背景渐隐（TranscriptionScreen.kt）**：
  - `bgAlpha = (1f - (morphProgress / 0.35f)).coerceIn(0f, 1f)`
- **顶部飞行卡片轨迹**：
  - 位移与形变完全跟随手势进度反向插值回主页列表原位，同时卡片圆角严格保持苹果级 G2 贝塞尔超椭圆（`ContinuousCornerShape`）。

---

## 5. 参数速查表

| 参数名称 | 取值 / 公式 | 所在文件 | 说明 |
| :--- | :--- | :--- | :--- |
| **手势增益尺度** | `raw / 0.48` | `PredictiveMotion.kt` | 控制手势拉动距离（拉动 48% 即达满额） |
| **手势目标对数底** | `ln(1 + 2.4 * x) / ln(3.4)` | `PredictiveMotion.kt` | 咔捕非线性手势阻尼 |
| **物理弹簧刚度 $k$** | `115.0` | `PredictiveMotion.kt` | 弹簧拉力系数 |
| **物理弹簧阻尼 $c$** | `19.0` | `PredictiveMotion.kt` | 弹簧减速阻尼（阻尼比约 0.886） |
| **前景位移幅度** | `+13%` 屏幕宽 | `PredictiveMotion.kt` | 当前页最大右移视差 |
| **后景初始位移** | `-8%` 屏幕宽 | `PredictiveMotion.kt` | 上一级页面初始左偏移视差 |
| **变形返回阻尼比** | `0.82f` | `SmoothBackHost.kt` | 详细页回落主页弹簧阻尼比 |
| **变形返回刚度** | `260f` | `SmoothBackHost.kt` | 详细页回落主页弹簧刚度 |
