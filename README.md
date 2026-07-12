# aozaink-core

开发工程名为 `aozaink-core`；面向玩家的发布产物名为 `molu-core`。

手写汉字识别内核。这是 Aozai Ink 唯一的官方维护模块。

## 职责

只做三件事：

1. 接收 `InkRecognitionRequest`（图片或轨迹 + 透传元数据）
2. 识别 → `InkRecognitionResult`
3. 广播 `InkRecognizedEvent`（识别结果 + 透传元数据原封不动）到 NeoForge 事件总线

## 不做的事

- 不知道玩家用什么物品写字（原版纸、黄符、鼠标）
- 不知道输入的 UI 长什么样（黄符三格、临时白纸书写面）
- 不知道识别结果是什么意思（"火"是燃烧还是暴躁——玩法决定）
- 不主动决定候选字集合（由 input/gameplay 通过 `registerGlyphs` 注册，或由 request 提供 `candidateWhitelist`）

## API 入口

```java
import com.aozainkmc.core.AozaiInkCoreApi;

// 注册输入模块使用的引擎类型
AozaiInkCoreApi.registerInput("classic_taiji_traj", EngineType.ONLINE_TRAJECTORY);

// 注册玩法关心的字，在线引擎推理时只在这些字里选
AozaiInkCoreApi.registerGlyphs(Set.of("火", "镇", "封"));

// 查询字灵存储
AozaiInkCoreApi.markStore().marksOn(target);

// 调用识别器
AozaiInkCoreApi.recognizer().recognize(request);
```

## 引擎类型

### EngineType.OFFLINE_IMAGE

- 使用旧 CNN 图片模型 `assets/aozaink_core/ocr/cup_ocr_64.onnx`。
- 输入为 64×64 灰度浮点数组。
- 输出为全词表 softmax，core 只过滤保留汉字脚本（Unicode HAN）后取 Top-K。
- `candidateWhitelist` 不会传给模型， gameplay 需要在 `InkRecognizedEvent` 里自行过滤。

### EngineType.ONLINE_TRAJECTORY

- 使用 olsingle24 轨迹模型 `assets/aozaink_core/ocr/olsingle24/`。
- 输入为归一化笔迹 `InkTrace`，core 内部做 RDP 简化、resample、6-dim 特征提取。
- 模型结构：单个 `candidate_dynamic.onnx`，直接接收 `trajectory`、`mask`、`candidate_ids`，输出候选 logits；不再使用多出口或分段 block。
- 推理时把 `candidateWhitelist` 或已注册字集转成 `candidate_ids` 传入模型，模型只输出这些字的概率。

## 引擎按需启动

- core 在 `FMLCommonSetupEvent` 时根据 `registerInput` 的结果决定加载哪些 ONNX session。
- 只注册了 `OFFLINE_IMAGE` 就只加载 `cup_ocr_64.onnx`。
- 只注册了 `ONLINE_TRAJECTORY` 就只加载 `olsingle24` 的单个 ONNX session。
- 都没注册则两个引擎都不加载，节省内存和启动时间。
- 首次使用对应引擎前已加载完成，避免第一次推理卡顿。

## 核心类型

### 输入：InkRecognitionRequest

```java
record InkRecognitionRequest(
    InkTrace trace,                    // 归一化笔迹（在线引擎使用）
    float[] imageInput,                // 64x64 灰度浮点数组（离线引擎使用）
    InkRecognitionMode mode,           // ONLINE / OFFLINE / HYBRID（保留）
    List<String> candidateWhitelist,   // 白名单；空=使用 registerGlyphs 注册的字
    long ttlTicks,                     // 标记存活时间
    InkSource source                   // 透传元数据
)
```

### 输出：InkRecognitionResult

```java
record InkRecognitionResult(
    String topGlyph,              // 排名第一的字
    float confidence,             // top1 置信度
    List<InkCandidate> candidates, // 候选字列表
    int simplifiedStrokeCount,    // 简化后笔画数（仅在线引擎有效）
    int simplifiedPointCount,     // 简化后总点数（仅在线引擎有效）
    long writingDurationMs        // 书写耗时（仅在线引擎有效）
)
```

离线图片引擎的 `simplifiedStrokeCount`、`simplifiedPointCount`、`writingDurationMs` 固定为 0。

### 透传：InkSource

```java
record InkSource(
    String sourceId,          // "classic_taiji_traj", "paper_temp", "talisman_desk"
    float powerMultiplier,    // 效果倍率
    String tierLabel,         // "wood", "diamond", 纯字符串
    float tierRank,           // 0.0 ~ 5.0
    Map<String, Object> extra // 备用槽
)
```

core 不修改 `InkSource`，原样透传到事件。玩法模块读它，不需要知道输入的具体实现。

### 跨模块服务：Service Registry

`InkSource.extra` 只表示一次识别事件的随事件负载，方向是 input -> core -> gameplay。需要跨模块同步查询能力时，不要把 `extra` 当双向黑板；改用 core 的类型安全服务注册表：

```java
AozaiInkCoreApi.registerService(GlyphDescriber.class, describer);
GlyphDescriber describer = AozaiInkCoreApi.getService(GlyphDescriber.class);
```

接口契约放在 `aozaink-core` / `core.api`，实现留在具体模块。任何模块都可以注册或查询服务；没人注册时 `getService` 返回 `null`。

### 跨模块单向信号：InkModuleSignalEvent

`InkModuleSignalEvent` 是一个通用的模块间信号容器：`ServerPlayer + ResourceLocation signalId + CompoundTag payload`。core 只提供事件类型，不注册具体信号、不解释 `signalId`，也不把它映射成玩法或成就。当前用法是 input 广播客观输入结果，sigillum 作为玩法模块自行解释。

### 输出：InkRecognizedEvent

```java
// NeoForge 事件，core 广播
event.result();   // InkRecognitionResult
event.source();   // InkSource (原样透传)
event.mark();     // InkMark (core 创建的持久记录)
event.player();   // ServerPlayer
event.level();    // ServerLevel
```

### 存储：InkMarkStore

```java
AozaiInkCoreApi.markStore().attach(mark);        // 附着字灵
AozaiInkCoreApi.markStore().marksOn(target);     // 查询目标上的字灵
AozaiInkCoreApi.markStore().allMarks();          // 所有字灵
AozaiInkCoreApi.markStore().clear(target);       // 清除目标
AozaiInkCoreApi.markStore().pruneExpired(time);  // 清理过期
```

## 构建依赖

只需 NeoForge + onnxruntime：

```groovy
dependencies {
    implementation "net.neoforged:neoforge:${neo_version}"
    jarJar(implementation(group: "com.microsoft.onnxruntime", name: "onnxruntime")) {
        version { prefer onnxruntime_version }
    }
}
```

## 模型文件

- 离线图片：`assets/aozaink_core/ocr/cup_ocr_64.onnx`、`assets/aozaink_core/ocr/labels.json`
- 在线轨迹：`assets/aozaink_core/ocr/olsingle24/candidate_dynamic.onnx`、`meta.json`、`vocab.json`

## 兼容性承诺

- 所有 `com.aozainkmc.core.api.*` 的公开类型在 `0.x` 范围内向后兼容
- 事件结构新增字段不删旧字段
- `InkSource.extra` 是预留扩展槽，永不删除
- `InkMarkStore` 接口稳定，不增删方法
