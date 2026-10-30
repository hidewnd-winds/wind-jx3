# Winds JX3

![Java](https://img.shields.io/badge/Java-21-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.1-6DB33F)
![License](https://img.shields.io/badge/License-Apache%202.0-blue)

Winds JX3 是一个面向《剑网 3》相关业务的模块化后端服务，基于 Java 21 与 Spring Boot 3.3.1 构建。项目统一提供技艺成本计算、JX3API 代理、黄历图片管理、微博动态监控以及 WebSocket 实时推送能力。

## 功能特性

- **技艺成本计算**：支持单个或批量计算技艺制品成本，并使用 Redis 缓存查询结果。
- **JX3API 代理**：统一封装第三方 JX3API 请求及认证参数。
- **黄历管理**：上传黄历图片至阿里云 OSS，按日期保存 MongoDB 记录并发布更新事件。
- **微博监控**：管理监控博主与抓取账号，定时拉取最新动态，通过 MongoDB 锁支持多实例调度。
- **实时推送**：通过统一的 `/ws` 端点推送连接、黄历更新和微博更新事件。
- **剑三官方监听**：直接采集官网新闻、活动、公告，以及正式服区服网关状态和补丁清单，保存记录并通过 `/ws` 在线广播。
- **接口文档**：集成 Knife4j、Swagger UI 与 OpenAPI JSON。

## 技术栈

| 类别 | 技术 |
| --- | --- |
| 运行环境 | Java 21 |
| 应用框架 | Spring Boot 3.3.1 |
| 构建工具 | Maven 多模块工程 |
| 数据存储 | MongoDB |
| 缓存 | Redis |
| 对象存储 | Alibaba Cloud OSS |
| 实时通信 | Spring WebSocket |
| 接口文档 | Springdoc OpenAPI、Knife4j |

## 项目结构

| 模块 | 职责 |
| --- | --- |
| `winds-application` | 统一启动入口、运行配置与可执行 Jar 打包 |
| `winds-common` | 跨模块共享的响应模型、基础实体与公共能力 |
| `winds-costing` | 技艺成本计算、JX3Box 数据访问、JX3API 代理与缓存 |
| `winds-bot` | 黄历图片上传、MongoDB 持久化、OSS 存储与领域事件发布 |
| `winds-scout` | 微博博主和抓取账号管理、定时监控与动态持久化 |
| `winds-ws` | WebSocket 接入、Token 鉴权与业务事件广播 |
| `winds-jx3` | 剑三官方新闻、维护、开关服和更新包监听与留档 |

```text
wind-jx3/
├── winds-application/       # 统一启动与配置
├── winds-common/            # 公共模型与基础能力
├── winds-costing/           # 成本计算与 JX3API 代理
├── winds-bot/               # 黄历业务
├── winds-scout/             # 微博监控业务
├── winds-jx3/               # 剑三官方数据监听
├── winds-ws/                # WebSocket 服务
├── docs/                    # 接口与业务文档
├── pom.xml                  # Maven 聚合 POM
└── README.md
```

## 快速开始

### 环境要求

- JDK 21
- Maven（仓库未提供 Maven Wrapper）
- MongoDB
- Redis
- Alibaba Cloud OSS 配置
- 可用的 JX3API 凭据；如启用微博监控，还需要有效的微博 Cookie

### 配置

应用配置统一位于 `winds-application/src/main/resources/`：

- `application.yml`：服务端口、缓存、文件上传与 OpenAPI 配置。
- `application-dev.yml`：开发环境的 MongoDB、Redis、OSS、JX3API 和默认服务器配置。

默认启用 `dev` Profile。首次启动前，请按部署环境检查以下配置组：

| 配置前缀 | 用途 |
| --- | --- |
| `spring.data.mongodb` | MongoDB 连接与认证 |
| `spring.data.redis` | Redis 连接与连接池 |
| `aliyun.oss` | OSS Endpoint、Bucket 与访问凭据 |
| `jx3api` | JX3API 地址、Token 与 Ticket |
| `box.default.server` | 默认游戏服务器 |

不要将真实密码、AccessKey、Token 或 Cookie 提交到版本控制。

### MongoDB 连接维护

所有模块通过 Spring 管理的共享 `MongoClient` / `MongoTemplate` 使用 MongoDB 官方驱动连接池。驱动负责连接复用、心跳、故障连接淘汰及按需重建，Spring 在应用关闭时关闭客户端；业务代码不单独创建客户端或额外包装连接池。

地址、数据库和认证继续使用 `spring.data.mongodb`。连接池及超时统一使用 `winds.mongodb`，这些设置优先于连接 URI 中的同名驱动选项，可通过环境变量覆盖，例如 `WINDS_MONGODB_READTIMEOUT=60s`。

| 配置项（`winds.mongodb.*`） | 默认值 | 含义 |
| --- | --- | --- |
| `min-pool-size` / `max-pool-size` | `2` / `100` | 每台 MongoDB 服务器连接池的最小保有量和容量上限 |
| `max-connecting` | `2` | 每个池同时建立连接的上限 |
| `max-wait-time` | `10s` | 等待空闲连接或建连许可的上限 |
| `max-connection-idle-time` | `5m` | 空闲连接回收阈值 |
| `maintenance-frequency` | `30s` | 驱动后台连接池维护间隔 |
| `server-selection-timeout` | `5s` | 等待可用 MongoDB 服务器的上限 |
| `connect-timeout` | `5s` | 建立 TCP 连接的超时 |
| `read-timeout` | `30s` | 业务连接等待网络读取的超时，避免无限占用连接或建连许可 |

上述超时分别约束各阶段，不是整个请求的总时限；驱动重试可能增加总耗时。读取超时不能证明写入未发生，也不保证服务端操作已取消；不要据此无条件重试非幂等写入。超时和维护间隔必须为正数，最小连接数允许为零且不得大于容量上限。

连接池故障仍需结合 MongoDB 服务端日志、应用线程栈和网络情况定位。仅提高容量或重启应用不能证明根因已解决。项目保留 Actuator 的 MongoDB 连接池指标监听器，不额外开放管理端点。

### 构建与启动

Windows PowerShell：

```powershell
mvn.cmd clean package -DskipTests
java.exe -jar .\winds-application\target\winds-application.jar
```

Linux 或 macOS：

```bash
mvn clean package -DskipTests
java -jar winds-application/target/winds-application.jar
```

服务默认监听 `9002` 端口。

## 接口文档

启动应用后可访问：

| 文档 | 地址 |
| --- | --- |
| Knife4j | `http://localhost:9002/doc.html` |
| Swagger UI | `http://localhost:9002/swagger-ui.html` |
| OpenAPI JSON | `http://localhost:9002/v3/api-docs` |

主要接口分组：

| 能力 | 接口 |
| --- | --- |
| 单个技艺成本计算 | `POST /costing/one` |
| 批量技艺成本计算 | `POST /costing/list` |
| JX3API 代理 | `POST /proxy/jx3api` |
| 黄历图片更新 | `POST /thire/huangli/update` |
| 微博监控管理 | `/scout/weibo/**` |
| WebSocket 推送 | `/ws` |

完整的 HTTP、WebSocket 接口、权限、字段与消息示例统一见 [接口参考文档](docs/winds_jx3_api.md)。

## WebSocket

WebSocket 与 HTTP 服务共用 `9002` 端口，连接地址为：

```text
ws://localhost:9002/ws?token=<WS_TOKEN>
```

也可以在握手请求中使用 `Authorization: Bearer <WS_TOKEN>`。Token 必须对应 MongoDB `ws_auth_token` 集合中的有效记录；微博账号池接口额外要求 `scout.weibo.manage` 权限，订阅和推文查询允许普通有效 Token 调用。

服务端事件包括：

- `connection.success`：连接建立成功。
- `huangli.updated`：黄历图片更新。
- `weibo.updated`：仅推送当前 Token 已订阅博主的微博动态。
- `weibo.account.invalid`、`weibo.account.recovery_failed`、`weibo.pool.unavailable`：仅向当前有微博管理权限的连接推送账号失效、恢复失败及账号池不可用预警。告警持久化，管理客户端通过 `/scout/weibo/alerts` 补拉并在本地保存后确认接收；确认不表示超管私聊已送达。
- `jx3.news.updated`、`jx3.maintenance.updated`、`jx3.server.changed`、`jx3.patch.updated`：剑三官方数据变化。
- `jx3.earthquake.warning`：转发 JX3API 事件版 `action=2007` 国内地震预警，向全部在线连接广播；沿用 `winds.jx3.enabled` 与 `winds.jx3.socket.enabled` 开关。

微博和剑三事件的日期时间统一为北京时间 `yyyy-MM-dd HH:mm:ss`。

事件字段与完整 JSON 示例见 [接口参考文档](docs/winds_jx3_api.md#5-websocket)。

## 测试

运行全部测试：

```powershell
mvn.cmd test
```

运行指定模块及其依赖的测试：

```powershell
mvn.cmd -pl winds-scout -am test
mvn.cmd -pl winds-ws -am test
```

执行完整 Reactor 校验：

```powershell
mvn.cmd clean verify -DskipTests=false
```

## 贡献

欢迎通过 Issue 或 Pull Request 参与改进。提交变更前请：

1. 保持现有模块职责与代码风格。
2. 不提交密钥、Token、Cookie 等敏感信息。
3. 为行为变更补充必要测试，并在 Pull Request 中说明验证结果。
4. 同步更新受影响的接口文档和示例。

## 许可证

本项目基于 [Apache License 2.0](LICENSE) 开源。
