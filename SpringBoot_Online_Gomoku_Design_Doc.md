# 在线五子棋对战与观战系统设计文档 (System Design Document)

## 1. 项目概述与目标 (Project Overview & Objectives)

本项目旨在基于 **Spring Boot** 构建一个轻量级、高并发且具备高可用性与状态自愈能力的在线五子棋（Gomoku）对战与观战平台。系统支持双人在线实时对战、单人人机对战（PvE）、多人实时观战（Spectating）以及局内实时聊天互动（In-Game Chat）。

本文档遵循现代分布式与实时系统工程设计规范，全面重构了系统架构、安全与授权边界、核心领域模型、并发串行化模型、裁判算法、实时通信协议、内置 AI 决策引擎以及异常恢复机制。本文档作为 AI Coding Agent 或工程团队分步落地实施的标准设计蓝图。

---

## 2. 系统架构与技术选型 (System Architecture & Technology Stack)

### 2.1 架构分层模式 (Architectural Modes)

为保证架构方案的闭环与工程落地可行性，系统架构明确划分为两套递进模式：

#### 1. 单机生产级 MVP 模式 (Single-Instance Production-Ready MVP - 本期实现基准)
* **后端框架 (Backend Framework)**: Java 17 / 21 + Spring Boot 3.x
* **实时通信 (Real-Time Communication)**: Spring WebSocket + STOMP 协议，采用内置 **Simple In-Memory Broker (内存消息代理)**。
* **认证与授权 (Authentication & Authorization)**: 
  * WebSocket `CONNECT` 阶段通过 **Channel Interceptor (通道拦截器)** 校验 JWT (JSON Web Token) 或分配唯一的 **Guest Session Token (访客会话令牌)**。
  * 服务端统一注入 **Principal (安全主体)**，杜绝任何客户端身份伪造。
  * WebSocket `SUBSCRIBE` 阶段实施严格的房间订阅鉴权。
* **状态与并发管理 (State & Concurrency)**:
  * 房间注册池：`ConcurrentHashMap<String, GameRoom>` 内存管理。
  * 房间级串行化边界：每个 `GameRoom` 独占实例级 **ReentrantLock (可重入互斥锁)**，所有写操作统一经过服务层串行调度。
* **定时与调度 (Scheduling)**:
  * 基于 Spring 内置 `ThreadPoolTaskScheduler`，任务携带状态版本快照以消除竞态。
* **内置 AI 引擎 (In-Process AI Engine)**:
  * 纯进程内基于 **Minimax (极大极小值算法)** 与 **Alpha-Beta Pruning (Alpha-Beta 剪枝)** 实现，不依赖任何外部大语言模型 (LLM) 或商业 API。
  * 采用独立有界线程池隔离 AI 算力，保障 WebSocket I/O 线程池低延迟。
* **前端参考 (Frontend Reference)**:
  * HTML5 Canvas + STOMP.js + SockJS (或 Vue 3 / React)。

#### 2. 分布式扩展模式 (Scale-Out Architecture - 远期演进蓝图)
* 当单机容量达到瓶颈时平滑演进：
  * **跨节点消息路由**: 采用 RabbitMQ 或 Redis Pub/Sub 作为 STOMP External Broker。
  * **房间分区与所有权 (Room Affinity & Ownership)**: 采用基于一致性哈希 (Consistent Hashing) 的房间粘性路由，或基于 Redis + Redisson 实现分布式锁与房间快照同步，不在跨节点环境下直接使用本地锁保护共享状态。

### 2.2 系统架构拓扑图 (High-Level Architecture)

```
[ Frontend Clients (Browser) ]
       │          ▲
 HTTP  │          │ WebSocket (STOMP via /ws-gomoku)
 (REST)│          │ Headers: Authorization: Bearer <Token>
       ▼          ▼
┌──────────────────────────────────────────────────────────┐
│              Spring Boot Application Server              │
│                                                          │
│  ┌───────────────────────┐    ┌───────────────────────┐  │
│  │   REST Controllers    │    │ STOMP Channel Inbound │  │
│  │ (Auth / Room List)    │    │ (Auth & Sub Filter)   │  │
│  └───────────┬───────────┘    └───────────┬───────────┘  │
│              │                            │              │
│  ┌───────────▼────────────────────────────▼───────────┐  │
│  │               GameRoomService                      │  │
│  │     (Serialization Boundary / ReentrantLock)       │  │
│  │  - State Machine     - GomokuReferee (Win Check)   │  │
│  │  - Version Control   - Timer Race Guard            │  │
│  └───────┬────────────────────────────┬───────────────┘  │
│          │                            │                  │
│  ┌───────▼───────────────┐    ┌───────▼───────────────┐  │
│  │ Bounded AI Pool       │    │ STOMP Broker & Hub    │  │
│  │ (Minimax + Alpha-Beta)│    │ (/topic/*, /user/**)  │  │
│  └───────────────────────┘    └───────────────────────┘  │
│                                                          │
│  ┌────────────────────────────────────────────────────┐  │
│  │   Concurrent Room Pool & Lifecycle Eviction Cache  │  │
│  └────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────┘
```

---

## 3. 核心领域模型与数据结构 (Domain Model & Data Structures)

### 3.1 规则集与坐标系规范 (Rule Set & Coordinate System)

* **规则集 (Rule Set)**:
  * MVP 采用国际广泛使用的 **FREESTYLE (自由规则)**：黑白双方均无禁手限制（黑方不过滤三三、四四、长连）；横、竖、正斜、反斜任一轴向上连续同色棋子达到或超过 5 颗（`count >= 5`）即判定获胜。
  * 系统预留 `RuleSet` 枚举（如未来扩展 `STANDARD`、`RENJU`）。
* **棋盘规格与坐标系 (Coordinate System)**:
  * 标准棋盘尺寸：`BOARD_SIZE = 15`，总格数 225。
  * 数组表示：`int[][] board = new int[BOARD_SIZE][BOARD_SIZE]`。
  * 坐标约定：`board[row][col]`。
    * `row` (行号 / Y 轴)：`0` 到 `14`，自上而下递增。
    * `col` (列号 / X 轴)：`0` 到 `14`，自左而右递增。
    * 左上角原点为 `(0, 0)`，棋盘中心天元为 `(7, 7)`。
  * 棋盘单元值定义：
    * `0`: 空位 (`EMPTY`)
    * `1`: 黑子 (`BLACK`)
    * `2`: 白子 (`WHITE`)

### 3.2 实体与枚举设计 (Entities & Enums)

```java
// 棋子颜色 / 行棋方
public enum PieceColor {
    BLACK(1),  // 黑棋（先手）
    WHITE(2);  // 白棋（后手）

    private final int value;
    PieceColor(int value) { this.value = value; }
    public int getValue() { return value; }
    
    public PieceColor opposite() {
        return this == BLACK ? WHITE : BLACK;
    }
}

// 房间状态机枚举
public enum GameStatus {
    WAITING,   // 等待玩家加入
    PLAYING,   // 对局进行中
    PAUSED,    // 玩家掉线重连中（保留期）
    FINISHED   // 对局已结束
}

// 终局原因枚举
public enum FinishReason {
    FIVE_IN_A_ROW,        // 连五成线获胜
    SURRENDER,            // 对手认输
    TURN_TIMEOUT,         // 回合落子超时
    DISCONNECT_TIMEOUT,   // 掉线重连超时
    DRAW                  // 满盘平局
}

// 玩家角色
public enum UserRole {
    PLAYER_BLACK, // 黑方棋手
    PLAYER_WHITE, // 白方棋手
    SPECTATOR     // 观战者
}

// 标准业务错误码
public enum ErrorCode {
    NOT_YOUR_TURN,        // 非当前行动回合
    CELL_OCCUPIED,        // 目标坐标已有落子
    INVALID_COORDINATES,  // 坐标越界
    GAME_NOT_PLAYING,     // 对局不在进行状态
    NOT_A_PLAYER,         // 非对局选手（观战者越权）
    STALE_STATE,          // 客户端状态版本过期
    DUPLICATE_COMMAND,    // 重复提交的指令
    ROOM_FULL,            // 房间选手席已满
    UNAUTHORIZED_ACTION,  // 未授权的操作
    RATE_LIMITED          // 频率超限
}
```

### 3.3 落子记录与聊天实体 (Move & Chat Models)

```java
public record Move(
    int stepNumber,        // 第几手 (1..225)
    PieceColor color,      // 执子颜色
    int row,               // 行号 (0..14)
    int col,               // 列号 (0..14)
    long timestamp         // 落子服务端时间戳
) {}

public record ChatMessage(
    String messageId,      // 消息唯一标识
    String senderId,       // 发送者用户ID (由Principal提供)
    String senderName,     // 发送者昵称
    UserRole role,         // 发送时角色
    String content,        // 过滤转义后的文本内容
    long timestamp         // 服务端时间戳
) {}
```

### 3.4 完整房间聚合根模型 (GameRoom Aggregate)

```java
public class GameRoom {
    public static final int BOARD_SIZE = 15;
    public static final int TOTAL_CELLS = BOARD_SIZE * BOARD_SIZE;

    // 基础元数据
    private final String roomId;
    private String gameId;                        // 对局唯一标识 (重开对局刷新)
    private long stateVersion;                    // 房间状态版本号 (单调递增，每次状态变更 +1)
    private GameStatus status;
    private FinishReason finishReason;
    private PieceColor winner;                    // 胜利方 (null 表示未决或平局)
    private boolean isAiMode;                     // 是否为人机模式
    private String aiDifficulty;                  // AI 难度 (EASY / MEDIUM / HARD)

    // 席位与连接管理
    private String playerBlackId;                 // 黑方用户ID
    private String playerWhiteId;                 // 白方用户ID
    private final Map<String, Set<String>> userActiveSessions = new ConcurrentHashMap<>(); // userId -> active STOMP sessionIds
    private final Set<String> spectatorUserIds = ConcurrentHashMap.newKeySet();           // 观战用户ID集合

    // 棋局核心状态
    private PieceColor currentTurn;               // 当前轮到哪一方
    private final int[][] board = new int[BOARD_SIZE][BOARD_SIZE];
    private int occupiedCount;                    // 当前盘面棋子总数 (0..225)
    private final List<Move> moveHistory = new ArrayList<>(); // 落子历史 (仅用于复盘和打谱)

    // 倒计时与时效控制
    private long turnDeadline;                    // 当前回合落子截止绝对时间戳
    private long disconnectDeadline;              // 掉线重连截止绝对时间戳
    private ScheduledFuture<?> currentTurnTimer;  // 当前回合超时定时器引用
    private ScheduledFuture<?> currentDisconnectTimer; // 当前断线重连定时器引用

    // 聊天有界缓存 (环形缓冲，最大保留 50 条)
    private final Deque<ChatMessage> recentChats = new ArrayDeque<>(50);

    // 生命周期审计时间戳
    private final long createdAt;
    private Long startedAt;
    private Long finishedAt;
    private volatile long lastActivityAt;

    // 房间实例并发控制锁
    private final ReentrantLock roomLock = new ReentrantLock();

    // 版本自增与活跃时间刷新 (锁内执行)
    public long nextVersion() {
        this.lastActivityAt = System.currentTimeMillis();
        return ++this.stateVersion;
    }
}
```

---

## 4. 业务场景与交互逻辑 (Business Flows & Logic)

### 4.1 安全准入与角色分配 (Secure Access & Role Assignment)

所有客户端身份**必须且只能**从服务端认证后的 `java.security.Principal` 获取，禁止从消息 Payload 读取任何客户端自报的 `userId`、`username`、角色或棋子颜色。

1. **用户进入房间**:
   * 服务端在房间锁内检查用户当前身份：
     * 若该用户已是本房间的 `playerBlackId` 或 `playerWhiteId`，直接判定为选手重连/刷新，更新其关联的 Session 集合。
     * 若该用户为新进入者：
       * 若黑方为空：分配为 `PLAYER_BLACK`，房间状态保持 `WAITING`。
       * 若黑方已有、白方为空且非 AI 模式：分配为 `PLAYER_WHITE`，房间状态流转为 `PLAYING`，初始化 `gameId`，黑方先手，启动首回合落子倒计时。
       * 若两位选手已齐或已开启 AI 模式：自动分配为 `SPECTATOR`（观战者），加入观战集合。
2. **人机模式切换 (PvE Mode)**:
   * 仅房间黑方选手（房主）且在 `WAITING` 状态下有权触发 `/app/game.ai_switch`。
   * 触发后系统设置 `playerWhiteId = "SYSTEM_AI"`，`isAiMode = true`，房间状态进入 `PLAYING`，初始化棋局并启动黑方倒计时。
3. **订阅授权拦截 (Subscription Authorization)**:
   * 实现 Spring 的 `ChannelInterceptor`，在 `StompCommand.SUBSCRIBE` 阶段拦截：
     * 目标 `/topic/room/{roomId}/**`：校验当前 `Principal` 是否已成功加入该房间（作为选手或观战者）。未加入者直接抛出 `AccessDeniedException`，拒绝订阅。
     * 私有通道 `/user/queue/**`：仅允许订阅属于自身 `Principal` 的个人队列。

### 4.2 胜负与平局判定算法 (Referee Engine)

判定算法必须高效且具备确定性。落子时仅扫描该落子位置在 4 个轴向上的连线：

```java
public class GomokuReferee {
    // 轴向向量：水平(0), 垂直(1), 主对角线(2), 副对角线(3)
    private static final int[][] DIRECTIONS = {
        {0, 1},   // 水平向右/左
        {1, 0},   // 垂直向下/上
        {1, 1},   // 主对角线向右下/左上
        {1, -1}   // 副对角线向右上/左下
    };

    /**
     * 胜负裁定：FREESTYLE 自由规则下连续同色棋子达到或超过 5 颗即判定获胜
     */
    public static boolean checkWin(int[][] board, int row, int col, int color) {
        for (int[] dir : DIRECTIONS) {
            int count = 1;
            count += countConsecutive(board, row, col, dir[0], dir[1], color);
            count += countConsecutive(board, row, col, -dir[0], -dir[1], color);
            if (count >= 5) {
                return true;
            }
        }
        return false;
    }

    private static int countConsecutive(int[][] board, int startRow, int startCol, int dRow, int dCol, int color) {
        int count = 0;
        int r = startRow + dRow;
        int c = startCol + dCol;
        while (r >= 0 && r < GameRoom.BOARD_SIZE && c >= 0 && c < GameRoom.BOARD_SIZE && board[r][c] == color) {
            count++;
            r += dRow;
            c += dCol;
        }
        return count;
    }

    /**
     * 平局裁定：当盘面落子数达到 225 且当前落子未获胜时裁定平局
     */
    public static boolean checkDraw(int occupiedCount, boolean isWin) {
        return !isWin && occupiedCount >= GameRoom.TOTAL_CELLS;
    }
}
```

### 4.3 进程内 AI 对战引擎实现契约 (PvE AI Engine Contract)

为杜绝引入外部商业 LLM 产生不可控费用、网络延迟或依赖虚构，PvE 对战采用纯本地 Java 实现的传统博弈树引擎。

#### 1. 服务接口契约 (Interface Contract)
```java
public record BoardSnapshot(
    int[][] grid,              // 15x15 深拷贝不可变数组
    int occupiedCount,
    PieceColor aiColor,
    long baseStateVersion      // 计算触发时的房间状态版本号
) {}

public interface GomokuAiService {
    /**
     * 计算最佳落子，必须在指定 timeBudget 内返回
     */
    CompletableFuture<Move> computeBestMoveAsync(
        BoardSnapshot snapshot, 
        AiDifficulty difficulty, 
        Duration timeBudget
    );
}
```

#### 2. 算力与调度隔离设计
* **独立有界线程池**: 配置独立的 `ThreadPoolExecutor (corePoolSize=2, maxPoolSize=4, queueCapacity=50)`，与处理 STOMP 消息的 WebSocket 线程池物理隔离。
* **状态版本绑定与竞态丢弃**:
  * AI 开始计算时捕获当前 `baseStateVersion`。
  * AI 计算完成后，落子请求提交给 `GameRoomService` 统一入口；若此时房间 `stateVersion != baseStateVersion`（例如玩家已认输、掉线或强退），直接丢弃该 AI 计算结果。
* **搜索策略与优化**:
  * **开局天元优化**: 若盘面为空或对方落在角落，第一手直接锁定天元 `(7, 7)`。
  * **快速杀手着法检查 (Immediate Win/Block Check)**: 优先检测己方是否存在活四/连五点，或对方是否存在必杀点，有则直接落子，无需进入深度 Minimax 搜索。
  * **候选点修剪 (Candidate Pruning)**: 仅在已有棋子周围 1~2 格且属于空位的交叉点中选取前 15~25 个高分候选点，抑制分支发散。
  * **迭代加深搜索 (Iterative Deepening Search) 与软硬超时**:
    * 在设定时间预算内（如 200ms）逐层深入（2层 -> 3层 -> 4层）。
    * 每次完成一层搜索更新 `bestMove`；若时间达到 80% 软阈值，停止下一层搜索直接返回。

### 4.4 实时聊天安全与频率限制 (Chat Security & Rate Limiting)

* **内容限制与转义**:
  * 文本最大长度硬性限制为 200 个字符。
  * 服务端执行 HTML 实体转义，严防存储型与反射型 **XSS (Cross-Site Scripting，跨站脚本攻击)**。
* **频率限制 (Rate Limiting)**:
  * 基于 Google Guava `RateLimiter` 或轻量级 **Token Bucket (令牌桶)**：每个用户在单个房间内限流每秒最多 2 条，突发最多 5 条。超限抛出 `RATE_LIMITED` 错误。
* **有界保留**:
  * 房间内仅保留最新 50 条消息，旧消息自动出队丢弃，防爆内存。

---

## 5. WebSocket 通信协议与 API 规范 (Protocol & API Specifications)

### 5.1 STOMP 端点规划 (Endpoints)

* **WebSocket 连接握手**: `/ws-gomoku` (支持 SockJS 降级)
* **客户端上行目标前缀 (Application Destination Prefix)**: `/app`
* **广播代理前缀 (Broker Destination Prefix)**: `/topic`
* **用户私有单播前缀 (User Destination Prefix)**: `/user`

### 5.2 客户端上行命令规范 (Client -> Server: `/app/...`)

所有上行命令由客户端生成唯一的 `commandId` 用于幂等性防重，携带客户端已知的 `expectedStateVersion` 用于乐观版本校验。**严禁在请求体中附带用户身份字段。**

| 目标端点 (Destination) | 动作说明 (Action) | 请求体 Payload 格式 |
| :--- | :--- | :--- |
| `/app/room.join` | 用户加入/重连房间 | `{ "commandId": "uuid-1", "roomId": "R101" }` |
| `/app/game.move` | 玩家落子 | `{ "commandId": "uuid-2", "roomId": "R101", "expectedStateVersion": 15, "row": 7, "col": 7 }` |
| `/app/game.ai_switch`| 开启人机对战 (仅房主)| `{ "commandId": "uuid-3", "roomId": "R101", "difficulty": "MEDIUM" }` |
| `/app/chat.send` | 发送聊天消息 | `{ "commandId": "uuid-4", "roomId": "R101", "content": "精彩的防守！" }` |
| `/app/game.surrender`| 认输退出 | `{ "commandId": "uuid-5", "roomId": "R101", "expectedStateVersion": 15 }` |

### 5.3 服务端下行广播与单播规范 (Server -> Client)

所有广播消息体均携带单调递增的 `stateVersion`。客户端消费消息时，必须比对本地保存的最新版本号，忽略小于等于当前版本的过期重复消息。

#### 1. 状态同步广播: `/topic/room/{roomId}/state`
当房间状态改变、选手进入、对局开始、对局结束或状态回滚时触发：
```json
{
  "type": "ROOM_STATE_UPDATE",
  "roomId": "R101",
  "gameId": "G-20260903-001",
  "stateVersion": 1,
  "status": "PLAYING",
  "ruleSet": "FREESTYLE",
  "playerBlack": { "userId": "U1001", "username": "Alice" },
  "playerWhite": { "userId": "U1002", "username": "Bob" },
  "currentTurn": "BLACK",
  "occupiedCount": 0,
  "turnDeadline": 1788440030000,
  "serverTimestamp": 1788440000000
}
```

#### 2. 落子同步广播: `/topic/room/{roomId}/gameplay`
每次有效落子成功后广播：
```json
{
  "type": "MOVE_COMMITTED",
  "roomId": "R101",
  "gameId": "G-20260903-001",
  "stateVersion": 2,
  "commandId": "uuid-2",
  "stepNumber": 1,
  "color": "BLACK",
  "row": 7,
  "col": 7,
  "nextTurn": "WHITE",
  "turnDeadline": 1788440060000,
  "isGameOver": false,
  "winner": null,
  "finishReason": null,
  "serverTimestamp": 1788440001500
}
```

#### 3. 终局结果广播: `/topic/room/{roomId}/gameplay`
```json
{
  "type": "GAME_FINISHED",
  "roomId": "R101",
  "gameId": "G-20260903-001",
  "stateVersion": 45,
  "winner": "BLACK",
  "finishReason": "FIVE_IN_A_ROW",
  "winningLine": [ [7, 7], [8, 8], [9, 9], [10, 10], [11, 11] ],
  "finishedAt": 1788440800000
}
```

#### 4. 聊天与系统提示广播: `/topic/room/{roomId}/chat`
```json
{
  "type": "CHAT_MESSAGE",
  "messageId": "msg-889",
  "senderId": "U1003",
  "senderName": "Charlie",
  "role": "SPECTATOR",
  "content": "白棋这步防守很漂亮",
  "timestamp": 1788440010000
}
```

#### 5. 私有异常响应通道: `/user/queue/errors`
当客户端命令执行失败（如越权、非自身回合、坐标非法）时，仅单播通知该用户，严禁广播：
```json
{
  "commandId": "uuid-2",
  "errorCode": "NOT_YOUR_TURN",
  "errorMessage": "当前不是您的行棋回合",
  "currentServerVersion": 15,
  "timestamp": 1788440005000
}
```

#### 6. 私有全量对局快照通道: `/user/queue/snapshot`
新观战者进入或断线重连用户接入时，向其私有队列下发全量快照，完成客户端对局渲染重构：
```json
{
  "roomId": "R101",
  "gameId": "G-20260903-001",
  "stateVersion": 28,
  "status": "PLAYING",
  "ruleSet": "FREESTYLE",
  "playerBlack": { "userId": "U1001", "username": "Alice" },
  "playerWhite": { "userId": "U1002", "username": "Bob" },
  "currentTurn": "WHITE",
  "turnDeadline": 1788440085000,
  "occupiedCount": 28,
  "board": [ [0,0,0...], ... ],
  "recentMoves": [ ... ],
  "recentChats": [ ... ]
}
```

---

## 6. 健壮性设计与边界异常处理 (Robustness & Edge Cases)

### 6.1 统一串行化边界与并发控制 (Serialization Boundary)

为了彻底根除多线程交织下的 **Data Race (数据竞态)**，系统确立如下并发设计原则：
1. **统一服务层门面 (`GameRoomService`)**:
   * Controller 或 WebSocket Handler 仅负责 STOMP 协议解析、提取 `Principal` 与构造参数，绝不允许直接对 `GameRoom` 进行写操作。
   * 所有针对房间状态的写操作（`joinRoom`, `makeMove`, `switchAi`, `surrender`, `handleDisconnect`, `handleTimeout`）统一收口在 `GameRoomService`。
2. **锁内与锁外分离模式 (Inside-Lock vs. Outside-Lock Separation)**:
   * **锁内执行**: 
     1. 获取对应 `GameRoom.roomLock`；
     2. 校验命令参数合法性与状态版本；
     3. 变更棋盘数组与领域状态；
     4. 自增 `stateVersion` 并生成待广播的事件对象；
     5. 重置或创建定时器上下文。
   * **锁外执行**:
     1. 释放 `roomLock`；
     2. 通过 Spring `SimpMessagingTemplate` 向 WebSocket Broker 投递事件；
     3. 触发异步 AI 计算（若下一手轮到 AI）。

### 6.2 回合超时定时器的防竞态保证 (Turn Timeout Race Guard)

单纯取消 ScheduledFuture 无法避免竞态：当定时器线程已由操作系统唤醒并进入执行队列时，玩家在同一毫秒完成落子，旧定时器可能在下一毫秒将刚开始回合的无辜对手判负。

**三元组快照防竞态解法**:
1. 调度任务创建时，封装执行上下文：
   ```java
   record TimeoutContext(
       String gameId,
       PieceColor expectedTurn,
       long expectedStateVersion,
       long expectedDeadline
   ) {}
   ```
2. 定时任务触发回调执行流程：
   ```java
   public void handleTurnTimeout(String roomId, TimeoutContext context) {
       GameRoom room = getRoom(roomId);
       room.getRoomLock().lock();
       try {
           // 双重核验：对局ID、状态、行棋方、版本号必须完全匹配
           if (room.getStatus() != GameStatus.PLAYING ||
               !Objects.equals(room.getGameId(), context.gameId()) ||
               room.getCurrentTurn() != context.expectedTurn() ||
               room.getStateVersion() != context.expectedStateVersion()) {
               // 条件不一致，说明落子/认输已先一步完成，静默退出
               return;
           }
           
           // 确认真正超时，执行判负结算
           room.setStatus(GameStatus.FINISHED);
           room.setFinishReason(FinishReason.TURN_TIMEOUT);
           room.setWinner(context.expectedTurn().opposite());
           long newVer = room.nextVersion();
           
           // 构建终局事件并在锁外广播...
       } finally {
           room.getRoomLock().unlock();
       }
   }
   ```

### 6.3 多会话断线、重连与保留期 (Multi-Session Disconnect & Grace Period)

单一 WebSocket 连接断开（`SessionDisconnectEvent`）**绝不直接等同于用户离线**（用户可能多开标签页、或旧连接断开晚于新连接重连）。

1. **会话引用计数管理**:
   * 每个选手映射维护活跃的 Session ID 集合：`userActiveSessions.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(sessionId)`。
   * 仅当用户绑定的 `activeSessions` 计数归零时，才认定该选手真正离线。
2. **掉线保留期 (Grace Period)**:
   * 真正离线后，房间状态流转为 `PAUSED`，当前回合落子倒计时挂起，启动 45 秒掉线保护定时器。
   * 同样通过 `(gameId, stateVersion, expectedDeadline)` 竞态守卫调度断线判负任务。
   * 若选手在 45 秒内通过新 Session 重新连接，清除掉线定时器，房间恢复为 `PLAYING` 状态，补齐挂起时间并广播状态恢复。
   * 若 45 秒超时，系统判定离线方负，未离线方获胜。
3. **双方同时掉线兜底**:
   * 若两位选手先后掉线且均在保留期内未归，系统在最后一人超时时自动裁定为终局，释放资源。

### 6.4 资源生命周期与过期清理 (Lifecycle & Eviction Policy)

防止在 `ConcurrentHashMap` 中长期驻留废弃房间导致 **Memory Leak (内存泄漏)**：
* **空房间回收**: 房间内所有选手与观战者均退出且无新用户进入，存活超过 10 分钟自动注销销毁。
* **终局房间回收**: 处于 `FINISHED` 状态的房间，在保留 5 分钟供复盘后，自动从内存池中剔除。
* **关联任务清除**: 房间销毁时，必须显式调用 `cancel(false)` 取消其附带的回合倒计时、掉线倒计时及未完成的 AI 计算线程引用。
* **优雅停机 (Graceful Shutdown)**: 监听 Spring 容器关闭事件 `ContextClosedEvent`，广播系统停机维护通知，持久化或安全结算当前活跃对局。

---

## 7. 系统可观测性与安全加固 (Observability & Security)

### 7.1 结构化日志追踪 (Structured MDC Logging)

所有涉及房间状态机变迁、落子、报错的日志，必须在 Logback/SLF4J **MDC (Mapped Diagnostic Context)** 中打入统一上下文追踪字段：
```java
MDC.put("roomId", roomId);
MDC.put("gameId", room.getGameId());
MDC.put("userId", principal.getName());
MDC.put("commandId", commandId);
MDC.put("stateVersion", String.valueOf(room.getStateVersion()));
```
**安全禁止项**: 严禁在日志中打印用户的 JWT 令牌、重连凭证密钥或敏感鉴权凭据。

### 7.2 核心监控度量指标 (Micrometer Metrics)

通过 Spring Boot Actuator 与 Micrometer 暴露核心监控指标：
* `gomoku.rooms.active`: 当前活跃房间 Gauge。
* `gomoku.players.online`: 当前在线玩家与观战者计数器。
* `gomoku.moves.total`: 累计有效落子 Counter，按 `result (continue/win/draw)` 打 Tag。
* `gomoku.moves.rejected`: 拒绝落子 Counter，按 `errorCode` 打 Tag。
* `gomoku.ai.compute.time`: AI 计算耗时 Timer (跟踪 P50, P95, P99 分位数)。
* `gomoku.room.lock.wait.time`: 房间锁竞争等待耗时 Timer。

---

## 8. 全面测试矩阵与验证规范 (Test Matrix & Verification)

除基础裁判算法单元测试外，必须通过并发与状态机集成测试验证如下 12 种极端场景：

1. **同格并发冲突**: 两位选手在同一毫秒向同一坐标落子，必须有且仅有一方成功，另一方收到 `CELL_OCCUPIED` 或 `NOT_YOUR_TURN`。
2. **重复命令幂等**: 客户端因重试连续发送相同 `commandId` 的落子，服务端仅执行一次，后续重复请求安全幂等返回。
3. **落子与超时竞态**: 玩家在倒计时截止前 1ms 落子，定时器线程与落子线程并发执行，系统不得误判该玩家超时负。
4. **落子与认输竞态**: 玩家 A 提交落子的同时玩家 B 提交认输，终局状态必须严格按版本串行裁决，不产生脏状态。
5. **多连接乱序断开**: 同一玩家开启双 Tab，旧 Tab 关闭触发的 `SessionDisconnectEvent` 不得破坏新 Tab 的 `PLAYING` 状态。
6. **越权落子拦截**: 观战者向 `/app/game.move` 发送落子包，必须被服务端拒绝并返回 `NOT_A_PLAYER` 错误，严禁污染棋盘。
7. **非法订阅拦截**: 用户尝试直接订阅未进入房间的 `/topic/room/{roomId}/chat`，必须在 `SUBSCRIBE` 阶段被安全拦截。
8. **AI 计算期房间终止**: AI 正在计算时人类玩家认输或退出，AI 计算完成返回的落点因版本不符被静默丢弃。
9. **客户端状态自愈**: 客户端模拟丢包丢失某一步骤的广播事件，收到新版本后校验出版本空洞，通过 `/user/queue/snapshot` 请求快照并成功自愈重建棋盘。
10. **终局迟到命令防护**: 对局已由五连获胜结束，网络延迟送达的后续落子命令或旧定时器不得改变对局结果。
11. **棋盘四轴及边界判定**: 验证水平边界、垂直边界、四个角顶点以及 6 连长连在 FREESTYLE 下的正确裁决。
12. **多房间高并发 AI 隔离**: 50 个房间同时进行人机对战，验证 AI 独立线程池队列积压情况及 WebSocket I/O 线程的无卡顿表现。

---

## 9. 分步落地实施指南与阶段验收门禁 (Step-by-Step Implementation & Quality Gates)

为保障工程高质量落地，整个系统采用**迭代增量开发 (Iterative & Incremental Development)** 模式推进。系统被分解为 **6 个单向递进、职责解耦的独立实施阶段**。每个阶段均定义了明确的实施指导 Prompt、**核心交付成果 (Deliverables)** 以及**质量验收门禁 (Quality Gates / Acceptance Criteria)**。开发团队或 AI Coding Agent 必须在当前阶段验收 100% 通过后，方可推进下一阶段。

```
┌──────────────┐     ┌──────────────┐     ┌──────────────┐
│  阶段一：    │ ──> │  阶段二：    │ ──> │  阶段三：    │
│  领域与裁判  │     │  安全与鉴权  │     │  对战与流水线│
└──────┬───────┘     └──────┬───────┘     └──────┬───────┘
       ▼                    ▼                    ▼
  [单测验收]           [安全集成测试]       [双端联调测试]
       │                    │                    │
┌──────▼───────┐     ┌──────▼───────┐     ┌──────▼───────┐
│  阶段四：    │ ──> │  阶段五：    │ ──> │  阶段六：    │
│  定时器防竞态│     │  内置传统 AI │     │  前端与可观测│
└──────┬───────┘     └──────┬───────┘     └──────┬───────┘
       ▼                    ▼                    ▼
  [并发竞态测试]       [SLA基准测试]        [E2E全局验收]
```

---

### 阶段一：领域核心模型、坐标系与裁判算法 (Domain Model & Referee Engine)

#### 1. 实施指导 Prompt
> "基于 Java 17 和 Spring Boot 3，创建五子棋的核心领域实体与裁判逻辑：
> 1. 创建枚举 `PieceColor`、`GameStatus`、`FinishReason`、`UserRole`、`ErrorCode`。
> 2. 严格按 15x15 规格定义棋盘坐标系 `board[row][col]`，原点 (0,0) 位于左上角。
> 3. 实现 `GomokuReferee`，按照 FREESTYLE (自由规则) 实现 4 个轴向的 `checkWin`（五连及以上即胜）与基于 `occupiedCount == 225` 的 `checkDraw`。
> 4. 编写全覆盖的 JUnit 5 单元测试，重点测试四角边界、中心天元、正反斜向以及长连获胜用例。"

#### 2. 核心交付成果 (Deliverables)
* 领域枚举与模型类：
  * `com.gomoku.model.enums.PieceColor`
  * `com.gomoku.model.enums.GameStatus`
  * `com.gomoku.model.enums.FinishReason`
  * `com.gomoku.model.enums.UserRole`
  * `com.gomoku.model.enums.ErrorCode`
  * `com.gomoku.model.entity.Move`
  * `com.gomoku.model.entity.ChatMessage`
* 裁判核心逻辑组件：
  * `com.gomoku.referee.GomokuReferee`
* 单元测试用例套件：
  * `src/test/java/com/gomoku/referee/GomokuRefereeTest.java`

#### 3. 验收标准与验证方式 (Quality Gate & Acceptance Criteria)
* **验证方式**: 执行 Maven 纯单元测试命令：
  ```bash
  mvn test -Dtest=GomokuRefereeTest
  ```
* **准出标准 (Exit Criteria)**:
  1. **四轴向连五验证**: 水平（东-西）、垂直（南-北）、主对角线（东南-西北）、副对角线（东北-西南）正反双向扫描均能准确识别连五。
  2. **自由规则长连验证**: 任意轴向出现 6 颗或更多同色连珠时，均准确裁定胜出。
  3. **边界溢出测试**: 棋盘四角顶点 `(0,0)`, `(0,14)`, `(14,0)`, `(14,14)` 及边缘坐标扫描不会抛出 `ArrayIndexOutOfBoundsException`。
  4. **满盘平局验证**: 棋盘填满 225 格且未成五连时，`checkDraw` 准确返回 `true`。
  5. **通过标志**: `BUILD SUCCESS`，单测覆盖率达 100%，无任何失败用例。

---

### 阶段二：WebSocket/STOMP 鉴权与安全拦截器 (STOMP Security & Channel Interceptors)

#### 1. 实施指导 Prompt
> "配置 Spring WebSocket 与 STOMP 协议支持：
> 1. 注册端点 `/ws-gomoku`，配置明确的 `allowedOriginPatterns`（禁用通配符跨域），启用 Simple In-Memory Broker (`/topic`, `/queue`) 与应用前缀 `/app`。
> 2. 实现 `ChannelInterceptor`，在 `CONNECT` 阶段从 Header 中解析 JWT 或访客凭证，生成并注入 `Principal`；严禁信任客户端请求体中的身份信息。
> 3. 在拦截器的 `SUBSCRIBE` 阶段对 `/topic/room/{roomId}/**` 进行房间成员鉴权，未加入房间的用户拒绝订阅。
> 4. 配置用户专属私有队列 `/user/queue/errors` 与 `/user/queue/snapshot`。"

#### 2. 核心交付成果 (Deliverables)
* 配置类与拦截器：
  * `com.gomoku.config.WebSocketConfig` (实现 `WebSocketMessageBrokerConfigurer`)
  * `com.gomoku.security.AuthChannelInterceptor` (实现 `ChannelInterceptor`)
  * `com.gomoku.security.TokenProvider` (支持 JWT 解析与测试用 Guest Token 生成)
  * `com.gomoku.security.UserPrincipal` (实现 `java.security.Principal`)
* 安全集成测试套件：
  * `src/test/java/com/gomoku/security/WebSocketSecurityIntegrationTest.java`

#### 3. 验收标准与验证方式 (Quality Gate & Acceptance Criteria)
* **验证方式**: 启动嵌入式 WebSocket 容器运行集成测试：
  ```bash
  mvn test -Dtest=WebSocketSecurityIntegrationTest
  ```
* **准出标准 (Exit Criteria)**:
  1. **未鉴权拦截**: 客户端不携带 Token 发起 `CONNECT` 命令，握手被拒绝或抛出鉴权异常。
  2. **安全主体绑定**: 携带合法 Token 连接成功后，STOMP 消息头中提取到的 `Principal.getName()` 与 Token 携带的 `userId` 完全一致。
  3. **订阅越权防护**: 用户 A 尝试直接发送 SUBSCRIBE 指令订阅未加入房间的 `/topic/room/R101/chat`，被 `AuthChannelInterceptor` 拦截并阻断。
  4. **私有单播可用**: 客户端成功订阅 `/user/queue/errors` 并能正常接收单播消息。
  5. **通过标志**: 安全拦截套件全部通过，跨域配置排除了 `*` 通配符。

---

### 阶段三：房间管理、并发串行化与落子流水线 (Room Management & Gameplay Pipeline)

#### 1. 实施指导 Prompt
> "实现 `GameRoom` 聚合根与 `GameRoomService` 业务服务层：
> 1. 使用 `ConcurrentHashMap<String, GameRoom>` 管理房间，为每个房间配置 `ReentrantLock` 互斥锁。
> 2. 所有写操作（准入、落子、认输、AI 切换、掉线）统一进入 `GameRoomService`。在锁内校验状态与乐观版本号 `expectedStateVersion`，更新棋盘，递增 `stateVersion`，锁外异步广播事件。
> 3. 实现 `@MessageMapping("/game.move")` 与 `@MessageMapping("/game.surrender")`，操作失败时统一向 `/user/queue/errors` 发送标准 `ErrorCode`。
> 4. 实现多 Session 计数追踪，避免单 Tab 关闭导致误判离线。"

#### 2. 核心交付成果 (Deliverables)
* 领域服务与房间聚合根：
  * `com.gomoku.model.entity.GameRoom` (含 `ReentrantLock`, `stateVersion`, 席位状态)
  * `com.gomoku.service.GameRoomService` (串行化调度中枢)
  * `com.gomoku.controller.GameMessageController` (处理 STOMP 上行指令)
  * `com.gomoku.dto.request.MoveRequest`, `SurrenderRequest`
  * `com.gomoku.dto.response.MoveCommittedEvent`, `RoomStateUpdateEvent`, `ErrorResponse`
* 双端联调集成测试套件：
  * `src/test/java/com/gomoku/gameplay/GamePlayIntegrationTest.java`

#### 3. 验收标准与验证方式 (Quality Gate & Acceptance Criteria)
* **验证方式**: 启动多线程模拟双客户端 STOMP 对弈测试：
  ```bash
  mvn test -Dtest=GamePlayIntegrationTest
  ```
* **准出标准 (Exit Criteria)**:
  1. **席位自动分配**: 第一个连接的用户自动进驻黑方，第二个用户进驻白方，房间状态迁移至 `PLAYING` 并触发先手计时。
  2. **落子版本推进**: 黑方落子 `(7,7)` 成功，全局广播 `MOVE_COMMITTED`，`stateVersion` 严格单调递增，当前行动方自动交接给白方。
  3. **非法回合拦截**: 白方未落子前，黑方连续提交落子，黑方私有队列 `/user/queue/errors` 收到 `NOT_YOUR_TURN` 响应，棋盘无变化。
  4. **并发抢占防护**: 双线程模拟向同一坐标同时落子，一人成功，另一人收到 `CELL_OCCUPIED`，无脏数据写入。
  5. **观战者越权防护**: 观战者发起落子请求，被直接拒绝并返回 `NOT_A_PLAYER`。
  6. **认输结算**: 玩家提交 `/app/game.surrender`，立即广播 `GAME_FINISHED` 并正确指定胜者。

---

### 阶段四：防竞态定时器与单机生命周期回收 (Timers & Lifecycle Eviction)

#### 1. 实施指导 Prompt
> "完善房间调度与异常恢复机制：
> 1. 基于 `ThreadPoolTaskScheduler` 实现回合 30 秒落子倒计时与 45 秒掉线重连倒计时。
> 2. 调度任务必须携带 `(gameId, expectedTurn, expectedStateVersion, expectedDeadline)` 竞态快照，触发时重新加锁核验，防止误杀下一回合玩家。
> 3. 实现定时清理任务：回收存活超过 10 分钟的空房间和结束超过 5 分钟的终局房间，显式取消未完成的 Future 任务，杜绝内存泄漏。"

#### 2. 核心交付成果 (Deliverables)
* 定时与生命周期组件：
  * `com.gomoku.scheduler.GameTimerService` (基于 `ThreadPoolTaskScheduler`)
  * `com.gomoku.scheduler.TimeoutContext` (记录期望版本的防竞态三元组)
  * `com.gomoku.scheduler.RoomEvictionScheduler` (定时扫描清理废弃房间)
  * `com.gomoku.listener.WebSocketSessionEventListener` (监听 `SessionDisconnectEvent` 并维护引用计数)
* 竞态与生命周期测试套件：
  * `src/test/java/com/gomoku/scheduler/TimerRaceConditionTest.java`

#### 3. 验收标准与验证方式 (Quality Gate & Acceptance Criteria)
* **验证方式**: 执行并发竞态与超时自动化测试：
  ```bash
  mvn test -Dtest=TimerRaceConditionTest
  ```
* **准出标准 (Exit Criteria)**:
  1. **回合自然超时判负**: 当前行棋方 30 秒内未落子，定时器自动触发，判定对方胜出，终局原因确认为 `TURN_TIMEOUT`。
  2. **防误判竞态验证 (消除 ABA)**: 模拟在 29.9 秒成功落子，随后触发的旧定时任务能比对出 `stateVersion` 不匹配并静默放弃，**绝对不将下一回合玩家误判为超时**。
  3. **多 Session 离线隔离**: 某玩家同时打开两个 Tab，关闭其中一个 Tab 时对局继续进行；当该玩家所有 Tab 均关闭时，房间状态切换为 `PAUSED` 并启动 45 秒保留期。
  4. **保留期恢复与超时**: 45 秒内重连成功对局恢复；超时未重连自动判负。
  5. **内存泄漏防护**: 空房间超过 10 分钟自动注销，所有未触发的 ScheduledFuture 均被显式 `cancel(false)` 取消。

---

### 阶段五：进程内传统 AI 决策引擎 (PvE AI Engine)

#### 1. 实施指导 Prompt
> "实现进程内传统五子棋 AI 引擎 `GomokuAiService`，杜绝任何外部网络 LLM 调用：
> 1. 构造独立的有界线程池 `aiTaskExecutor`。
> 2. 实现开局天元优化、必胜/必防杀手步快速检查。
> 3. 提取不可变 `BoardSnapshot`，在已有棋子周围 1~2 格筛选前 20 个高分候选点，执行 Minimax 搜索与 Alpha-Beta 剪枝，结合 200ms 时间预算控制搜索深度。
> 4. AI 计算结果携带 `baseStateVersion` 提交至 `GameRoomService`，若版本过期则安全丢弃。"

#### 2. 核心交付成果 (Deliverables)
* AI 引擎核心与配置：
  * `com.gomoku.ai.BoardSnapshot` (不可变棋盘快照模型)
  * `com.gomoku.ai.GomokuAiService` (服务接口契约)
  * `com.gomoku.ai.impl.MinimaxGomokuAiService` (启发式评估 + 局部剪枝 + Alpha-Beta 搜索)
  * `com.gomoku.config.AiThreadPoolConfig` (物理隔离的有界线程池)
* AI 性能与正确性基准测试：
  * `src/test/java/com/gomoku/ai/GomokuAiBenchmarkTest.java`

#### 3. 验收标准与验证方式 (Quality Gate & Acceptance Criteria)
* **验证方式**: 执行 AI 棋力与耗时基准测试：
  ```bash
  mvn test -Dtest=GomokuAiBenchmarkTest
  ```
* **准出标准 (Exit Criteria)**:
  1. **零外部网络依赖**: 纯本地运行，不含任何外部 HTTP/LLM SDK 依赖。
  2. **关键杀手步识别**: 针对残局（己方活四或对方冲四），AI 在单步计算中 100% 能够落子在制胜点或封堵点。
  3. **计算时延 SLA 保障**: 压测 50 步对局，在默认难度下 P95 耗时控制在 200ms 以内，硬超时熔断兜底有效。
  4. **异步过期结果丢弃**: 模拟 AI 计算耗时期间人类玩家认输，AI 线程返回后因 `stateVersion` 不一致被安全忽略，不产生异常与死锁。
  5. **线程隔离性**: 模拟高并发 AI 计算，WebSocket 消息处理线程池零阻塞。

---

### 阶段六：聊天安全、可观测性与单页演示前端 (Chat, Metrics & Frontend Demo)

#### 1. 实施指导 Prompt
> "完成系统收尾与前端验证页面：
> 1. 实现 `/app/chat.send` 处理器，引入令牌桶限流（每秒最多2条）、文本 HTML 转义与 200 字长限制，使用环形缓冲队列保留最近 50 条消息。
> 2. 在服务层关键节点集成 MDC 结构化日志与 Micrometer 指标。
> 3. 编写单文件 `index.html`（HTML5 Canvas + SockJS + STOMP.js），完整接入鉴权连接、坐标落子（严格对应 `board[row][col]`）、单调版本防重、重连快照渲染、AI 切换与防刷聊天。"

#### 2. 核心交付成果 (Deliverables)
* 聊天与安全组件：
  * `com.gomoku.chat.ChatService` (含令牌桶限流、HTML 实体转义、环形缓冲)
* 可观测性配置与切面：
  * `com.gomoku.observability.MdcLoggingAspect` (请求全链路 MDC 绑定)
  * `com.gomoku.observability.MetricsConfig` (注册活跃房间、落子计数、AI 时长等指标)
* 前端演示页面：
  * `src/main/resources/static/index.html` (单文件自包含 HTML5 Canvas + STOMP.js 客户端)

#### 3. 验收标准与验证方式 (Quality Gate & Acceptance Criteria)
* **验证方式**: 启动 Spring Boot 应用，打开浏览器双窗口进行端到端对战验证：
  ```bash
  mvn spring-boot:run
  # 访问 http://localhost:8080/index.html
  ```
* **准出标准 (Exit Criteria)**:
  1. **前端交互与渲染**: Canvas 棋盘精确匹配 `board[row][col]` 坐标，落子无偏差，高亮最新落子；开局、落子、终局状态流转顺畅。
  2. **聊天安全防御**: 发送 `<script>alert(1)</script>` 被严格转义；连续快速点击发送聊天触发 `RATE_LIMITED` 错误气泡提示；输入超 200 字符被前端截断并经后端校验拦截。
  3. **快照自愈展示**: 刷新浏览器页面后，前端触发 `/user/queue/snapshot` 恢复全部棋盘状态并补全历史聊天。
  4. **监控指标采集**: 访问 `/actuator/metrics/gomoku.moves.total` 可成功获取落子计数与分位数统计。
  5. **全系统交付标志**: 端到端联调无控制台 JavaScript 报错，后端日志含完整 `roomId/gameId/userId/stateVersion` 结构化追踪信息。

