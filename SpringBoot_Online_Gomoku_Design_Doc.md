# 在线五子棋对战与观战系统系统设计文档 (System Design Document)

## 1. 项目概述与目标 (Project Overview & Objectives)

本项目旨在基于 **Spring Boot** 构建一个轻量级、高并发且具备高可扩展性的在线五子棋（Gomoku）对战与观战平台。系统支持双人在线实时对战、单人人机对战（PvE）、多人实时观战（Spectating）以及局内实时聊天互动（In-Game Chat）。

本文档遵循行业标准设计规范，详细定义了系统架构、核心领域模型、状态机转换、裁判算法、实时通信协议及异常恢复机制，可直接作为 AI Coding Agent 或工程团队的分步落地实现蓝本。

---

## 2. 系统架构与技术选型 (System Architecture & Technology Stack)

### 2.1 技术栈推荐 (Recommended Tech Stack)
* **后端框架 (Backend Framework)**: Java 17 / 21 + Spring Boot 3.x
* **实时通信 (Real-Time Communication)**: Spring WebSocket + STOMP 协议（基于 Simple In-Memory Broker 或 External RabbitMQ）
* **会话与认证 (Authentication & Session)**: Spring Security + JWT (JSON Web Token) / Lightweight Guest Session
* **状态存储 (State Storage)**: 
  * 活跃对局：`ConcurrentHashMap` 内存存储（单机）或 Redis（分布式扩展）
  * 持久化（可选）：H2 / SQLite / MySQL（记录历史战绩）
* **前端实现参考 (Frontend Reference)**: HTML5 Canvas + STOMP.js + SockJS (或 Vue 3 / React)

### 2.2 总体架构图 (High-Level Architecture)

```
[ Frontend Clients (Browser) ]
       │          ▲
 HTTP  │          │ WebSocket (STOMP)
 (REST)│          │ (/ws/game)
       ▼          ▼
┌──────────────────────────────────────────────────────────┐
│              Spring Boot Application Server              │
│                                                          │
│  ┌───────────────────────┐    ┌───────────────────────┐  │
│  │   REST Controllers    │    │  STOMP Message Router │  │
│  │ (Auth / Room Query)   │    │  (/app/*, /topic/*)   │  │
│  └───────────┬───────────┘    └───────────┬───────────┘  │
│              │                            │              │
│  ┌───────────▼────────────────────────────▼───────────┐  │
│  │              Game Engine & Referee                 │  │
│  │ (Win Detection / Rule Enforcement / State Machine) │  │
│  └───────────┬────────────────────────────┬───────────┘  │
│              │                            │              │
│  ┌───────────▼───────────┐    ┌───────────▼───────────┐  │
│  │      AI PvE Engine    │    │  Chat & Spectator Hub │  │
│  │ (Minimax + Alpha-Beta)│    │  (Broadcasting Hub)   │  │
│  └───────────────────────┘    └───────────────────────┘  │
│                                                          │
│  ┌────────────────────────────────────────────────────┐  │
│  │    In-Memory Game Store / Concurrent Room Pool     │  │
│  └────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────┘
```

---

## 3. 核心领域模型与数据结构 (Domain Model & Data Structures)

### 3.1 实体与状态枚举 (Entities & Enums)

```java
// 棋子颜色 / 玩家角色
public enum PieceColor {
    BLACK(1),  // 黑棋（先手）
    WHITE(2);  // 白棋（后手）
    
    private final int value;
    PieceColor(int val) { this.value = val; }
    public int getValue() { return value; }
}

// 房间状态机枚举
public enum GameStatus {
    WAITING,   // 等待玩家加入
    PLAYING,   // 对局进行中
    PAUSED,    // 玩家掉线重连中
    FINISHED   // 对局已结束
}

// 玩家角色分类
public enum UserRole {
    PLAYER_BLACK, // 黑方棋手
    PLAYER_WHITE, // 白方棋手
    SPECTATOR     // 观战者
}
```

### 3.2 棋盘数据模型 (Board Model)
* **标准规格**: 15 × 15 二维数组（`int[][] grid = new int[15][15]`）。
  * `0`: 空位 (EMPTY)
  * `1`: 黑子 (BLACK)
  * `2`: 白子 (WHITE)
* **落子历史 (Move History)**: `List<Move>`，记录每一步的坐标 `(x, y)`、执子颜色、落子时间戳，用于悔棋、复盘与打谱。

### 3.3 房间模型 (Game Room)
```java
public class GameRoom {
    private String roomId;
    private GameStatus status;
    private boolean isAiMode;            // 是否为人机对战
    private String playerBlackId;        // 黑方用户ID
    private String playerWhiteId;        // 白方用户ID
    private PieceColor currentTurn;      // 当前轮到哪一方落子
    private int[][] board;               // 15x15 棋盘
    private List<Move> moveHistory;      // 落子记录
    private Set<String> spectatorIds;    // 观战用户ID集合
    private long lastMoveTimestamp;      // 上一次落子时间（用于超时裁定）
    private final ReentrantLock roomLock = new ReentrantLock(); // 房间并发锁
}
```

---

## 4. 业务场景与交互逻辑 (Business Flows & Logic)

### 4.1 用户准入与角色分配 (Access & Role Assignment)
系统根据房间内当前人数动态分配角色：
1. **首个用户进入 (Player Count = 1)**:
   * 分配为房间房主（默认执黑 `PLAYER_BLACK`）。
   * 界面提供模式选择：
     * **等待对战 (PvP Mode)**: 保持 `WAITING` 状态，监听新玩家加入。
     * **人机对战 (PvE Mode)**: 点击切换为人机模式，系统自动创建 AI 白方 (`PLAYER_WHITE`)，房间立即进入 `PLAYING` 状态。
2. **第二个用户进入 (Player Count = 2)**:
   * 若房间处于 `WAITING` 状态，第二位用户自动分配为执白 `PLAYER_WHITE`。
   * 房间状态由 `WAITING` 推进为 `PLAYING`，通知双方游戏开始，黑方先行。
3. **第三人及以上进入 (Player Count > 2)**:
   * 自动分配为观战者角色 (`SPECTATOR`)。
   * 服务端向新观战者推送当前对局全量快照（`Board Snapshot` + `Chat History` + `Move History`）。
   * 观战者不具备落子权限，但具备公共频道聊天权限。

### 4.2 胜负判定算法 (Referee Engine / Win Determination)
五子棋胜负判定必须保证高效、无漏判。每当有有效落子 `(x, y, color)` 时，只针对该坐标进行 **4 个轴向 (Horizontal, Vertical, Diagonal, Anti-Diagonal)** 的连续同色棋子扫描：

```java
public class GomokuReferee {
    // 方向向量：水平、垂直、主对角线、副对角线
    private static final int[][] DIRECTIONS = {
        {1, 0},  // 0: 水平 (East - West)
        {0, 1},  // 1: 垂直 (South - North)
        {1, 1},  // 2: 正对角线 (South-East - North-West)
        {1, -1}  // 3: 反对角线 (North-East - South-West)
    };

    public static boolean checkWin(int[][] board, int x, int y, int color) {
        for (int[] dir : DIRECTIONS) {
            int count = 1; // 包含当前刚落下的棋子
            
            // 正向延伸扫描
            count += countInDirection(board, x, y, dir[0], dir[1], color);
            // 反向延伸扫描
            count += countInDirection(board, x, y, -dir[0], -dir[1], color);
            
            if (count >= 5) {
                return true; // 连成5子（标准无禁手规则下达到或超过5子即胜）
            }
        }
        return false;
    }

    private static int countInDirection(int[][] board, int startX, int startY, int dx, int dy, int color) {
        int continuous = 0;
        int curX = startX + dx;
        int curY = startY + dy;
        while (curX >= 0 && curX < 15 && curY >= 0 && curY < 15 && board[curX][curY] == color) {
            continuous++;
            curX += dx;
            curY += dy;
        }
        return continuous;
    }
}
```

*平局判定 (Draw Check)*: 当落子数达到 225（即 `moveHistory.size() == 225`）且无胜负时，自动裁定为平局。

### 4.3 人机对战算法 (PvE AI Engine)
为保证轻量级部署与极快响应，AI 算法采用 **启发式搜索 (Heuristic Evaluation) + 局部候选点截断 + Minimax 搜索 (极大极小值算法) + Alpha-Beta 剪枝 (Alpha-Beta Pruning)**。

1. **候选落子点修剪 (Candidate Selection)**:
   * 仅考虑距离已有棋子 1~2 格范围内的空闲交叉点，大幅缩减搜索分支数（将分支从 200+ 缩减至 15~30 个）。
2. **启发式估值函数 (Heuristic Evaluation Function)**:
   * 棋型评分权重表：
     * 连五 (FIVE): `+100000`
     * 活四 (OPEN_FOUR): `+10000`
     * 冲四 (FOUR): `+1000`
     * 活三 (OPEN_THREE): `+1000`
     * 眠三 (THREE): `+100`
     * 活二 (OPEN_TWO): `+100`
   * 综合评分 = `己方成型分 * 权重` - `敌方威胁分 * 防守权重`。
3. **搜索深度 (Search Depth)**:
   * 默认设定深度为 3~4 层，单步计算时间控制在 200ms 以内，既能达到高水平棋力，又杜绝服务器 CPU 尖峰。

### 4.4 实时聊天与旁观互动 (Chat & Spectator Mechanics)
* **频道划分**: 每个房间绑定独立 STOMP Topic：`/topic/room/{roomId}/chat`。
* **消息类型**:
  * `CHAT_MESSAGE`: 玩家或旁观者发送的文本聊天。
  * `SYSTEM_NOTIFICATION`: 系统广播（如：“玩家 [Bob] 加入观战”、“[Alice] 落子 (7, 7)”、“[Alice] 胜出”）。
* **频率限制 (Rate Limiting)**: 后端基于内存令牌桶（Token Bucket）对每个 Session 限制发送频率（如 1 秒内最多 2 条），防刷屏。

---

## 5. WebSocket 通信协议与 API 规范 (Protocol & API Specifications)

### 5.1 STOMP 端点规划 (Endpoints)
* **WebSocket 连接端点**: `/ws-gomoku` (支持 SockJS 降级)
* **客户端上行目标前缀 (Application Destination Prefix)**: `/app`
* **代理广播前缀 (Broker Destination Prefix)**: `/topic`, `/queue`

### 5.2 上行操作指令 (Client -> Server: `/app/...`)

| 目标端点 (Destination) | 动作说明 (Action) | 请求体核心字段 (Payload) |
| :--- | :--- | :--- |
| `/app/room.join` | 用户加入房间 | `{ "roomId": "R101", "userId": "U01", "username": "Alice" }` |
| `/app/game.move` | 玩家落子 | `{ "roomId": "R101", "x": 7, "y": 7 }` |
| `/app/game.ai_switch` | 开启人机对战 (仅房主) | `{ "roomId": "R101", "difficulty": "MEDIUM" }` |
| `/app/chat.send` | 发送聊天消息 | `{ "roomId": "R101", "content": "好棋！" }` |
| `/app/game.surrender` | 认输退出 | `{ "roomId": "R101" }` |

### 5.3 下行广播消息 (Server -> Client: `/topic/...`)

#### 1. 状态同步: `/topic/room/{roomId}/state`
当房间状态、角色变更或开局时触发：
```json
{
  "type": "ROOM_STATE_UPDATE",
  "roomId": "R101",
  "status": "PLAYING",
  "playerBlack": { "userId": "U01", "username": "Alice" },
  "playerWhite": { "userId": "U02", "username": "Bob" },
  "spectatorsCount": 3,
  "currentTurn": "BLACK",
  "timeoutSeconds": 30
}
```

#### 2. 落子同步: `/topic/room/{roomId}/gameplay`
每次有效落子广播：
```json
{
  "type": "MOVE_SUCCESS",
  "x": 7,
  "y": 7,
  "color": "BLACK",
  "nextTurn": "WHITE",
  "isGameOver": false,
  "winner": null,
  "moveStep": 15
}
```

#### 3. 聊天与系统广播: `/topic/room/{roomId}/chat`
```json
{
  "type": "CHAT_MESSAGE",
  "senderId": "U03",
  "senderName": "Charlie",
  "role": "SPECTATOR",
  "content": "白棋这步防守很漂亮",
  "timestamp": 1725372200000
}
```

---

## 6. 健壮性设计与边界异常处理 (Robustness & Edge Cases)

### 6.1 并发与防重放控制 (Concurrency & Race Conditions)
* **竞态落子防护**: 避免双方同时发送落子包导致的脏数据。
  * 每个 `GameRoom` 实例配备专用互斥锁 `ReentrantLock`。
  * 落子处理流水线：
    1. 获取房间锁；
    2. 校验是否轮到发送方（`currentTurn.matches(sender)`）；
    3. 校验坐标范围及空位；
    4. 写入棋盘，调用胜负裁判；
    5. 切换回合，释放锁；
    6. 异步广播落子事件。

### 6.2 断线与重连机制 (Disconnection & Reconnection)
* **心跳检测 (Heartbeat)**: STOMP 默认心跳协商（`10000ms, 10000ms`）。
* **临时掉线保护 (Grace Period)**:
  * 棋手掉线触发 `SessionDisconnectEvent`，房间状态切换为 `PAUSED`。
  * 启动倒计时任务（例如 45 秒保留期）。
  * 棋手在保留期内重连成功并发送 `/app/room.reconnect`，全量恢复对局。
  * 若超时未归，系统自动判负，对方获胜。

### 6.3 回合倒计时 (Turn Timeout Handling)
* 使用 Spring 内置 `ThreadPoolTaskScheduler`。
* 每一手落子后重置 30 秒倒计时定时器。
* 定时触发时若当前玩家未完成落子，判定该玩家超时负。

---

## 7. 给 AI 的分步实现任务清单 (AI Implementation Guide / Prompts)

在将本设计交付给 Claude / GPT / Copilot 等 AI Coding Agent 时，可按照以下分步 Prompt 推进：

### 阶段一：核心数据结构与游戏裁判实现
> **Prompt 1**:  
> "请基于 Java 17 和 Spring Boot 3，创建五子棋的核心领域实体与裁判逻辑：包含 `PieceColor` 枚举、15x15 棋盘二维数组模型，以及 `GomokuReferee` 类。实现 `checkWin(int[][] board, int lastX, int lastY, int color)` 方法，进行 4 个轴向的五连子判定，并编写全覆盖的 JUnit 5 单元测试。"

### 阶段二：WebSocket/STOMP 与房间管理
> **Prompt 2**:  
> "配置 Spring WebSocket (STOMP 协议)，支持跨域与端点 `/ws-gomoku`。实现 `RoomManager` 服务，使用 `ConcurrentHashMap` 管理 `GameRoom` 实例。实现当第一个用户进入时为黑方，第二个为白方，第三个及以上为观战者的角色自动分配逻辑，并广播房间状态变化。"

### 阶段三：对战逻辑、锁机制与聊天广播
> **Prompt 3**:  
> "实现 `@MessageMapping("/game.move")` 与 `@MessageMapping("/chat.send")` 控制器。为每个房间加入 `ReentrantLock` 并发锁，实现落子合法性校验、胜负判定、回合切换及广播。观战者仅允许聊天，禁止发送落子指令。"

### 阶段四：启发式人机对战引擎 (PvE)
> **Prompt 4**:  
> "为五子棋系统增加单人人机对战功能。当房间仅有1人并触发 `/app/game.ai_switch` 时，AI 作为白方加入。实现基于启发式估值与 Minimax 搜索（带 Alpha-Beta 剪枝）的 `GomokuAiService`，当轮到白方时异步计算落子并在 300ms 内自动落子。"

### 阶段五：单页面演示前端 (Vanilla JS / Canvas)
> **Prompt 5**:  
> "编写一个单文件 `index.html`（包含 CSS 与 JavaScript），接入 `sockjs.js` 和 `stomp.js`。左侧为一个 15x15 的 HTML5 Canvas 交互式棋盘，支持高亮最新落子；右侧为一个实时聊天与观战列表面板。完整支持开局、落子、观战、人机切换与聊天。"
