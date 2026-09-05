# Minecraft Fabric MCP 客户端模组 PRD

- 文档状态：Draft v0.4（技术方案已确认）
- 更新日期：2026-09-05
- 产品代号：MCMCP
- 目标平台：Minecraft Java Edition 26.2、Fabric Loader
- MCP 协议基线：2026-07-28

## 1. 背景

AI Agent 需要通过标准化接口读取 Minecraft 客户端上下文，并以当前玩家身份执行游戏命令。现有自动化方案通常依赖屏幕识别、键鼠模拟或服务端插件，存在上下文不完整、稳定性差、部署成本高等问题。

MCMCP 是一个纯客户端 Fabric 模组，在 Minecraft 进程内提供 MCP Server。外部 MCP Host 通过本机 Streamable HTTP 连接模组，读取聊天、游戏状态与当前画面，并请求客户端发送斜杠命令。服务端无需安装任何模组或插件。

## 2. 产品目标

### 2.1 首版目标

1. 外部 MCP 客户端可以请求当前玩家发送一条斜杠命令。
2. 外部 MCP 客户端可以读取本次客户端会话内捕获的聊天框消息。
3. 外部 MCP 客户端可以获得结构化的客户端、连接、玩家、世界、区块及 F3 调试信息快照，包括玩家精确/方块/区块坐标、区块内坐标、朝向、光照、性能与客户端可见系统信息。
4. 外部 MCP 客户端可以获得当前 Minecraft 窗口游戏画面的 PNG 截图。
5. 单人游戏、局域网游戏和多人服务器均可使用；命令仍受当前玩家权限及服务器规则约束。
6. MCP 服务只监听本机回环地址，不对局域网或公网暴露。

### 2.2 成功指标

- MCP Inspector 能发现并调用全部 4 个工具。
- 连续运行 2 小时不发生崩溃、死锁或明显内存持续增长。
- 游戏已进入世界时，状态与聊天查询的本机请求 P95 响应时间不超过 100 ms。
- 1920×1080 画面截图的本机请求 P95 响应时间不超过 500 ms；硬件编码或游戏帧率异常不纳入该指标。
- MCP 服务空闲时不持续消耗可观 CPU，游戏主线程每 tick 新增耗时 P95 不超过 1 ms。
- 所有 Minecraft API 和渲染 API 调用均在对应的客户端线程或渲染线程执行。

## 3. 非目标

首版不包含：

- 键盘、鼠标、移动、视角、攻击、使用物品或 GUI 点击控制。
- 在模组内运行大语言模型、直接解析自然语言或负责建筑规划；这些能力由外部 MCP Host/模型提供。
- 将用户参考图片上传给模组或在模组内执行图像理解；参考图片由外部 MCP Host/模型接收，模组只返回游戏截图。
- 发送普通聊天消息。
- 绕过玩家权限、服务器权限、反作弊或聊天安全机制。
- 保证命令执行成功，或将某条服务端反馈与某次命令精确关联。
- 读取服务端未同步给客户端的数据。
- 服务端 Fabric 模组、代理服插件或跨机器远程接入。
- 局域网监听、OAuth、Bearer Token 或其他鉴权机制。
- 视频流、连续帧、音频、离屏渲染或自由相机截图。
- 持久化聊天记录、截图文件或完整游戏状态历史。
- Minecraft 快照版、预发布版以及 26.3 Pre-Release 支持。
- 首版同时兼容多个 Minecraft 大版本。

## 4. 目标用户与核心场景

### 4.1 目标用户

- 使用支持 MCP 的 AI 编程助手或 Agent 操作 Minecraft 的玩家。
- 开发 Minecraft 辅助 Agent、测试 Agent 或观测工具的开发者。

### 4.2 核心用户故事

1. 作为 Agent，我希望执行 `/time query daytime` 等命令，以便利用当前玩家已有的命令权限操作或查询游戏。
2. 作为 Agent，我希望读取最近聊天消息，以便理解玩家、服务器和系统反馈。
3. 作为 Agent，我希望读取玩家精确/方块/区块位置、区块内坐标、朝向、生命值、维度、天气及结构化 F3 信息，以便定位建筑锚点并进行下一步推理。
4. 作为 Agent，我希望获取当前游戏画面，以便识别无法通过结构化状态表达的视觉信息。
5. 作为玩家，我希望 MCP 服务只允许本机程序访问，避免无意暴露到网络。

### 4.3 端到端用户案例

#### 案例 A：用自然语言获得高等级附魔物品

用户不会记忆 Minecraft 命令，对 MCP Host 说：“给我一把锋利 100 的钻石剑。”

预期流程：

1. 外部模型调用 `minecraft_get_game_state`，确认游戏版本、玩家已进入世界及当前连接类型。
2. 外部模型根据 Minecraft 26.2 命令语法生成版本正确的 `/give` 命令；模组本身不解释自然语言。
3. 模型调用 `minecraft_execute_command` 提交命令。
4. 模型调用 `minecraft_get_chat_messages` 获取命令反馈，必要时根据错误消息修正语法并重试。
5. 可选调用 `minecraft_capture_screenshot`，让用户直观看到物品已出现在背包或快捷栏中。

成功条件：当前玩家拥有执行对应命令的权限时获得目标物品；无权限、服务端禁用相关命令或物品组件不合法时，模型明确向用户解释失败反馈，不声称已经成功。

#### 案例 B：分批建立大型规则结构

用户说：“建立一个 100 × 100 × 100 的中空正方体，由彩色羊毛构成，垂直方向交替排列。”

预期流程：

1. 模型读取玩家精确坐标、方块坐标、区块坐标、朝向和维度，询问或推断建筑锚点与颜色序列。
2. 模型把需求转换为明确方案：外尺寸为 100 × 100 × 100、内部为空、每个 Y 层按确定的彩色羊毛序列循环。
3. 模型考虑 `/fill` 的单次体积限制、命令长度限制、世界高度、已加载区块和服务器限速，将六个外表面拆分为有界命令批次；不得通过填满整个体积再清空的方式造成不必要的大规模修改。
4. 模型逐批调用 `minecraft_execute_command`，并通过聊天反馈检测坐标越界、方块数量超限、权限不足或服务器限流。
5. 模型在关键阶段读取状态和截图，确认位置、外观和进度；失败后只重试未确认的批次，避免盲目重复全部命令。

成功条件：结构尺寸、锚点、中空规则和垂直颜色周期与用户确认的方案一致。MCMCP 只保证命令提交与观测能力，不保证外部模型的建筑规划正确。

#### 案例 C：配置经过区域时设置出生点的命令方块系统

用户说：“设置一个命令方块或一组命令方块，当玩家经过这里时设置出生点。”

预期流程：

1. 模型读取玩家位置、脚下方块、朝向和目标方块，向用户确认触发区域、适用玩家范围、出生点位置以及一次触发还是持续检测。
2. 模型根据权限和场景选择压力板加脉冲命令方块，或循环命令方块加 `/execute` 区域检测方案。
3. 模型使用版本正确的 `/setblock`、`/data` 或等效命令放置并配置命令方块，不依赖 GUI 点击。
4. 模型读取聊天反馈并使用状态与截图确认放置位置；提示用户实际经过触发区完成最终行为测试。

成功条件：具备命令方块使用权限且服务器允许命令方块时，目标玩家经过指定区域后出生点被设置到确认位置。外部模型必须在修改前复述触发区域和出生点，避免坐标误解造成错误配置。

#### 案例 D：根据参考图片辅助搭建建筑

用户向 MCP Host/模型提供一张建筑参考图片，并要求在当前世界复现。

预期流程：

1. 外部模型接收并分析参考图片，提取比例、轮廓、材质、颜色和可见结构；参考图片不经过 MCMCP 传输。
2. 模型读取玩家位置、维度、朝向、区块与世界高度，向用户确认建筑锚点、朝向、目标尺寸、材质替代规则以及图片未展示部分的处理方式。
3. 模型将建筑拆分为地基、主体、外立面、屋顶和细节等可验证阶段，并生成版本正确、满足单次限制的命令批次。
4. 每个阶段完成后，模型调用 `minecraft_capture_screenshot` 获取游戏内视图，并结合结构化位置和目标方块状态进行视觉复核。
5. 对无法从单张图片推断的背面、内部或遮挡区域，模型必须询问用户或明确采用的假设，不得将推测描述成图片事实。

成功条件：在用户确认的视角、尺寸和近似标准下完成可识别的复现。首版不承诺像素级、逐方块或不可见内部结构的自动还原。

### 4.4 通用约束

- 自然语言理解、任务分解、版本命令生成、失败重试和参考图片理解均属于外部 MCP Host/模型职责。
- 所有改变世界的操作最终必须通过 `minecraft_execute_command`，并受玩家权限、服务器规则、命令方块设置、世界边界与命令限制约束。
- 大型任务必须拆分为可检查批次，读取聊天反馈，并在关键阶段截图；不得因未收到明确成功反馈而无限重放可能产生副作用的命令。
- 涉及坐标范围、大规模覆盖、命令方块逻辑或可能破坏现有建筑的任务，外部 Host 应在首次执行前向用户展示计划并获得确认。该确认由 Host 负责，首版模组不实现确认协议。

## 5. 版本与兼容性策略

### 5.1 首版基线

截至 2026-09-04，最新正式发布的 Minecraft Java Edition 26.x 为 26.2；26.3 仍处于预发布阶段。因此首版编译、测试和发布基线为 Minecraft Java Edition 26.2。

初始技术基线参考 Fabric 对 26.2 的官方建议：

- Fabric Loader：0.19.3 或开发时最新兼容稳定版。
- Fabric Loom：1.17 或开发时最新兼容稳定版。
- Gradle：9.5.1 或 Loom 要求的兼容版本。
- Java：采用 Minecraft 26.2 与 Fabric 工具链要求的版本，初始化项目时锁定并记录。
- 映射：遵循 26.x 的非混淆代码与 Fabric 官方推荐，不以已停止官方支持的 Yarn 作为默认方案。

依赖必须锁定明确版本；升级不得以破坏本 PRD 的接口契约为代价。

### 5.2 MCP 兼容性

- 实现 MCP 2026-07-28 的 Streamable HTTP 传输与工具能力。
- MCP 端点为单一 `POST /mcp`。
- 每个 JSON-RPC 请求独立处理，不依赖协议级 Session。
- 客户端请求的 `Accept` 必须同时包含 `application/json` 与 `text/event-stream`。首版所有短调用统一选择规范允许的 `application/json` 响应，不提供长连接订阅，因此无需主动选择 SSE 响应。
- 必须实现 `server/discover`、`tools/list` 和 `tools/call`。
- `server/discover` 的 result 必须包含 `resultType: "complete"`、`supportedVersions: ["2026-07-28"]`、`capabilities.tools.listChanged: false`、`instructions`、`ttlMs: 3600000`、`cacheScope: "public"`，并在 `result._meta["io.modelcontextprotocol/serverInfo"]` 中返回 `{name: "mcmcp", version: <mod-version>}`。
- `tools/list` 按本 PRD 顺序返回固定的 4 个工具，并返回 `resultType: "complete"`、`ttlMs: 3600000`、`cacheScope: "public"`；首版不分页、不发送工具列表变更通知。
- 不声明未实现的 `resources`、`prompts`、`sampling`、`elicitation` 或订阅能力。
- 所有请求 `params._meta` 必须包含并校验 `io.modelcontextprotocol/protocolVersion` 和 `io.modelcontextprotocol/clientCapabilities`；`io.modelcontextprotocol/clientInfo` 可选，缺失时不得拒绝合法请求。
- 所有 POST 必须校验 `MCP-Protocol-Version` 和 `Mcp-Method` 请求头；`tools/call` 还必须校验 `Mcp-Name`。请求头值必须与请求体对应字段一致。
- 缺少必需头、头与请求体不一致、协议版本不支持时，按 MCP 2026-07-28 定义返回对应 HTTP 状态与 JSON-RPC 错误。
- 首版不承诺兼容 2025-11-25 及更早的旧版 Streamable HTTP 行为。

## 6. 产品形态与总体架构

MCMCP 由以下模块组成：

1. **Fabric Client Mod**：注册客户端生命周期、聊天事件和渲染访问入口。
2. **State Collector**：按请求从客户端线程生成不可变状态快照。
3. **Chat Buffer**：接收聊天框事件并保存在有界内存环形缓冲区。
4. **Screenshot Capturer**：在渲染线程复制当前 framebuffer，编码为 PNG 后返回内存数据。
5. **Command Dispatcher**：校验命令并调度至客户端线程，以当前玩家身份发送。
6. **Embedded MCP Server**：在独立 I/O 线程处理本机 HTTP 与 JSON-RPC 请求。
7. **Configuration**：控制监听端口、缓冲区和限流参数。

线程边界：

- HTTP I/O 线程不得直接访问非线程安全的 Minecraft 对象。
- 状态读取、命令发送通过客户端任务队列调度。
- framebuffer 访问通过渲染线程调度。
- PNG 编码可在复制像素后转移至工作线程，避免长时间阻塞渲染线程。
- 请求等待客户端或渲染线程超过 5 秒时返回超时错误，不无限阻塞。

## 7. MCP 接口定义

首版对外提供 4 个 MCP Tools。工具名称和字段构成稳定 API；在同一主版本内不得删除字段或改变字段语义。

每个工具在 `tools/list` 中必须提供 `name`、`title`、`description`、`inputSchema`、`outputSchema` 和 `annotations`。Schema 使用 JSON Schema 2020-12，根对象及所有固定结构子对象设置 `additionalProperties: false`；本节字段表、示例和字段规则共同构成 Schema 的规范来源。

所有成功工具结果必须包含：

- `resultType: "complete"`。
- `isError: false`。
- `structuredContent`：符合对应 `outputSchema` 的 JSON 值。
- `content`：至少包含一个序列化相同数据的 MCP Text Content；截图工具还包含 MCP Image Content。

完整成功响应的封装形式如下。本节各工具的“成功输出”代码块仅展示其中的 `structuredContent` 值，不重复展示 JSON-RPC 外层。

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "resultType": "complete",
    "content": [
      {"type": "text", "text": "{\"status\":\"submitted\",\"command\":\"/time query daytime\",\"submitted_at\":\"2026-09-04T10:15:30.123Z\"}"}
    ],
    "structuredContent": {
      "status": "submitted",
      "command": "/time query daytime",
      "submitted_at": "2026-09-04T10:15:30.123Z"
    },
    "isError": false
  }
}
```

所有时间使用 UTC RFC 3339 字符串；坐标使用 Minecraft 客户端可见的双精度值。

### 7.1 `minecraft_execute_command`

- Title：`Execute Minecraft Command`
- Annotations：`readOnlyHint: false`、`destructiveHint: true`、`idempotentHint: false`、`openWorldHint: true`

以当前玩家身份向当前单人世界或多人服务器发送一条斜杠命令。

#### 输入

```json
{
  "command": "time query daytime"
}
```

| 字段 | 类型 | 必填 | 约束 |
|---|---|---:|---|
| `command` | string | 是 | 必须以 `/` 开头；移除第一个 `/` 后按 Java `String.length()` 计为 1–256；不得包含 `§`、控制字符或游戏 26.2 判定为非法的聊天字符；不得有首尾空白 |

`inputSchema` 只约束 `command` 为必填非空 string 且不接受额外字段；前导 `/`、去除 `/` 后的游戏长度、首尾空白与 26.2 字符合法性由工具业务校验，失败返回 `INVALID_ARGUMENT`。

#### 行为

- 模组校验完整输入后移除开头 `/`，通过 Minecraft 26.2 官方客户端连接的命令发送入口发送；不得自行构造或直接发送底层命令数据包。
- 使用游戏原生签名链路处理含 signed message argument 的命令。若签名或参数准备失败，返回 `COMMAND_REJECTED`，不得降级到不安全的自定义发包。
- 仅允许斜杠命令语义；不以 `/` 开头的文本直接返回 `INVALID_ARGUMENT`，不得回退为普通聊天消息。
- 在调用游戏 API 前完成长度和字符校验，绝不依赖游戏 API 静默截断超长命令。
- 玩家未进入世界、网络连接不可用或命令为空时不得发送。
- 返回“已提交”只表示客户端已将命令交给游戏连接，不表示服务端接受或执行成功。
- 服务端后续返回的文本由聊天工具读取，但首版不将其与本调用建立因果关联。

#### 成功输出

```json
{
  "status": "submitted",
  "command": "/time query daytime",
  "submitted_at": "2026-09-04T10:15:30.123Z"
}
```

### 7.2 `minecraft_get_chat_messages`

- Title：`Get Minecraft Chat Messages`
- Annotations：`readOnlyHint: true`、`destructiveHint: false`、`idempotentHint: true`、`openWorldHint: true`

读取模组在当前 Minecraft 客户端运行期间捕获的聊天框消息。

#### 输入

```json
{
  "after_id": 120,
  "limit": 50,
  "types": ["chat", "system"]
}
```

| 字段 | 类型 | 必填 | 默认值 | 约束 |
|---|---|---:|---|---|
| `after_id` | integer | 否 | 无 | 仅返回 `id > after_id` 的消息；必须大于等于 0 |
| `limit` | integer | 否 | 50 | 1–200 |
| `types` | string[] | 否 | 全部 | 可选值：`chat`、`system` |

#### 消息范围

- `chat`：显示在聊天 HUD 中的玩家聊天消息。
- `system`：显示在聊天 HUD 中的系统、命令反馈或服务器消息。
- 不包含 action bar、标题、Boss Bar、记分板、Toast 或日志文件内容。
- 只保证返回模组注册监听器之后捕获的消息，不读取模组启动前的历史。
- 切换服务器或世界不会重置全局递增 `id`，但会更新 `session_id`；客户端完全退出后缓冲区消失。
- 默认按 `id` 升序返回。未提供 `after_id` 时返回符合条件的最近 `limit` 条；提供 `after_id` 时返回 `id > after_id` 的最早 `limit` 条，调用方可用 `next_after_id` 继续增量读取。

#### 成功输出

```json
{
  "messages": [
    {
      "id": 121,
      "session_id": "550e8400-e29b-41d4-a716-446655440000",
      "type": "system",
      "plain_text": "The time is 6000",
      "received_at": "2026-09-04T10:15:31.004Z",
      "game_tick": 48291
    }
  ],
  "oldest_available_id": 22,
  "newest_available_id": 121,
  "next_after_id": 121,
  "has_more": false,
  "gap_detected": false,
  "evicted_message_count": 0
}
```

字段说明：

- `plain_text` 必须是去除样式后的可读文本。
- `session_id` 是每次进入新 play connection 时生成的不透明 UUID v4 字符串；调用方不得依赖其排序语义。
- `game_tick` 在未进入世界或无法获得时为 `null`。
- 不承诺可靠解析发送者名称、UUID、签名、点击事件或悬浮事件，首版不暴露这些字段。
- `next_after_id` 为本次最后一条返回消息的 ID；结果为空时等于输入 `after_id`，未提供 `after_id` 且结果为空时为 `null`。
- 仅在提供 `after_id` 时使用 `has_more`，表示缓冲区中是否仍有符合过滤条件且 ID 更大的消息；未提供时固定为 `false`。
- `evicted_message_count` 表示因环形缓冲区满而从内存中移除的累计消息数。
- 提供 `after_id` 且已有 ID 更大的消息被淘汰时，`gap_detected` 为 `true`；工具仍返回当前可用消息。调用方不应从累计淘汰数推算本次缺失的过滤后消息数。
- 缓冲区为空时，`oldest_available_id` 和 `newest_available_id` 均为 `null`。未提供 `after_id` 时空结果的 `next_after_id` 为 `null`；提供时等于输入的 `after_id`。

### 7.3 `minecraft_get_game_state`

- Title：`Get Minecraft Game State`
- Annotations：`readOnlyHint: true`、`destructiveHint: false`、`idempotentHint: true`、`openWorldHint: true`

获取调用时刻的结构化游戏状态快照。

#### 输入

```json
{
  "sections": ["client", "connection", "player", "world", "debug", "target"]
}
```

| 字段 | 类型 | 必填 | 默认值 | 约束 |
|---|---|---:|---|---|
| `sections` | string[] | 否 | 全部 | 可选值：`client`、`connection`、`player`、`world`、`debug`、`target`；不得重复 |

`ready=true` 当且仅当当前 world 与 player 对象均存在且属于当前客户端会话；否则为 `false`。未进入世界、加载切换、死亡重生对象暂不可用时工具仍成功返回 `client` 和 `connection`，`player`、`world`、`debug`、`target` 均为 `null`，`game_tick` 为 `null`。未请求的 section 对应顶层字段省略，而不是返回 `null`。

`outputSchema` 始终要求 `captured_at`、`game_tick` 和 `ready`。调用请求中的每个 section 所对应顶层属性必须出现，未请求的必须省略；`player`、`world`、`debug`、`target` 在 `ready=false` 时为 `null`。section 为对象时，本节列出的固定子字段全部为 required；本节明确允许客户端不可得的字段在 Schema 中使用 nullable 类型。

#### 成功输出

```json
{
  "captured_at": "2026-09-04T10:15:32.017Z",
  "game_tick": 48292,
  "ready": true,
  "client": {
    "minecraft_version": "26.2",
    "mod_version": "0.1.0",
    "fps": 120,
    "screen": null,
    "paused": false,
    "window_focused": true,
    "gui_scale": 3
  },
  "connection": {
    "connected": true,
    "type": "multiplayer",
    "server_address": "example.org:25565",
    "latency_ms": 42
  },
  "player": {
    "name": "Player",
    "uuid": "00000000-0000-0000-0000-000000000000",
    "game_mode": "survival",
    "position": {"x": 10.5, "y": 64.0, "z": -3.25},
    "block_position": {"x": 10, "y": 64, "z": -4},
    "rotation": {"yaw": 90.0, "pitch": 5.0},
    "velocity": {"x": 0.0, "y": 0.0, "z": 0.0},
    "on_ground": true,
    "sprinting": false,
    "sneaking": false,
    "swimming": false,
    "fall_flying": false,
    "health": 20.0,
    "max_health": 20.0,
    "absorption": 0.0,
    "food": 20,
    "saturation": 5.0,
    "air": 300,
    "max_air": 300,
    "armor": 0,
    "experience_level": 3,
    "experience_progress": 0.4,
    "selected_hotbar_slot": 0,
    "selected_item": {
      "id": "minecraft:diamond_pickaxe",
      "count": 1,
      "display_name": "Diamond Pickaxe"
    },
    "status_effects": [
      {
        "id": "minecraft:speed",
        "amplifier": 0,
        "duration_ticks": 120,
        "ambient": false,
        "show_particles": true
      }
    ]
  },
  "world": {
    "dimension": "minecraft:overworld",
    "biome": "minecraft:plains",
    "difficulty": "normal",
    "hardcore": false,
    "day": 2,
    "time_of_day": 6000,
    "raining": false,
    "thundering": false,
    "light": {"block": 0, "sky": 15}
  },
  "debug": {
    "coordinates": {
      "precise": {"x": 10.5, "y": 64.0, "z": -3.25},
      "block": {"x": 10, "y": 64, "z": -4},
      "chunk": {"x": 0, "y": 4, "z": -1},
      "in_chunk": {"x": 10, "y": 0, "z": 12},
      "chunk_origin": {"x": 0, "y": 64, "z": -16},
      "region": {"x": 0, "z": -1}
    },
    "facing": {
      "direction": "west",
      "axis": "x",
      "towards": "negative_x",
      "yaw": 90.0,
      "pitch": 5.0
    },
    "chunk": {
      "loaded": true,
      "status": "minecraft:full",
      "local_difficulty": 1.5,
      "inhabited_time_ticks": 24000,
      "heightmaps": {
        "world_surface": 70,
        "motion_blocking": 68
      }
    },
    "render": {
      "render_distance_chunks": 12,
      "simulation_distance_chunks": 12,
      "fps": 120,
      "frame_time_ms": 8.3,
      "chunks_rendered": 729,
      "entities_rendered": 37,
      "entities_loaded": 84,
      "particles": 12
    },
    "system": {
      "java_version": "25.0.2",
      "memory_used_mib": 2048,
      "memory_allocated_mib": 4096,
      "memory_max_mib": 8192,
      "cpu": "Apple M4",
      "gpu": "Apple M4",
      "display_width": 1920,
      "display_height": 1080,
      "graphics_backend": "OpenGL"
    }
  },
  "target": {
    "type": "block",
    "distance": 3.2,
    "block": {
      "id": "minecraft:oak_log",
      "position": {"x": 11, "y": 64, "z": -1},
      "side": "north",
      "properties": {"axis": "y"},
      "tags": ["minecraft:logs", "minecraft:logs_that_burn"]
    },
    "fluid": null,
    "entity": null
  }
}
```

#### 字段规则

- `screen` 为当前 GUI Screen 标识；无 GUI 时为 `null`。首版必须稳定映射以下原版界面：标题 `minecraft:title`、暂停 `minecraft:pause`、聊天 `minecraft:chat`、背包 `minecraft:inventory`、创造背包 `minecraft:creative_inventory`、死亡 `minecraft:death`、进度 `minecraft:advancements`、设置 `minecraft:options`、多人列表 `minecraft:multiplayer`、世界选择 `minecraft:select_world`、断线 `minecraft:disconnected`、通用容器 `minecraft:container`。其他原版或第三方 Screen 使用 `class:<fully-qualified-class-name>`，不承诺跨版本稳定。不得使用本地化显示名。
- `connection.type` 可选值为 `none`、`singleplayer`、`lan`、`multiplayer`、`realms`；无法可靠区分时使用最接近值。
- `server_address` 在单人游戏中为 `null`。该字段包含隐私信息，但首版按产品用途默认返回。
- `latency_ms`、`game_mode`、`biome`、`hardcore` 等客户端无法可靠取得的字段可为 `null`，不得伪造。
- 数值使用游戏客户端当前可见值，不保证与服务端同一 tick 完全一致。
- `player.position` 和 `player.block_position` 是玩家位置的主字段；`debug.coordinates.precise` 与 `debug.coordinates.block` 必须在同一快照中与之完全一致，重复字段用于让 `debug` section 可独立使用。
- `debug.coordinates.chunk` 是玩家所在 16 × 16 × 16 区块分段坐标，即对方块 X/Y/Z 分别执行向负无穷取整除以 16；`in_chunk` 为各轴 0–15 的区块内坐标。负坐标必须使用 floor division/floor modulus，不得使用向零截断。
- `chunk_origin` 是该区块分段最小角的方块坐标；`region` 是 X/Z 区块坐标分别 floor-divide 32 的区域坐标。
- `debug.facing.direction` 为 `north`、`south`、`west`、`east`，`axis` 为 `x` 或 `z`，`towards` 为 `positive_x`、`negative_x`、`positive_z` 或 `negative_z`；yaw/pitch 与 `player.rotation` 一致。
- `debug.chunk` 描述玩家所在客户端区块。`status` 使用资源式标识；`local_difficulty`、`inhabited_time_ticks` 或高度图未同步/无稳定 API 时为 `null`。高度图数值表示玩家 X/Z 处对应类型的最高 Y。
- `debug.render.frame_time_ms` 为最近 1 秒已完成帧的平均时长，不得简单以单次 `1000 / fps` 伪造；其余计数使用 Minecraft 调试渲染器同源数据。无法从稳定客户端 API 获得时字段为 `null`。
- `debug.system` 仅返回 F3 已可见或 JVM/渲染后端公开的信息；内存单位为 MiB，CPU/GPU 字符串按底层接口原样返回。不得额外采集设备序列号、用户名、文件路径、IP 或其他系统标识。
- `debug` 数据直接从结构化游戏/JVM/渲染状态采集，不通过打开 F3 界面或解析本地化 F3 文本获得；调用不得改变 F3 显示状态。
- `selected_item` 为空手时为 `null`；`display_name` 返回去除样式后的纯文本。
- `status_effects` 按资源 ID 排序，以获得稳定输出；无限时长效果的 `duration_ticks` 为 `null`。
- `target.type` 可选值为 `miss`、`block`、`entity`。`distance` 字段始终存在；`miss` 时 `distance`、`block`、`fluid` 和 `entity` 均为 `null`。
- `block.side` 可选值为 `down`、`up`、`north`、`south`、`west`、`east`；`properties` 为按键名排序的字符串键值对象，`tags` 为按资源 ID 排序的数组。
- `target.fluid` 在目标位置无流体时为 `null`，否则返回 `{id, properties, tags}`，排序规则与方块一致。
- `target.entity` 的结构固定为 `{id, type, display_name, uuid, position, distance}`；`id` 是当前客户端世界内的整数实体 ID，`type` 是实体类型资源 ID，`display_name` 是纯文本。仅返回客户端已知数据，不返回完整 NBT。
- 首版不返回完整背包、附近实体列表、完整区块方块数据、种子、NBT 或记分板。

### 7.4 `minecraft_capture_screenshot`

- Title：`Capture Minecraft Screenshot`
- Annotations：`readOnlyHint: true`、`destructiveHint: false`、`idempotentHint: true`、`openWorldHint: false`

捕获最近一个已完成渲染帧所对应的 Minecraft 窗口 framebuffer，并返回 PNG。

#### 输入

```json
{
  "max_width": 1280
}
```

| 字段 | 类型 | 必填 | 默认值 | 约束 |
|---|---|---:|---|---|
| `max_width` | integer | 否 | 3840 | 1–3840；输出宽度为 `min(source_width, max_width)`，等比例缩小且不放大 |

#### 行为

- 截图内容与玩家当前看到的 Minecraft 窗口一致，包括 HUD、聊天框、调试界面、暂停菜单或其他当前 GUI。
- 仅捕获 Minecraft framebuffer，不捕获桌面、其他窗口或窗口边框。
- 输出固定为 PNG、sRGB、无额外文件落盘。
- 输出宽度硬上限为 3840；未传 `max_width` 等价于传入 3840，源画面更窄时不放大。
- 工具响应的 `content` 包含一个 `image` 内容块，`mimeType` 为 `image/png`，数据按 MCP 规范使用 Base64 表示；另附一个文本内容块描述元数据。
- 同一时刻只处理一个截图请求；超过并发或速率限制时立即报错，不排无限队列。
- 最小化窗口、渲染上下文不可用或 framebuffer 尺寸为 0 时返回错误。

#### 结构化元数据

```json
{
  "captured_at": "2026-09-04T10:15:33.120Z",
  "width": 1280,
  "height": 720,
  "mime_type": "image/png",
  "source_width": 1920,
  "source_height": 1080
}
```

`structuredContent` 不重复包含 Base64 图像数据。

## 8. 错误模型

工具执行错误通过 MCP Tool Result 的 `resultType: "complete"` 与 `isError: true` 返回。为避免成功 `outputSchema` 与错误结构冲突，错误结果不包含 `structuredContent`，机器可读错误对象以 JSON 文本放入 MCP Text Content：

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "resultType": "complete",
    "content": [
      {
        "type": "text",
        "text": "{\"error\":{\"code\":\"GAME_NOT_READY\",\"message\":\"The player has not entered a world.\",\"retryable\":true}}"
      }
    ],
    "isError": true
  }
}
```

未知工具、JSON-RPC 结构错误、MCP 必需元数据/请求头错误等协议错误使用 JSON-RPC Error Response；参数通过 `inputSchema` 后发生的游戏状态、业务约束或运行时失败使用上述工具执行错误。

首版工具执行错误码：

| 错误码 | 场景 | 可重试 |
|---|---|---:|
| `INVALID_ARGUMENT` | 已通过结构 Schema，但违反工具语义约束（如命令缺少 `/`） | 否 |
| `GAME_NOT_READY` | 尚未进入世界或正在切换世界 | 是 |
| `PLAYER_NOT_AVAILABLE` | 当前玩家对象不可用 | 是 |
| `CONNECTION_NOT_AVAILABLE` | 命令所需连接不可用 | 是 |
| `COMMAND_REJECTED` | 命令被客户端侧校验拒绝 | 否 |
| `SCREENSHOT_UNAVAILABLE` | framebuffer 或渲染上下文不可用 | 是 |
| `RATE_LIMITED` | 超过工具限流 | 是 |
| `BUSY` | 截图等独占操作正在执行 | 是 |
| `TIMEOUT` | 等待客户端线程或渲染线程超时 | 是 |
| `INTERNAL_ERROR` | 未分类内部错误 | 视情况 |

HTTP/JSON-RPC 层的非法请求、未知方法和协议不兼容按 MCP 与 JSON-RPC 规范返回；游戏业务错误不得伪装为 HTTP 500。

## 9. 聊天数据设计

- 使用内存环形缓冲区，默认最多保存 1000 条消息，配置范围 100–10000。
- 每条消息进入缓冲区时生成进程内单调递增的 64 位 `id`。
- 每次连接世界生成新的 `session_id`；断开连接后保留缓冲区，便于 Agent 读取断开前反馈。
- 相同文本的重复消息分别保留，不去重。
- 不将消息写入日志；调试日志也只记录消息 ID、类型和长度。
- 文本必须安全序列化为 JSON；保留 Unicode，替换无法编码的非法序列。
- 若 Fabric 事件不足以覆盖全部聊天 HUD 消息，可使用最小范围 Mixin 接入统一的 HUD 入队点，但不得重复捕获。

## 10. 配置需求

配置文件位置遵循 Fabric Config 目录惯例，建议为 `config/mcmcp.json`。首次启动生成默认配置：

```json
{
  "enabled": true,
  "port": 25585,
  "chat_buffer_size": 1000,
  "request_timeout_ms": 5000,
  "requests_per_second": 20,
  "screenshots_per_second": 1
}
```

约束：

- MCP endpoint 固定为 `/mcp`，不提供 endpoint 配置项，也不得从环境变量或 JVM 参数覆盖。
- 首版不提供 `host` 配置项，代码固定绑定 `127.0.0.1`，不得根据 `localhost` 解析结果或环境变量改变监听地址。
- 端口允许范围为 1024–65535。
- 端口被占用时游戏继续运行，MCP 服务保持不可用，并在日志与游戏内提示一次错误。
- 修改配置后重启客户端生效；首版不要求热重载。
- 不提供关闭 Origin 校验、开放 CORS 或监听局域网的隐藏开关。

## 11. 安全与隐私

首版按用户选择采用“仅限本机、无额外令牌”策略。由于命令工具可产生游戏内副作用，必须满足：

1. 只绑定 IPv4 回环地址 `127.0.0.1`；不得绑定 `0.0.0.0`、公网或局域网地址。
2. 严格校验 `Host`，只接受 `127.0.0.1:<configured-port>` 或 `localhost:<configured-port>`，其他值返回 HTTP 403。
3. 仅接受不含 `Origin` 头的本机原生 MCP 客户端请求；任何包含 `Origin` 的请求（包括 `null`、localhost 或 127.0.0.1 Origin）均返回 HTTP 403，以降低浏览器跨站与 DNS rebinding 风险。
4. 不发送 CORS 允许头，不提供网页内容，不支持浏览器调用。
5. 限制请求体大小，上限为 1 MiB；超过上限返回 HTTP 413。
6. 命令拒绝换行、NUL、`§`、控制字符和游戏判定的非法字符，防止单次请求注入多条消息或触发断线。
7. 对总请求和截图分别限流；限流在进入 Minecraft 主线程前执行。
   - 已成功解析的 `tools/call` 超限时，返回 HTTP 200 的 `RATE_LIMITED` Tool Result。
   - `server/discover` 或 `tools/list` 超过总请求限流时，返回 HTTP 429 和 JSON-RPC error code `-32000`，`error.data` 包含 `{ "code": "RATE_LIMITED", "retryable": true }`。
   - 在请求完成解析前因连接数、in-flight 或执行队列过载而拒绝时，返回 HTTP 503；不得把此类过载任务排入 Minecraft 主线程。
8. MCP 服务启动成功后，在日志中明确记录监听地址、端口和“未启用鉴权”；不得记录聊天正文、截图 Base64 或完整状态响应。
9. 玩家进入世界后，应提供一次非阻塞游戏内提示，说明本机程序可通过 MCP 读取画面与状态并执行命令；每次命令工具成功提交时提供可见但不阻塞的调用提示。
10. 模组卸载、客户端退出或禁用服务时立即停止 HTTP Listener，不保留后台进程。
11. 输出可能包含服务器地址、玩家 UUID、聊天内容、画面、CPU/GPU 型号和内存配置等敏感信息；PRD 不提供字段级脱敏，但 README 必须明确告知。

已知残余风险：同一操作系统账户下的其他本地进程可以直接连接本服务。无鉴权是首版明确接受的产品约束，而不是安全保证。后续版本应优先增加随机令牌。

## 12. 生命周期与降级行为

| 客户端状态 | MCP 服务 | 聊天读取 | 状态读取 | 命令执行 | 截图 |
|---|---|---|---|---|---|
| 主菜单 | 可用 | 可读已有缓冲 | 返回客户端状态，`ready=false` | 错误 | 可用 |
| 加载世界 | 可用 | 可读 | `ready=false`，世界相关字段为 `null` | 立即返回 `GAME_NOT_READY`，不等待、不发送 | 可用或明确报错 |
| 游戏内 | 可用 | 可用 | 可用 | 可用 | 可用 |
| 断开连接界面 | 可用 | 可读断开前缓冲 | 返回 `ready=false` | 错误 | 可用 |
| MCP 端口占用 | 不可用 | 不适用 | 不适用 | 不适用 | 不适用；游戏本身继续运行 |
| 客户端退出 | 停止 | 不适用 | 不适用 | 不适用 | 不适用 |

## 13. 非功能需求

### 13.1 稳定性

- MCP 请求异常不得导致 Minecraft 客户端崩溃。
- 客户端线程任务必须有超时与异常边界。
- HTTP 客户端断开后取消尚未开始的任务；已提交的命令不可承诺撤销。
- 世界切换期间使用生成号或会话标识避免读取已失效对象。

### 13.2 性能与资源

- 聊天缓冲区有固定上限，不随运行时间无限增长。
- 截图编码后的 Base64 和像素缓冲应在响应结束后可回收，不做历史缓存。
- HTTP Server 使用有界线程池和有界请求队列。
- 不在每个 game tick 主动构造完整状态 JSON；仅按请求采集。
- CPU、GPU、Java 版本等静态调试字段在初始化时采集并缓存；玩家、世界、区块、渲染和内存动态字段按请求读取。
- 默认最大并发处理中请求数建议为 8，截图并发为 1。

### 13.3 可观测性

日志至少包括：

- 模组与 Minecraft 版本。
- MCP 服务启动、停止、监听失败。
- 请求方法、工具名、耗时、结果类别和错误码。
- 限流、超时与线程调度失败。

日志不得包括：

- 完整命令文本或命令摘要；仅可记录命令长度、结果类别与错误码。
- 聊天正文。
- 截图数据。
- 完整状态响应。

### 13.4 可维护性

- MCP 协议层与 Minecraft 适配层分离。
- 工具输入输出使用显式 DTO 和 JSON Schema，不直接序列化 Minecraft 内部对象。
- 面向 Minecraft API 的代码集中在适配层，为后续 26.x 升级保留边界。
- 优先使用 Fabric API 事件；仅在无公开事件时使用范围最小的 Mixin。

## 14. 验收标准

### 14.1 MCP 与网络

- [ ] MCP Inspector 可通过 `http://127.0.0.1:25585/mcp` 完成 `server/discover`、`tools/list` 和 4 个工具调用。
- [ ] `server/discover`、`tools/list` 和 `tools/call` 响应包含 MCP 2026-07-28 要求的 `resultType`、能力、身份与缓存字段。
- [ ] 缺少或错误的 `MCP-Protocol-Version`、`Mcp-Method`、适用请求中的 `Mcp-Name`、必需请求 `_meta` 或 Accept 类型会被规范化拒绝；头与请求体不一致时返回 HeaderMismatch；缺少可选 `clientInfo` 不影响调用。
- [ ] 工具列表只包含本 PRD 定义的 4 个工具，顺序稳定，名称、Title、描述、输入/输出 Schema 与 annotations 正确。
- [ ] 服务未监听 IPv6 通配地址、`0.0.0.0`、LAN IP 或公网 IP。
- [ ] 任意带 `Origin` 的请求或非法 `Host` 返回 403；无 Origin 且 Host 合法的本机原生 MCP 客户端可以连接。
- [ ] 超过 1 MiB 的请求返回 413；畸形 JSON-RPC 不导致游戏崩溃。
- [ ] 端口占用时提示错误，但 Minecraft 可正常进入世界。

### 14.2 命令

- [ ] 单人世界允许作弊时，调用 `minecraft_execute_command` 能提交有效命令。
- [ ] 多人服务器中，命令以当前玩家身份发送并受服务端权限控制，含 signed message argument 的合法命令走游戏原生签名链路。
- [ ] 输入必须以 `/` 开头且发送前仅移除第一个 `/`；不带 `/` 的普通文本被拒绝，`//` 开头的合法第三方命令不被错误改写。
- [ ] 空命令、多行命令、移除 `/` 后超过 256 个 Java 字符的命令、包含 `§` 或游戏非法字符的命令被拒绝且绝不被截断发送。
- [ ] 未进入世界或已断线时不发送命令，并返回稳定错误码。
- [ ] 返回值不虚假声明服务端已成功执行命令。
- [ ] 命令成功提交时玩家能看到非阻塞的 MCP 调用提示。

### 14.3 聊天

- [ ] 玩家聊天和系统/命令反馈均被捕获一次，不重复、不遗漏正常事件。
- [ ] `after_id`、`limit`、`types` 过滤符合定义，结果顺序稳定。
- [ ] 缓冲区满时淘汰最旧消息并正确更新 `oldest_available_id`、`evicted_message_count` 与 `gap_detected`。
- [ ] 断开世界后仍可读取已有消息，重新进入世界后 `session_id` 改变。
- [ ] 样式复杂、Unicode 和超长消息能够安全返回。

### 14.4 状态

- [ ] 主菜单调用成功，`ready=false`、`game_tick=null` 且世界相关部分为 `null`。
- [ ] 仅当当前 world 和 player 均有效时 `ready=true`；游戏内输出与 HUD/F3 可观察值在合理误差内一致。
- [ ] `player.position`、`player.block_position` 与同一响应中的 `debug.coordinates` 一致；正坐标、负坐标、区块边界和世界高度边界下的 chunk、in-chunk、chunk-origin、region 计算正确。
- [ ] 朝向、维度、生物群系、光照、本地难度、区块加载/状态、渲染距离、模拟距离、FPS、帧耗时、区块/实体/粒子计数和内存数据与客户端同源调试值一致；客户端不可得字段返回 `null`。
- [ ] 获取 `debug` 不会自动打开、关闭或改变玩家的 F3 调试界面，且不通过解析本地化屏幕文本实现。
- [ ] 维度切换、死亡重生、玩家对象替换期间不崩溃且不返回失效引用。
- [ ] `sections` 会省略未请求的顶层字段，未知或重复 section 被拒绝，单独请求 `debug` 可获得自洽快照。
- [ ] 缺少客户端数据时返回 `null`，不使用猜测值；无准星目标时 `target.distance`、`block`、`fluid`、`entity` 均为 `null`。
- [ ] 目标方块/流体的资源 ID、状态属性及标签与 F3 目标信息一致，并保持确定性排序。
- [ ] 本 PRD 列出的原版 Screen 返回对应稳定标识，未知 Screen 返回 `class:<fully-qualified-class-name>`。

### 14.5 截图

- [ ] 工具结果包含 `resultType: "complete"`、序列化元数据的 Text Content、合法可解码的 PNG MCP Image Content 和符合 `outputSchema` 的 `structuredContent`。
- [ ] Image Content 使用 MCP 字段 `type: "image"`、`mimeType: "image/png"` 和 Base64 `data`；`structuredContent` 仅含元数据且不重复图像数据。
- [ ] 截图方向、颜色和宽高正确，不上下颠倒或通道错位。
- [ ] `max_width` 只缩小不放大，宽高比保持不变。
- [ ] HUD、聊天和当前 GUI 与屏幕显示一致。
- [ ] 不创建本地截图文件。
- [ ] 并发和频率超限返回 `BUSY` 或 `RATE_LIMITED`。
- [ ] 最小化窗口或 framebuffer 不可用时返回稳定错误，不导致崩溃。

### 14.6 端到端案例

在一次性测试世界中，使用至少一种兼容 MCP Host/模型完成以下验收；此项验证四个工具能共同支撑场景，不把模型输出质量归因于模组协议实现：

- [ ] 自然语言请求高等级附魔物品时，模型能读取版本/状态、提交命令、读取反馈，并在无权限时正确报告失败。
- [ ] 使用缩小规模的中空彩色羊毛立方体测试批量建筑流程，验证锚点、按 Y 交替颜色、命令拆分、反馈读取与阶段截图；100 × 100 × 100 方案须通过命令计划审查，但不要求在每个平台回归测试中实际完整建造。
- [ ] 使用一次性区域搭建“玩家经过后设置出生点”的命令方块系统，验证坐标确认、命令方块配置和实际触发。
- [ ] 向外部 Host 提供一张简单建筑参考图，验证图片不发送给 MCMCP，模型能根据参考图、状态和游戏截图完成分阶段近似复现。
- [ ] 大规模覆盖或命令方块案例在首条修改命令前由 Host 展示计划并获得测试用户确认。

### 14.7 回归环境

至少覆盖：

- macOS、Windows、Linux 各一次冒烟测试。
- 单人世界、无权限多人服务器、有命令权限多人服务器。
- 主菜单、加载、游戏内、暂停界面、断线界面。
- 原版客户端与安装常见渲染优化模组的兼容性冒烟测试；具体模组列表在开发计划中确定。

## 15. 发布要求

首个可发布版本必须包含：

- Fabric 模组 JAR。
- 安装方式、版本要求和 MCP Host 配置示例。
- 4 个工具的接口说明和示例。
- 无鉴权、本机进程可访问、命令副作用及隐私数据暴露警告。
- 已验证平台、已知兼容性问题和故障排查说明。
- 第三方依赖许可证清单。

## 16. 后续版本候选

以下内容不进入首版承诺：

1. 随机 Bearer Token 与操作系统安全存储。
2. MCP Resources 与状态/聊天变更订阅。
3. 按字段隐藏服务器地址、UUID、聊天或 HUD 的隐私模式。
4. 截图时隐藏 HUD、指定缩放质量或只截取游戏世界。
5. 完整背包、附近实体、配方、记分板等可选状态 section。
6. 命令 allowlist/denylist、用户逐次确认和命令审计面板。
7. stdio 本地桥接器。
8. 多 Minecraft 版本适配。

## 17. 风险与待验证项

| 风险/待验证项 | 影响 | 应对 |
|---|---|---|
| Minecraft 26.2 渲染后端可切换 OpenGL/Vulkan | framebuffer 读取方式可能不同 | 通过 Blaze3D/官方抽象访问，不直接依赖裸 OpenGL；分别验证可用后端 |
| Fabric/Minecraft 无统一聊天事件覆盖所有 HUD 消息 | 消息遗漏或重复 | 开发 Spike 验证事件；必要时对 HUD 入队点使用最小 Mixin |
| Java MCP SDK 与 Minecraft 所需 Java/依赖发生冲突 | 体积、类加载或运行时冲突 | 开发前评估 Java SDK；必要时实现最小协议层并 shade/relocate 第三方依赖 |
| 截图编码阻塞渲染线程 | 掉帧 | 渲染线程只复制像素，工作线程缩放和编码，截图限频 |
| 本机无鉴权服务可被其他进程调用 | 命令与隐私风险 | 严格回环绑定、Host 校验、拒绝所有带 Origin 的请求、限流、逐次命令提示；后续优先增加令牌 |
| 服务端命令反馈无法可靠关联调用 | Agent 可能误判 | 返回 `submitted` 而非 `succeeded`，由 Agent 独立读取后续聊天 |
| 26.3 正式版发布后“最新版本”发生变化 | 基线过时 | 首版继续锁定 26.2；版本升级作为独立兼容性任务，不静默变更 |

## 18. 参考资料

- Minecraft Java Edition 26.2：https://www.minecraft.net/en-us/article/minecraft-java-edition-26-2
- Fabric for Minecraft 26.2：https://fabricmc.net/2026/06/15/262.html
- MCP 2026-07-28 Streamable HTTP：https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http
- MCP 2026-07-28 Base Protocol：https://modelcontextprotocol.io/specification/2026-07-28/basic/index
- MCP 2026-07-28 Discovery：https://modelcontextprotocol.io/specification/2026-07-28/server/discover
- MCP 2026-07-28 Tools：https://modelcontextprotocol.io/specification/2026-07-28/server/tools
- MCP Architecture：https://modelcontextprotocol.io/docs/2026-07-28/learn/architecture
