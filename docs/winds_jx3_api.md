# Winds JX3 接口文档

## 1. 接入约定

本文覆盖当前 15 个业务 HTTP 接口、1 个 WebSocket 接入地址和 8 类服务端消息。每个 HTTP 接口均单独给出完整请求参数、请求示例、响应参数和响应示例。示例数据用于说明结构，不代表实时业务数据。

| 项目 | 说明 |
| --- | --- |
| HTTP 基础地址 | `http://costing.hidewnd.cn`；部署 TLS 后使用 HTTPS |
| WebSocket 地址 | `ws://costing.hidewnd.cn/ws`；部署 TLS 后使用 WSS |
| 文档页面 | `/doc.html`、`/swagger-ui.html` |
| OpenAPI JSON | `/v3/api-docs` |
| 字符编码 | UTF-8 |
| JSON 请求 | `Content-Type: application/json` |
| 日期时间 | 微博与剑三时间为北京时间 `yyyy-MM-dd HH:mm:ss`；日期为 `yyyy-MM-dd` |
| 字段路径 | `obj[].id` 表示数组元素字段；`<key>` 表示动态映射键 |
| 可空 | 表格“可空=是”表示可能为 null；空数组与 null 的含义不同 |
| ID | UID、推文 ID 等按字符串处理，避免大整数精度丢失 |

### 1.1 权限

微博 HTTP 接口通过 `Authorization: Bearer <TOKEN>` 鉴权。订阅归属只取请求头中的 Token，请求体中的 token/tokens 不参与鉴权或订阅归属。

| 能力 | 普通有效 Token | 具有 scout.weibo.manage 的有效 Token |
| --- | --- | --- |
| 订阅、取消订阅、查询自己订阅的博主、查询最新推文 | 允许 | 允许 |
| 查看和管理微博抓取账号池 | 禁止 | 允许 |
| WS 接收微博推文 | 仅已订阅 UID | 仅已订阅 UID |
| WS 接收微博账号失效预警 | 不接收 | 接收 |

### 1.2 HTTP 接口总览

| 章节 | 方法 | 完整路径 | 鉴权 | 用途 |
| --- | --- | --- | --- | --- |
| 2.1 | GET | `/scout/weibo/bloggers` | 普通 Token | 查看自己的订阅 |
| 2.2 | POST | `/scout/weibo/bloggers` | 普通 Token | 按 UID 或中文全称订阅 |
| 2.3 | GET | `/scout/weibo/bloggers/{uid}` | 普通 Token | 查看已订阅博主详情 |
| 2.4 | DELETE | `/scout/weibo/bloggers/{uid}` | 普通 Token | 取消自己的订阅 |
| 2.5 | GET | `/scout/weibo/posts/latest` | 普通 Token | 查询最新推文 |
| 3.1 | GET | `/scout/weibo/accounts` | 管理 Token | 查看抓取账号列表 |
| 3.2 | POST | `/scout/weibo/accounts` | 管理 Token | 创建抓取账号 |
| 3.3 | GET | `/scout/weibo/accounts/{id}` | 管理 Token | 查看抓取账号详情 |
| 3.4 | PUT | `/scout/weibo/accounts/{id}/credentials` | 管理 Token | 更新抓取账号凭据 |
| 3.5 | PATCH | `/scout/weibo/accounts/{id}/status` | 管理 Token | 修改抓取账号状态 |
| 3.6 | DELETE | `/scout/weibo/accounts/{id}` | 管理 Token | 删除抓取账号 |
| 3.7 | GET | `/scout/weibo/alerts` | 管理 Token | 分批补拉当前 Token 未确认的告警 |
| 3.8 | GET | `/scout/weibo/alerts/{id}` | 管理 Token | 告警快照与当前恢复状态 |
| 3.9 | POST | `/scout/weibo/alerts/{id}/ack` | 管理 Token | 确认已持久化接收告警 |
| 4.1 | POST | `/costing/one` | 无 | 查询单个技艺制品成本 |
| 4.2 | POST | `/costing/list` | 无 | 查询多个技艺制品成本 |
| 4.3 | POST | `/proxy/jx3api` | 无 | 代理调用 JX3API |
| 4.4 | POST | `/thire/huangli/update` | 无 | 上传黄历图片 |

### 1.3 响应与错误

所有成功 HTTP 响应状态均为 200，JSON 为 `success/code/msg/obj`。每个接口下方的响应表均完整列出这四个字段及其业务字段。

微博接口错误字段：

| 字段 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| success | boolean | 否 | 固定 false |
| code | integer | 否 | 与 HTTP 错误状态一致 |
| msg | string | 否 | 错误原因 |
| obj | null | 是 | 业务错误固定 null；鉴权错误直接省略此字段 |

| HTTP 状态 | 原因 | 常见 msg |
| --- | --- | --- |
| 400 | 参数缺失、格式或校验错误 | 请求参数缺失或格式错误；具体字段校验信息 |
| 401 | Token 缺失、无效或停用 | 未授权 |
| 403 | 普通 Token 访问账号池 | 权限不足 |
| 404 | 资源、匹配或有效推文不存在 | 微博账号不存在；未订阅该微博博主；微博别称不存在；暂无有效微博推文 |
| 409 | 账号 ID 重复或名称匹配歧义 | 微博账号已存在；存在多个同名微博博主，请使用UID；别称对应多个博主，请使用UID |
| 500 | 服务或数据保存失败 | 微博服务处理失败 |
| 502 | 微博上游请求失败 | 微博用户查询失败；微博推文查询失败 |
| 503 | 无可用抓取账号或鉴权服务异常 | 没有可用的微博账号；鉴权服务暂不可用 |

鉴权错误示例（HTTP 403）：

```json
{
  "success": false,
  "code": 403,
  "msg": "权限不足"
}
```

业务错误示例（HTTP 404）：

```json
{
  "success": false,
  "code": 404,
  "msg": "微博账号不存在",
  "obj": null
}
```

成本、代理、黄历沿用已有错误响应：部分错误 HTTP 状态仍为 200，`success` 仍可能为 true。调用方必须同时判断 code；101 为参数校验错误、501 为部分空值错误、502 为部分运行错误。代理外层成功也不代表上游业务成功。

普通接口参数错误示例（HTTP 200）：

```json
{
  "success": true,
  "code": 101,
  "msg": "[formulaName]配方名称不能为空",
  "obj": null
}
```

## 2. 微博订阅与查询

以下 5 个接口均允许普通有效 Token 调用；管理 Token 使用相同订阅规则，不拥有全局博主修改权。

### 2.1 查看当前 Token 的订阅列表

**接口：`GET /scout/weibo/bloggers`**

**鉴权：** 普通有效 Token。

#### 请求参数

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| Authorization | header | string | 是 | Bearer &lt;TOKEN&gt;；普通有效 Token |

无路径参数、查询参数和请求体。

#### 请求示例

```http
GET /scout/weibo/bloggers HTTP/1.1
Host: <host>:9002
Authorization: Bearer <TOKEN>
```

#### 响应参数

成功 HTTP 状态：200。

| 字段 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| success | boolean | 否 | 成功时为 true |
| code | integer | 否 | 成功时为 200 |
| msg | string | 否 | 成功时为“请求成功” |
| obj | object[] | 否 | 业务数组，无结果为 [] |
| obj[].uid | string | 否 | 微博 UID |
| obj[].screenName | string | 是 | 官方博主名称；历史记录可能为空，重新订阅可刷新 |
| obj[].avatar | string | 是 | 官方头像地址；历史记录可能为空，重新订阅可补全 |
| obj[].aliases | string[] | 否 | 系统记录的别称，无别称为 [] |
| obj[].createdAt | string | 是 | 创建时间，北京时间 yyyy-MM-dd HH:mm:ss；旧记录可能为 null |
| obj[].updatedAt | string | 是 | 最近更新时间，格式同上 |

#### 响应示例

```json
{
  "success": true,
  "code": 200,
  "msg": "请求成功",
  "obj": [
    {
      "uid": "1761587065",
      "screenName": "剑网3",
      "avatar": "https://example.com/avatar.jpg",
      "aliases": [
        "官博"
      ],
      "createdAt": "2026-09-09 10:00:00",
      "updatedAt": "2026-09-09 10:00:00"
    }
  ]
}
```

**调用说明：** 只返回当前 Token 订阅的博主；无订阅时 obj=[]。不返回任何订阅 Token。无分页参数。

### 2.2 订阅微博博主（UID／中文全称）

**接口：`POST /scout/weibo/bloggers`**

**鉴权：** 普通有效 Token。

#### 请求参数

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| Authorization | header | string | 是 | Bearer &lt;TOKEN&gt;；普通有效 Token |
| Content-Type | header | string | 是 | application/json |
| uid | body | string / null | 条件必填 | 纯数字 UID；与 screenName 至少填写一项。传 uid 时直接使用 UID；空字符串不合法 |
| screenName | body | string / null | 条件必填 | 未传 uid 时必须为非空白的微博完整名称，用于上游精确匹配；传 uid 时忽略此字段，名称以官方资料为准 |
| aliases | body | string[] / null | 否 | 首次建档保存的别称，省略或 null 时保存 [] |
| aliases[] | body | string | 提供数组元素时 | 每个别称不能为 null 或空白 |

#### 请求示例

```http
POST /scout/weibo/bloggers HTTP/1.1
Host: <host>:9002
Authorization: Bearer <TOKEN>
Content-Type: application/json

{
  "uid": "1761587065",
  "screenName": "剑网3",
  "aliases": [
    "官博"
  ]
}
```

仅传中文全称时，请求方法、地址和请求头不变，请求体改为：

```json
{
  "screenName": "剑网3",
  "aliases": [
    "官博"
  ]
}
```

#### 响应参数

成功 HTTP 状态：200。

| 字段 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| success | boolean | 否 | 成功时为 true |
| code | integer | 否 | 成功时为 200 |
| msg | string | 否 | 成功时为“请求成功” |
| obj | object | 否 | 本接口业务对象 |
| obj.uid | string | 否 | 微博 UID |
| obj.screenName | string | 是 | 官方博主名称；订阅成功时非空，历史记录可能为空 |
| obj.avatar | string | 是 | 官方头像地址；订阅成功时非空，历史记录可能为空 |
| obj.aliases | string[] | 否 | 系统记录的别称，无别称为 [] |
| obj.createdAt | string | 是 | 创建时间，北京时间 yyyy-MM-dd HH:mm:ss；旧记录可能为 null |
| obj.updatedAt | string | 是 | 最近更新时间，格式同上 |

#### 响应示例

```json
{
  "success": true,
  "code": 200,
  "msg": "请求成功",
  "obj": {
    "uid": "1761587065",
    "screenName": "剑网3",
    "avatar": "https://example.com/avatar.jpg",
    "aliases": [
      "官博"
    ],
    "createdAt": "2026-09-09 10:00:00",
    "updatedAt": "2026-09-09 10:00:00"
  }
}
```

**调用说明：** 重复订阅幂等；每次订阅先查询官方名称和头像，再原子追加当前 Token 并刷新资料，保留已有别称和其他订阅者。名称只接受微博搜索返回的全称完全匹配，不选择相似结果；无匹配返回 404，同名不同 UID 返回 409，可改用 UID。名称或资料查询上游失败返回 502，无可用账号返回 503；查询失败不写入订阅。uid 与 screenName 同时提供时以 uid 定位，不调用名称搜索。资料来自主页 userInfo，不要求博主已有推文。

每次订阅（含重复订阅）会立即抓取最新一条有效的非置顶微博，先以 `no_push=true` 作为基线落库，再保存订阅关系并返回博主信息，不触发更新事件。已有推文只同步内容，不重置其推送标记。没有有效推文时仍可订阅，不创建占位记录，后续沿用轮询首次基线规则。基线抓取失败返回 502，基线落库失败返回 500，均不新增订阅关系；若基线已落库而订阅保存失败，保留该记录，重试可幂等同步。

### 2.3 查看已订阅博主详情

**接口：`GET /scout/weibo/bloggers/{uid}`**

**鉴权：** 普通有效 Token。

#### 请求参数

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| Authorization | header | string | 是 | Bearer &lt;TOKEN&gt;；普通有效 Token |
| uid | path | string | 是 | 目标微博 UID，通常使用订阅响应中的 uid |

无请求体。

#### 请求示例

```http
GET /scout/weibo/bloggers/1761587065 HTTP/1.1
Host: <host>:9002
Authorization: Bearer <TOKEN>
```

#### 响应参数

成功 HTTP 状态：200。

| 字段 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| success | boolean | 否 | 成功时为 true |
| code | integer | 否 | 成功时为 200 |
| msg | string | 否 | 成功时为“请求成功” |
| obj | object | 否 | 本接口业务对象 |
| obj.uid | string | 否 | 微博 UID |
| obj.screenName | string | 是 | 官方博主名称；订阅成功时非空，历史记录可能为空 |
| obj.avatar | string | 是 | 官方头像地址；订阅成功时非空，历史记录可能为空 |
| obj.aliases | string[] | 否 | 系统记录的别称，无别称为 [] |
| obj.createdAt | string | 是 | 创建时间，北京时间 yyyy-MM-dd HH:mm:ss；旧记录可能为 null |
| obj.updatedAt | string | 是 | 最近更新时间，格式同上 |

#### 响应示例

```json
{
  "success": true,
  "code": 200,
  "msg": "请求成功",
  "obj": {
    "uid": "1761587065",
    "screenName": "剑网3",
    "avatar": "https://example.com/avatar.jpg",
    "aliases": [
      "官博"
    ],
    "createdAt": "2026-09-09 10:00:00",
    "updatedAt": "2026-09-09 10:00:00"
  }
}
```

**调用说明：** 博主不存在或当前 Token 未订阅，均返回 404：未订阅该微博博主。

### 2.4 取消订阅微博博主

**接口：`DELETE /scout/weibo/bloggers/{uid}`**

**鉴权：** 普通有效 Token。

#### 请求参数

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| Authorization | header | string | 是 | Bearer &lt;TOKEN&gt;；普通有效 Token |
| uid | path | string | 是 | 要取消订阅的微博 UID |

无请求体。

#### 请求示例

```http
DELETE /scout/weibo/bloggers/1761587065 HTTP/1.1
Host: <host>:9002
Authorization: Bearer <TOKEN>
```

#### 响应参数

成功 HTTP 状态：200。

| 字段 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| success | boolean | 否 | 成功时为 true |
| code | integer | 否 | 成功时为 200 |
| msg | string | 否 | 成功时为“取消订阅成功” |
| obj | null | 是 | 无返回数据，固定 null |

#### 响应示例

```json
{
  "success": true,
  "code": 200,
  "msg": "取消订阅成功",
  "obj": null
}
```

**调用说明：** 只移除当前 Token；未订阅或重复取消也返回成功。保留其他订阅者、博主资料和历史推文。最后一个订阅取消后停止后续轮询，已发出的消息不会撤回。

### 2.5 查询指定博主最新推文

**接口：`GET /scout/weibo/posts/latest`**

**鉴权：** 普通有效 Token。

#### 请求参数

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| Authorization | header | string | 是 | Bearer &lt;TOKEN&gt;；普通有效 Token |
| query | query | string | 是 | 非空白 UID 或已保存的别称；纯数字解释为 UID，其余精确匹配 aliases。中文别称必须 URL 编码 |

无请求体。

#### 请求示例

```http
GET /scout/weibo/posts/latest?query=1761587065 HTTP/1.1
Host: <host>:9002
Authorization: Bearer <TOKEN>
```

按“官博”别称查询：

```http
GET /scout/weibo/posts/latest?query=%E5%AE%98%E5%8D%9A HTTP/1.1
Host: <host>:9002
Authorization: Bearer <TOKEN>
```

#### 响应参数

成功 HTTP 状态：200。

| 字段 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| success | boolean | 否 | 成功时为 true |
| code | integer | 否 | 成功时为 200 |
| msg | string | 否 | 成功时为“请求成功” |
| obj | object | 否 | 本接口业务对象 |
| obj.uid | string | 否 | 微博 UID |
| obj.screenName | string | 是 | 博主名称，可能为空字符串 |
| obj.weiboId | string | 否 | 微博 ID |
| obj.publishedAt | string | 是 | 发布时间，北京时间 yyyy-MM-dd HH:mm:ss；旧记录可能缺失 |
| obj.content | string | 是 | 纯文本正文，保留段落、换行、话题及 @ 提及 |
| obj.rawContent | string | 是 | 上游原始 HTML；渲染前须清理不可信 HTML |
| obj.source | string | 是 | 发布来源 |
| obj.regionName | string | 是 | 发布地区 |
| obj.repostsCount | integer(int64) | 是 | 转发数，null 不代表 0 |
| obj.commentsCount | integer(int64) | 是 | 评论数，null 不代表 0 |
| obj.attitudesCount | integer(int64) | 是 | 点赞数，null 不代表 0 |
| obj.url | string | 是 | 推文详情地址 |
| obj.images | string[] | 是 | 正文图片及卡片、视频封面；新记录无图片为 [] |
| obj.topics | string[] | 是 | 话题名称；新记录无话题为 [] |
| obj.videoCoverImages | string[] | 是 | 卡片和视频封面；新记录无封面为 [] |
| obj.retweet | object | 是 | 转发原文，非转发为 null |
| obj.media | object[] | 是 | 媒体资源；新记录无媒体为 [] |
| obj.links | object[] | 是 | 正文链接、用户提及、话题和特殊内容卡片；新记录无内容为 [] |
| obj.article | object | 是 | 头条文章；无文章为 null |
| obj.truncated | boolean | 否 | 上游为长文但未取得完整正文时为 true |
| obj.media[].type | string | 否 | image 图片、video 视频、livephoto 动态照片 |
| obj.media[].url | string | 否 | 资源地址，保留签名参数；livephoto 为视频地址 |
| obj.media[].coverUrl | string | 是 | 封面地址；livephoto 为静态图片地址 |
| obj.links[].type | string | 是 | 保留上游链接或卡片类型，不限定为固定枚举 |
| obj.links[].title | string | 是 | 标题 |
| obj.links[].url | string | 是 | 目标链接 |
| obj.links[].description | string | 是 | 摘要 |
| obj.links[].image | string | 是 | 卡片图片 |
| obj.article.title | string | 是 | 文章标题 |
| obj.article.url | string | 是 | 文章链接 |
| obj.article.summary | string | 是 | 卡片摘要 |
| obj.article.content | string | 是 | 全文纯文本；上游未提供时为 null |
| obj.article.rawContent | string | 是 | 全文原始 HTML，使用前须清理 |
| obj.article.publishedAt | string | 是 | 文章发布时间 |
| obj.article.paid | boolean | 是 | 上游付费标志，未知为 null |
| obj.article.trial | boolean | 是 | 上游试读标志，未知为 null |
| obj.retweet.uid | string | 是 | 微博 UID |
| obj.retweet.screenName | string | 是 | 博主名称，可能为空字符串 |
| obj.retweet.weiboId | string | 是 | 微博 ID |
| obj.retweet.publishedAt | string | 是 | 发布时间，北京时间 yyyy-MM-dd HH:mm:ss；旧记录可能缺失 |
| obj.retweet.content | string | 是 | 纯文本正文，保留段落、换行、话题及 @ 提及 |
| obj.retweet.rawContent | string | 是 | 上游原始 HTML；渲染前须清理不可信 HTML |
| obj.retweet.source | string | 是 | 发布来源 |
| obj.retweet.regionName | string | 是 | 发布地区 |
| obj.retweet.repostsCount | integer(int64) | 是 | 转发数，null 不代表 0 |
| obj.retweet.commentsCount | integer(int64) | 是 | 评论数，null 不代表 0 |
| obj.retweet.attitudesCount | integer(int64) | 是 | 点赞数，null 不代表 0 |
| obj.retweet.url | string | 是 | 推文详情地址 |
| obj.retweet.images | string[] | 是 | 正文图片及卡片、视频封面；新记录无图片为 [] |
| obj.retweet.topics | string[] | 是 | 话题名称；新记录无话题为 [] |
| obj.retweet.videoCoverImages | string[] | 是 | 卡片和视频封面；新记录无封面为 [] |
| obj.retweet.retweet | object | 是 | 转发原文，非转发为 null |
| obj.retweet.media | object[] | 是 | 媒体资源；新记录无媒体为 [] |
| obj.retweet.links | object[] | 是 | 正文链接、用户提及、话题和特殊内容卡片；新记录无内容为 [] |
| obj.retweet.article | object | 是 | 头条文章；无文章为 null |
| obj.retweet.truncated | boolean | 否 | 上游为长文但未取得完整正文时为 true |
| obj.retweet.unavailable | boolean | 否 | 原文被删除或原作者不可访问时为 true |
| obj.retweet.media[].type | string | 否 | image 图片、video 视频、livephoto 动态照片 |
| obj.retweet.media[].url | string | 否 | 资源地址，保留签名参数；livephoto 为视频地址 |
| obj.retweet.media[].coverUrl | string | 是 | 封面地址；livephoto 为静态图片地址 |
| obj.retweet.links[].type | string | 是 | 保留上游链接或卡片类型，不限定为固定枚举 |
| obj.retweet.links[].title | string | 是 | 标题 |
| obj.retweet.links[].url | string | 是 | 目标链接 |
| obj.retweet.links[].description | string | 是 | 摘要 |
| obj.retweet.links[].image | string | 是 | 卡片图片 |
| obj.retweet.article.title | string | 是 | 文章标题 |
| obj.retweet.article.url | string | 是 | 文章链接 |
| obj.retweet.article.summary | string | 是 | 卡片摘要 |
| obj.retweet.article.content | string | 是 | 全文纯文本；上游未提供时为 null |
| obj.retweet.article.rawContent | string | 是 | 全文原始 HTML，使用前须清理 |
| obj.retweet.article.publishedAt | string | 是 | 文章发布时间 |
| obj.retweet.article.paid | boolean | 是 | 上游付费标志，未知为 null |
| obj.retweet.article.trial | boolean | 是 | 上游试读标志，未知为 null |

#### 响应示例

```json
{
  "success": true,
  "code": 200,
  "msg": "请求成功",
  "obj": {
    "uid": "1761587065",
    "screenName": "剑网3",
    "weiboId": "5140000000000000",
    "publishedAt": "2026-09-09 10:00:00",
    "content": "微博正文 #剑网3#",
    "rawContent": "<p>微博正文 #剑网3#</p>",
    "source": "微博网页版",
    "regionName": null,
    "repostsCount": 12,
    "commentsCount": 34,
    "attitudesCount": 56,
    "url": "https://m.weibo.cn/detail/5140000000000000",
    "images": [
      "https://example.com/main.jpg"
    ],
    "topics": [
      "剑网3"
    ],
    "videoCoverImages": [],
    "retweet": {
      "uid": "1234567890",
      "screenName": "原作者",
      "weiboId": "5139999999999999",
      "publishedAt": "2026-09-09 09:50:00",
      "content": "原微博正文",
      "rawContent": "<p>原微博正文</p>",
      "source": "微博网页版",
      "regionName": null,
      "repostsCount": 12,
      "commentsCount": 34,
      "attitudesCount": 56,
      "url": "https://m.weibo.cn/detail/5139999999999999",
      "images": [],
      "topics": [],
      "videoCoverImages": [],
      "retweet": null,
      "media": [],
      "links": [],
      "article": null,
      "truncated": false,
      "unavailable": false
    },
    "media": [
      {
        "type": "image",
        "url": "https://example.com/main.jpg",
        "coverUrl": null
      }
    ],
    "links": [
      {
        "type": "article",
        "title": "示例文章",
        "url": "https://example.com/article",
        "description": "文章摘要",
        "image": null
      }
    ],
    "article": {
      "title": "示例文章",
      "url": "https://example.com/article",
      "summary": "文章摘要",
      "content": null,
      "rawContent": null,
      "publishedAt": null,
      "paid": null,
      "trial": null
    },
    "truncated": false
  }
}
```

**调用说明：** 无需先订阅。优先返回库中按发布时间排序最新的一条；仅无记录时抓取最新有效非置顶推文并入库。因此返回的是最新采集记录，不保证请求时刻的上游实时状态。查询不会订阅或触发 WS 推送。未知别称、无推文返回 404，别称对应多个 UID 返回 409。retweet.retweet 为相同转发结构递归，最多五层；最深层为 null。旧记录未采集的内容或数组可能为 null。

旧版本的全局 `PUT /scout/weibo/bloggers/{uid}`、`PATCH /scout/weibo/bloggers/{uid}/status` 已移除，不属于当前可调用接口。旧博主未记录订阅 Token 时需要重新订阅才恢复监听。

## 3. 微博账号池管理接口

以下 6 个接口全部要求有效 Token 含 `scout.weibo.manage` 权限。这里的“账号”指服务端用于抓取微博的登录账号，不是被订阅的博主。

### 3.1 查看抓取账号列表

**接口：`GET /scout/weibo/accounts`**

**鉴权：** 有效 Token，且包含 scout.weibo.manage 权限。

#### 请求参数

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| Authorization | header | string | 是 | Bearer &lt;TOKEN&gt;；有效 Token，且包含 scout.weibo.manage 权限 |

无路径参数、查询参数和请求体。

#### 请求示例

```http
GET /scout/weibo/accounts HTTP/1.1
Host: <host>:9002
Authorization: Bearer <MANAGEMENT_TOKEN>
```

#### 响应参数

成功 HTTP 状态：200。

| 字段 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| success | boolean | 否 | 成功时为 true |
| code | integer | 否 | 成功时为 200 |
| msg | string | 否 | 成功时为“请求成功” |
| obj | object[] | 否 | 业务数组，无结果为 [] |
| obj[].id | string | 否 | 抓取账号唯一 ID |
| obj[].status | string | 是 | active 可选用，fail 停用；旧记录可能为 null |
| obj[].failCount | integer | 否 | 累计失败次数；成功请求不清零，更新凭据清零 |
| obj[].requestCount | integer(int64) | 否 | 累计选用次数 |
| obj[].lastErrorAt | string | 是 | 最近失败时间；从未失败或更新凭据后为 null |
| obj[].lastUsedAt | string | 是 | 最近使用时间；未使用时为 null |
| obj[].recoverAt | string | 是 | 计划自动恢复时间；无自动恢复时间时为 null |
| obj[].createdAt | string | 是 | 创建时间；旧记录可能为 null |
| obj[].updatedAt | string | 是 | 最近更新时间；旧记录可能为 null |

#### 响应示例

```json
{
  "success": true,
  "code": 200,
  "msg": "请求成功",
  "obj": [
    {
      "id": "account-1",
      "status": "active",
      "failCount": 0,
      "requestCount": 12,
      "lastErrorAt": null,
      "lastUsedAt": "2026-09-09 10:00:00",
      "recoverAt": null,
      "createdAt": "2026-09-09 09:00:00",
      "updatedAt": "2026-09-09 10:00:00"
    }
  ]
}
```

**调用说明：** 无账号时 obj=[]，无分页参数。所有返回数据都不含 Cookie、XSRF Token 和错误详情。

### 3.2 创建抓取账号

**接口：`POST /scout/weibo/accounts`**

**鉴权：** 有效 Token，且包含 scout.weibo.manage 权限。

#### 请求参数

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| Authorization | header | string | 是 | Bearer &lt;TOKEN&gt;；有效 Token，且包含 scout.weibo.manage 权限 |
| Content-Type | header | string | 是 | application/json |
| id | body | string | 是 | 调用方指定的唯一账号 ID，不能为空白 |
| cookie | body | string | 是 | 完整微博登录 Cookie，不能为空白，必须含有效 XSRF-TOKEN；不接收单独的 xsrfToken 参数 |

#### 请求示例

```http
POST /scout/weibo/accounts HTTP/1.1
Host: <host>:9002
Authorization: Bearer <MANAGEMENT_TOKEN>
Content-Type: application/json

{
  "id": "account-1",
  "cookie": "SUB=<SUB>; XSRF-TOKEN=<XSRF_TOKEN>"
}
```

#### 响应参数

成功 HTTP 状态：200。

| 字段 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| success | boolean | 否 | 成功时为 true |
| code | integer | 否 | 成功时为 200 |
| msg | string | 否 | 成功时为“请求成功” |
| obj | object | 否 | 本接口业务对象 |
| obj.id | string | 否 | 抓取账号唯一 ID |
| obj.status | string | 是 | active 可选用，fail 停用；旧记录可能为 null |
| obj.failCount | integer | 否 | 累计失败次数；成功请求不清零，更新凭据清零 |
| obj.requestCount | integer(int64) | 否 | 累计选用次数 |
| obj.lastErrorAt | string | 是 | 最近失败时间；从未失败或更新凭据后为 null |
| obj.lastUsedAt | string | 是 | 最近使用时间；未使用时为 null |
| obj.recoverAt | string | 是 | 计划自动恢复时间；无自动恢复时间时为 null |
| obj.createdAt | string | 是 | 创建时间；旧记录可能为 null |
| obj.updatedAt | string | 是 | 最近更新时间；旧记录可能为 null |

#### 响应示例

```json
{
  "success": true,
  "code": 200,
  "msg": "请求成功",
  "obj": {
    "id": "account-1",
    "status": "active",
    "failCount": 0,
    "requestCount": 0,
    "lastErrorAt": null,
    "lastUsedAt": null,
    "recoverAt": null,
    "createdAt": "2026-09-09 10:00:00",
    "updatedAt": "2026-09-09 10:00:00"
  }
}
```

**调用说明：** 创建后 status=active，failCount=0，requestCount=0。重复 ID 返回 409；Cookie 缺少有效 XSRF-TOKEN 返回 400。示例 Cookie 为占位符，需替换为真实凭据，响应不会回传凭据。

### 3.3 查看抓取账号详情

**接口：`GET /scout/weibo/accounts/{id}`**

**鉴权：** 有效 Token，且包含 scout.weibo.manage 权限。

#### 请求参数

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| Authorization | header | string | 是 | Bearer &lt;TOKEN&gt;；有效 Token，且包含 scout.weibo.manage 权限 |
| id | path | string | 是 | 抓取账号 ID，例如 account-1；不是微博 UID |

无请求体。

#### 请求示例

```http
GET /scout/weibo/accounts/account-1 HTTP/1.1
Host: <host>:9002
Authorization: Bearer <MANAGEMENT_TOKEN>
```

#### 响应参数

成功 HTTP 状态：200。

| 字段 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| success | boolean | 否 | 成功时为 true |
| code | integer | 否 | 成功时为 200 |
| msg | string | 否 | 成功时为“请求成功” |
| obj | object | 否 | 本接口业务对象 |
| obj.id | string | 否 | 抓取账号唯一 ID |
| obj.status | string | 是 | active 可选用，fail 停用；旧记录可能为 null |
| obj.failCount | integer | 否 | 累计失败次数；成功请求不清零，更新凭据清零 |
| obj.requestCount | integer(int64) | 否 | 累计选用次数 |
| obj.lastErrorAt | string | 是 | 最近失败时间；从未失败或更新凭据后为 null |
| obj.lastUsedAt | string | 是 | 最近使用时间；未使用时为 null |
| obj.recoverAt | string | 是 | 计划自动恢复时间；无自动恢复时间时为 null |
| obj.createdAt | string | 是 | 创建时间；旧记录可能为 null |
| obj.updatedAt | string | 是 | 最近更新时间；旧记录可能为 null |

#### 响应示例

```json
{
  "success": true,
  "code": 200,
  "msg": "请求成功",
  "obj": {
    "id": "account-1",
    "status": "active",
    "failCount": 0,
    "requestCount": 12,
    "lastErrorAt": null,
    "lastUsedAt": "2026-09-09 10:00:00",
    "recoverAt": null,
    "createdAt": "2026-09-09 09:00:00",
    "updatedAt": "2026-09-09 10:00:00"
  }
}
```

**调用说明：** 账号不存在返回 404：微博账号不存在。响应不包含登录凭据和错误详情。

### 3.4 更新抓取账号凭据

**接口：`PUT /scout/weibo/accounts/{id}/credentials`**

**鉴权：** 有效 Token，且包含 scout.weibo.manage 权限。

#### 请求参数

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| Authorization | header | string | 是 | Bearer &lt;TOKEN&gt;；有效 Token，且包含 scout.weibo.manage 权限 |
| Content-Type | header | string | 是 | application/json |
| id | path | string | 是 | 抓取账号 ID，例如 account-1；不是微博 UID |
| cookie | body | string | 是 | 完整微博登录 Cookie，不能为空白，必须含有效 XSRF-TOKEN；不接收单独的 xsrfToken 参数 |

#### 请求示例

```http
PUT /scout/weibo/accounts/account-1/credentials HTTP/1.1
Host: <host>:9002
Authorization: Bearer <MANAGEMENT_TOKEN>
Content-Type: application/json

{
  "cookie": "SUB=<NEW_SUB>; XSRF-TOKEN=<NEW_XSRF_TOKEN>"
}
```

#### 响应参数

成功 HTTP 状态：200。

| 字段 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| success | boolean | 否 | 成功时为 true |
| code | integer | 否 | 成功时为 200 |
| msg | string | 否 | 成功时为“请求成功” |
| obj | object | 否 | 本接口业务对象 |
| obj.id | string | 否 | 抓取账号唯一 ID |
| obj.status | string | 是 | active 可选用，fail 停用；旧记录可能为 null |
| obj.failCount | integer | 否 | 累计失败次数；成功请求不清零，更新凭据清零 |
| obj.requestCount | integer(int64) | 否 | 累计选用次数 |
| obj.lastErrorAt | string | 是 | 最近失败时间；从未失败或更新凭据后为 null |
| obj.lastUsedAt | string | 是 | 最近使用时间；未使用时为 null |
| obj.recoverAt | string | 是 | 计划自动恢复时间；无自动恢复时间时为 null |
| obj.createdAt | string | 是 | 创建时间；旧记录可能为 null |
| obj.updatedAt | string | 是 | 最近更新时间；旧记录可能为 null |

#### 响应示例

```json
{
  "success": true,
  "code": 200,
  "msg": "请求成功",
  "obj": {
    "id": "account-1",
    "status": "active",
    "failCount": 0,
    "requestCount": 12,
    "lastErrorAt": null,
    "lastUsedAt": "2026-09-09 10:00:00",
    "recoverAt": null,
    "createdAt": "2026-09-09 09:00:00",
    "updatedAt": "2026-09-09 10:00:00"
  }
}
```

**调用说明：** 使用完整新 Cookie 替换凭据，同时恢复 active、清零 failCount、清除 lastErrorAt 和 recoverAt；保留累计 requestCount 和 lastUsedAt。账号不存在为 404，Cookie 缺少有效 XSRF-TOKEN 为 400。

### 3.5 修改抓取账号状态

**接口：`PATCH /scout/weibo/accounts/{id}/status`**

**鉴权：** 有效 Token，且包含 scout.weibo.manage 权限。

#### 请求参数

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| Authorization | header | string | 是 | Bearer &lt;TOKEN&gt;；有效 Token，且包含 scout.weibo.manage 权限 |
| Content-Type | header | string | 是 | application/json |
| id | path | string | 是 | 抓取账号 ID，例如 account-1；不是微博 UID |
| status | body | string | 是 | 只允许 active 或 fail；不可为空白 |

#### 请求示例

```http
PATCH /scout/weibo/accounts/account-1/status HTTP/1.1
Host: <host>:9002
Authorization: Bearer <MANAGEMENT_TOKEN>
Content-Type: application/json

{
  "status": "fail"
}
```

手动启用时，请求体改为：

```json
{
  "status": "active"
}
```

#### 响应参数

成功 HTTP 状态：200。

| 字段 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| success | boolean | 否 | 成功时为 true |
| code | integer | 否 | 成功时为 200 |
| msg | string | 否 | 成功时为“请求成功” |
| obj | object | 否 | 本接口业务对象 |
| obj.id | string | 否 | 抓取账号唯一 ID |
| obj.status | string | 是 | active 可选用，fail 停用；旧记录可能为 null |
| obj.failCount | integer | 否 | 累计失败次数；成功请求不清零，更新凭据清零 |
| obj.requestCount | integer(int64) | 否 | 累计选用次数 |
| obj.lastErrorAt | string | 是 | 最近失败时间；从未失败或更新凭据后为 null |
| obj.lastUsedAt | string | 是 | 最近使用时间；未使用时为 null |
| obj.recoverAt | string | 是 | 计划自动恢复时间；无自动恢复时间时为 null |
| obj.createdAt | string | 是 | 创建时间；旧记录可能为 null |
| obj.updatedAt | string | 是 | 最近更新时间；旧记录可能为 null |

#### 响应示例

```json
{
  "success": true,
  "code": 200,
  "msg": "请求成功",
  "obj": {
    "id": "account-1",
    "status": "fail",
    "failCount": 0,
    "requestCount": 12,
    "lastErrorAt": null,
    "lastUsedAt": "2026-09-09 10:00:00",
    "recoverAt": null,
    "createdAt": "2026-09-09 09:00:00",
    "updatedAt": "2026-09-09 10:00:00"
  }
}
```

**调用说明：** 设置状态并清除 recoverAt；不会清零累计失败数，也不替换 Cookie。账号不存在为 404，非法状态为 400。已失效的登录凭据应通过更新凭据接口修复，仅改 active 不能修复 Cookie。

### 3.6 删除抓取账号

**接口：`DELETE /scout/weibo/accounts/{id}`**

**鉴权：** 有效 Token，且包含 scout.weibo.manage 权限。

#### 请求参数

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| Authorization | header | string | 是 | Bearer &lt;TOKEN&gt;；有效 Token，且包含 scout.weibo.manage 权限 |
| id | path | string | 是 | 抓取账号 ID，例如 account-1；不是微博 UID |

无请求体。

#### 请求示例

```http
DELETE /scout/weibo/accounts/account-1 HTTP/1.1
Host: <host>:9002
Authorization: Bearer <MANAGEMENT_TOKEN>
```

#### 响应参数

成功 HTTP 状态：200。

| 字段 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| success | boolean | 否 | 成功时为 true |
| code | integer | 否 | 成功时为 200 |
| msg | string | 否 | 成功时为“删除成功” |
| obj | null | 是 | 无返回数据，固定 null |

#### 响应示例

```json
{
  "success": true,
  "code": 200,
  "msg": "删除成功",
  "obj": null
}
```

**调用说明：** 删除指定抓取账号。账号不存在（包括重复删除）返回 404：微博账号不存在。不会取消任何博主订阅。

## 4. 成本、代理与黄历 HTTP 接口

### 4.1 查询单个技艺制品成本

**接口：`POST /costing/one`**

**鉴权：** 无需 Token。

#### 请求参数

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| Content-Type | header | string | 是 | application/json |
| formulaName | body | string | 是 | 技艺制品名称，不能为 null 或空白 |
| number | body | integer | 是 | 需求数量，不能为 null，最小 1 |
| server | body | string / null | 否 | null 或空字符串使用服务端默认服务器；仅空白字符串不会被视为缺省 |
| rangeCreate | body | boolean / null | 否 | 是否按随机产出数量计算；省略或 null 默认为 true |

#### 请求示例

```http
POST /costing/one HTTP/1.1
Host: <host>:9002
Content-Type: application/json

{
  "formulaName": "示例配方",
  "number": 2,
  "server": "剑胆琴心",
  "rangeCreate": true
}
```

#### 响应参数

成功 HTTP 状态：200。

| 字段 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| success | boolean | 否 | 成功时为 true |
| code | integer | 否 | 成功时为 200 |
| msg | string | 否 | 成功时为“请求成功” |
| obj | object | 否 | 本接口业务对象 |
| obj.server | string | 是 | 服务器名称 |
| obj.energies | integer | 是 | 预计总精力 |
| obj.cost | integer(int64) | 是 | 材料总成本原始数值 |
| obj.costString | string | 是 | 格式化总成本 |
| obj.value | integer(int64) | 是 | 交易行总价原始数值 |
| obj.valueString | string | 是 | 格式化交易行总价 |
| obj.actualProfit | integer(int64) | 是 | 扣除费用后的实际利润 |
| obj.actualProfitString | string | 是 | 格式化实际利润 |
| obj.requiredMap | object | 是 | 所需材料映射，键由材料计算结果生成 |
| obj.requiredMap.&lt;key&gt; | object | 是 | 单种材料信息 |
| obj.requiredMap.&lt;key&gt;.id | string | 是 | 材料 ID |
| obj.requiredMap.&lt;key&gt;.name | string | 是 | 材料名称 |
| obj.requiredMap.&lt;key&gt;.number | integer | 是 | 材料总需求数量 |
| obj.requiredMap.&lt;key&gt;.value | integer(int64) | 是 | 该材料总价原始数值 |
| obj.requiredMap.&lt;key&gt;.valueString | string | 是 | 该材料格式化总价 |
| obj.makeDetail | object[] | 是 | 制作批次明细；当前计算路径未填充，示例返回 null |
| obj.makeDetail[].no | integer | 是 | 制作批次序号 |
| obj.makeDetail[].name | string | 是 | 制作物品名称 |
| obj.makeDetail[].makeNumber | integer | 是 | 本批产出数量 |
| obj.type | string | 是 | COOKING / TAILORING / MEDICINE / FOUNDING / FURNITURE |
| obj.formulaName | string | 是 | 技艺制品名称 |
| obj.materialId | string | 是 | 成品材料 ID |
| obj.number | integer | 是 | 请求需求数量 |
| obj.actualNumber | integer | 是 | 实际产出数量 |

#### 响应示例

```json
{
  "success": true,
  "code": 200,
  "msg": "请求成功",
  "obj": {
    "server": "剑胆琴心",
    "energies": 20,
    "cost": 12500,
    "costString": "1金25银",
    "value": 30000,
    "valueString": "3金",
    "actualProfit": 8000,
    "actualProfitString": "80银",
    "requiredMap": {
      "示例材料": {
        "id": "10001",
        "name": "示例材料",
        "number": 4,
        "value": 12500,
        "valueString": "1金25银"
      }
    },
    "makeDetail": null,
    "type": "COOKING",
    "formulaName": "示例配方",
    "materialId": "20001",
    "number": 2,
    "actualNumber": 2
  }
}
```

**调用说明：** 示例按 5% 手续费及 8000 铜保管费计算，实际金额取决于服务配置和市场报价。当前响应未填充 makeDetail；所有金额和名称按本次计算结果返回。部分价格查询失败可能体现为 0，0 不能视为上游价格已核实。

### 4.2 查询多个技艺制品成本

**接口：`POST /costing/list`**

**鉴权：** 无需 Token。

#### 请求参数

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| Content-Type | header | string | 是 | application/json |
| server | body | string / null | 否 | null 或空字符串使用服务端默认服务器；仅空白字符串不会被视为缺省 |
| rangeCreate | body | boolean / null | 否 | 是否按随机产出数量计算；省略或 null 默认为 true |
| items | body | object[] | 是 | 清单明细；当前只校验非 null，空数组未被拒绝 |
| items[].formulaName | body | string | 是 | 配方名称，不应为空白 |
| items[].number | body | integer | 是 | 需求数量，应为至少 1 的整数 |

#### 请求示例

```http
POST /costing/list HTTP/1.1
Host: <host>:9002
Content-Type: application/json

{
  "server": "剑胆琴心",
  "rangeCreate": true,
  "items": [
    {
      "formulaName": "示例配方",
      "number": 2
    }
  ]
}
```

#### 响应参数

成功 HTTP 状态：200。

| 字段 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| success | boolean | 否 | 成功时为 true |
| code | integer | 否 | 成功时为 200 |
| msg | string | 否 | 成功时为“请求成功” |
| obj | object | 否 | 本接口业务对象 |
| obj.server | string | 是 | 服务器名称 |
| obj.energies | integer | 是 | 预计总精力 |
| obj.cost | integer(int64) | 是 | 材料总成本原始数值 |
| obj.costString | string | 是 | 格式化总成本 |
| obj.value | integer(int64) | 是 | 交易行总价原始数值 |
| obj.valueString | string | 是 | 格式化交易行总价 |
| obj.actualProfit | integer(int64) | 是 | 扣除费用后的实际利润 |
| obj.actualProfitString | string | 是 | 格式化实际利润 |
| obj.requiredMap | object | 是 | 所需材料映射，键由材料计算结果生成 |
| obj.requiredMap.&lt;key&gt; | object | 是 | 单种材料信息 |
| obj.requiredMap.&lt;key&gt;.id | string | 是 | 材料 ID |
| obj.requiredMap.&lt;key&gt;.name | string | 是 | 材料名称 |
| obj.requiredMap.&lt;key&gt;.number | integer | 是 | 材料总需求数量 |
| obj.requiredMap.&lt;key&gt;.value | integer(int64) | 是 | 该材料总价原始数值 |
| obj.requiredMap.&lt;key&gt;.valueString | string | 是 | 该材料格式化总价 |
| obj.makeDetail | object[] | 是 | 制作批次明细；当前计算路径未填充，示例返回 null |
| obj.makeDetail[].no | integer | 是 | 制作批次序号 |
| obj.makeDetail[].name | string | 是 | 制作物品名称 |
| obj.makeDetail[].makeNumber | integer | 是 | 本批产出数量 |
| obj.formulas | object | 是 | 按请求 formulaName 为键的成品映射 |
| obj.formulas.&lt;name&gt; | object | 是 | 单个成品结果 |
| obj.formulas.&lt;name&gt;.type | string | 是 | COOKING / TAILORING / MEDICINE / FOUNDING / FURNITURE |
| obj.formulas.&lt;name&gt;.formulaName | string | 是 | 技艺制品名称 |
| obj.formulas.&lt;name&gt;.materialId | string | 是 | 成品材料 ID |
| obj.formulas.&lt;name&gt;.number | integer | 是 | 请求需求数量 |
| obj.formulas.&lt;name&gt;.actualNumber | integer | 是 | 实际产出数量 |

#### 响应示例

```json
{
  "success": true,
  "code": 200,
  "msg": "请求成功",
  "obj": {
    "server": "剑胆琴心",
    "energies": 20,
    "cost": 12500,
    "costString": "1金25银",
    "value": 30000,
    "valueString": "3金",
    "actualProfit": 8000,
    "actualProfitString": "80银",
    "requiredMap": {
      "示例材料": {
        "id": "10001",
        "name": "示例材料",
        "number": 4,
        "value": 12500,
        "valueString": "1金25银"
      }
    },
    "makeDetail": null,
    "formulas": {
      "示例配方": {
        "type": "COOKING",
        "formulaName": "示例配方",
        "materialId": "20001",
        "number": 2,
        "actualNumber": 2
      }
    }
  }
}
```

**调用说明：** items 的元素目前未启用级联校验，调用方应保证子项参数合法。同名配方键以后处理的结果为准。makeDetail 当前为 null；示例按 5% 手续费及 8000 铜保管费计算。

### 4.3 JX3API 代理

**接口：`POST /proxy/jx3api`**

**鉴权：** 无需 Token。

#### 请求参数

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| Content-Type | header | string | 是 | application/json |
| url | body | string | 是 | 非空白上游相对路径，通常以 /data 开头；服务端直接拼接上游基础地址 |
| params | body | object | 是 | 上游请求参数对象；无参数时传 {}，不能为 null |
| params.&lt;key&gt; | body | 任意 JSON 值 | 由上游接口决定 | 具体参数与选定上游接口一致；ticket/token 由服务端覆盖或补充 |

#### 请求示例

```http
POST /proxy/jx3api HTTP/1.1
Host: <host>:9002
Content-Type: application/json

{
  "url": "/data/server/status",
  "params": {
    "server": "剑胆琴心"
  }
}
```

#### 响应参数

成功 HTTP 状态：200。

| 字段 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| success | boolean | 否 | 成功时为 true |
| code | integer | 否 | 成功时为 200 |
| msg | string | 否 | 成功时为“代理请求成功” |
| obj | object / null | 是 | 上游业务数据，可能为 null |
| obj.&lt;key&gt; | 任意 JSON 值 | 由上游决定 | obj 为上游完整 JSON 对象；内部字段不存在统一固定模型 |

#### 响应示例

```json
{
  "success": true,
  "code": 200,
  "msg": "代理请求成功",
  "obj": {
    "code": 200,
    "msg": "示例上游结果",
    "data": {}
  }
}
```

**调用说明：** 响应示例中 obj 内部的 code/msg/data 仅为一种上游结构示意，不保证所有上游接口包含这些字段。外层 code=200 表示代理调用返回，仍需按所选上游协议判断内部业务成功与否。

### 4.4 上传并更新黄历图片

**接口：`POST /thire/huangli/update`**

**鉴权：** 无需 Token。

#### 请求参数

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| Content-Type | header | string | 是 | multipart/form-data; boundary=&lt;BOUNDARY&gt;，由上传客户端生成 |
| file | multipart/form-data | file | 是 | 非空图片；文件 Part 的 Content-Type 必须以 image/ 开头，内容签名须为 PNG / JPEG / GIF / WebP |
| date | multipart/form-data | string | 否 | yyyy-MM-dd；省略时使用服务端当前日期 |

#### 请求示例

```http
POST /thire/huangli/update HTTP/1.1
Host: <host>:9002
Content-Type: multipart/form-data; boundary=ExampleBoundary

--ExampleBoundary
Content-Disposition: form-data; name="date"

2026-09-09
--ExampleBoundary
Content-Disposition: form-data; name="file"; filename="huangli.png"
Content-Type: image/png

<PNG 文件二进制内容>
--ExampleBoundary--
```

#### 响应参数

成功 HTTP 状态：200。

| 字段 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| success | boolean | 否 | 成功时为 true |
| code | integer | 否 | 成功时为 200 |
| msg | string | 否 | 成功时为“请求成功” |
| obj | object | 否 | 本接口业务对象 |
| obj.date | string | 否 | 实际保存的黄历日期，yyyy-MM-dd |
| obj.url | string | 否 | 可访问的图片地址 |

#### 响应示例

```json
{
  "success": true,
  "code": 200,
  "msg": "请求成功",
  "obj": {
    "date": "2026-09-09",
    "url": "https://cdn.example.com/huangli/2026-09-09.png"
  }
}
```

**调用说明：** 上传示例中的二进制占位符须替换成真实文件内容；使用 SDK/form-data 工具时由工具生成 boundary。成功保存后向在线 WS 连接发送 huangli.updated。

## 5. WebSocket

### 5.1 建立连接

**接口：`GET /ws`，通过 WebSocket 升级握手连接。**

#### 请求参数

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| token | query | string | 与 Authorization 至少一项 | 有效 Token；有非空 query token 时优先使用，连接地址中的 Token 需 URL 编码 |
| Authorization | header | string | 与 token 至少一项 | Bearer &lt;TOKEN&gt;；仅 query token 缺失或空白时使用 |
| Host | header | string | 是 | 服务地址，由客户端设置 |
| Upgrade | header | string | 是 | websocket，由 WebSocket 客户端设置 |
| Connection | header | string | 是 | Upgrade，由 WebSocket 客户端设置 |
| Sec-WebSocket-Key | header | string | 是 | 客户端生成的握手随机值 |
| Sec-WebSocket-Version | header | string | 是 | 13，由 WebSocket 客户端设置 |

#### 请求示例

使用 URL Token：

```http
GET /ws?token=<TOKEN> HTTP/1.1
Host: <host>:9002
Upgrade: websocket
Connection: Upgrade
Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==
Sec-WebSocket-Version: 13
```

使用请求头 Token：

```http
GET /ws HTTP/1.1
Host: <host>:9002
Authorization: Bearer <TOKEN>
Upgrade: websocket
Connection: Upgrade
Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==
Sec-WebSocket-Version: 13
```

客户端库会自动处理 Upgrade 等协议头；业务侧只需设置地址和 Token。浏览器原生 WebSocket 不能自定义 Authorization 时使用 URL Token。无效 Token 拒绝握手，HTTP 状态为 401，不会收到 connection.success。

#### 响应参数

| 项目 | 位置 | 说明 |
| --- | --- | --- |
| HTTP 101 | status | 握手成功，后续消息为 WS 文本帧，不使用 HTTP 的 success/code/msg/obj 封装 |
| Upgrade | header | websocket |
| Connection | header | Upgrade |
| Sec-WebSocket-Accept | header | 根据请求 Sec-WebSocket-Key 计算的握手校验值 |
| connection.success | 首个文本帧 | 连接成功消息，字段见 5.3 |

#### 响应示例

```http
HTTP/1.1 101 Switching Protocols
Upgrade: websocket
Connection: Upgrade
Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=
```

随后收到：

```json
{
  "type": "connection.success",
  "message": "连接成功"
}
```

### 5.2 消息类型与接收规则

| type | 接收者 | 字段与示例 |
| --- | --- | --- |
| connection.success | 新连接自身 | 5.3 |
| huangli.updated | 全部在线连接 | 5.4 |
| weibo.updated | 当前 Token 有效且订阅对应 UID 的在线连接 | 5.5 |
| weibo.account.invalid | 当前仍含 scout.weibo.manage 权限的在线连接 | 5.6 |
| weibo.account.recovery_failed | 当前仍含 scout.weibo.manage 权限的在线连接 | 5.6 |
| weibo.pool.unavailable | 当前仍含 scout.weibo.manage 权限的在线连接 | 5.6 |
| jx3.news.updated | 全部在线连接 | 5.7 |
| jx3.maintenance.updated | 全部在线连接 | 5.8 |
| jx3.server.changed | 全部在线连接 | 5.9 |
| jx3.patch.updated | 全部在线连接 | 5.10 |

以下均为服务端主动消息，无对应的客户端请求参数。客户端无需发送订阅帧，微博订阅通过 HTTP 接口维护。订阅变更无需重连，影响后续推文分发；同一 Token 的多个在线连接均可收到消息。

WebSocket 推送只覆盖当前服务实例在线连接。微博管理告警另有持久化补拉和按管理 Token 的接收确认接口，支持重连、跨服务实例读取；其他事件不因此获得离线补发或客户端确认能力。微博首次监听只建立基线，服务重启不补发历史推文，同一推文 ID 不重复推送；剑三首次采集只建立基线，不补发停机期间变化。

剑三新闻和公告默认每轮完成后间隔 30 秒，区服探测间隔 10 秒，补丁间隔 30 秒；实际周期还包含本轮执行耗时。采集任务统一交给应用异步执行器，调度器只触发任务；同一来源不会重叠执行。区服按网关探测完成顺序处理，仍需连续两次明确结果确认变化。

### 5.3 连接成功（connection.success）

#### 消息参数

| 字段 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| type | string | 否 | 固定 connection.success |
| message | string | 否 | 固定“连接成功” |

#### 消息示例

```json
{
  "type": "connection.success",
  "message": "连接成功"
}
```

### 5.4 黄历更新（huangli.updated）

#### 消息参数

| 字段 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| type | string | 否 | 固定 huangli.updated |
| date | string | 否 | 黄历日期，yyyy-MM-dd |
| url | string | 否 | 黄历图片地址 |

#### 消息示例

```json
{
  "type": "huangli.updated",
  "date": "2026-09-09",
  "url": "https://cdn.example.com/huangli/2026-09-09.png"
}
```

### 5.5 微博推文更新（weibo.updated）

#### 消息参数

| 字段 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| type | string | 否 | 固定 weibo.updated |
| uid | string | 否 | 微博 UID |
| screenName | string | 是 | 博主名称，可能为空字符串 |
| weiboId | string | 否 | 微博 ID |
| publishedAt | string | 是 | 发布时间，北京时间 yyyy-MM-dd HH:mm:ss；旧记录可能缺失 |
| content | string | 是 | 纯文本正文，保留段落、换行、话题及 @ 提及 |
| rawContent | string | 是 | 上游原始 HTML；渲染前须清理不可信 HTML |
| source | string | 是 | 发布来源 |
| regionName | string | 是 | 发布地区 |
| repostsCount | integer(int64) | 是 | 转发数，null 不代表 0 |
| commentsCount | integer(int64) | 是 | 评论数，null 不代表 0 |
| attitudesCount | integer(int64) | 是 | 点赞数，null 不代表 0 |
| url | string | 是 | 推文详情地址 |
| images | string[] | 是 | 正文图片及卡片、视频封面；新记录无图片为 [] |
| topics | string[] | 是 | 话题名称；新记录无话题为 [] |
| videoCoverImages | string[] | 是 | 卡片和视频封面；新记录无封面为 [] |
| retweet | object | 是 | 转发原文，非转发为 null |
| media | object[] | 是 | 媒体资源；新记录无媒体为 [] |
| links | object[] | 是 | 正文链接、用户提及、话题和特殊内容卡片；新记录无内容为 [] |
| article | object | 是 | 头条文章；无文章为 null |
| truncated | boolean | 否 | 上游为长文但未取得完整正文时为 true |
| media[].type | string | 否 | image 图片、video 视频、livephoto 动态照片 |
| media[].url | string | 否 | 资源地址，保留签名参数；livephoto 为视频地址 |
| media[].coverUrl | string | 是 | 封面地址；livephoto 为静态图片地址 |
| links[].type | string | 是 | 保留上游链接或卡片类型，不限定为固定枚举 |
| links[].title | string | 是 | 标题 |
| links[].url | string | 是 | 目标链接 |
| links[].description | string | 是 | 摘要 |
| links[].image | string | 是 | 卡片图片 |
| article.title | string | 是 | 文章标题 |
| article.url | string | 是 | 文章链接 |
| article.summary | string | 是 | 卡片摘要 |
| article.content | string | 是 | 全文纯文本；上游未提供时为 null |
| article.rawContent | string | 是 | 全文原始 HTML，使用前须清理 |
| article.publishedAt | string | 是 | 文章发布时间 |
| article.paid | boolean | 是 | 上游付费标志，未知为 null |
| article.trial | boolean | 是 | 上游试读标志，未知为 null |
| retweet.uid | string | 是 | 微博 UID |
| retweet.screenName | string | 是 | 博主名称，可能为空字符串 |
| retweet.weiboId | string | 是 | 微博 ID |
| retweet.publishedAt | string | 是 | 发布时间，北京时间 yyyy-MM-dd HH:mm:ss；旧记录可能缺失 |
| retweet.content | string | 是 | 纯文本正文，保留段落、换行、话题及 @ 提及 |
| retweet.rawContent | string | 是 | 上游原始 HTML；渲染前须清理不可信 HTML |
| retweet.source | string | 是 | 发布来源 |
| retweet.regionName | string | 是 | 发布地区 |
| retweet.repostsCount | integer(int64) | 是 | 转发数，null 不代表 0 |
| retweet.commentsCount | integer(int64) | 是 | 评论数，null 不代表 0 |
| retweet.attitudesCount | integer(int64) | 是 | 点赞数，null 不代表 0 |
| retweet.url | string | 是 | 推文详情地址 |
| retweet.images | string[] | 是 | 正文图片及卡片、视频封面；新记录无图片为 [] |
| retweet.topics | string[] | 是 | 话题名称；新记录无话题为 [] |
| retweet.videoCoverImages | string[] | 是 | 卡片和视频封面；新记录无封面为 [] |
| retweet.retweet | object | 是 | 转发原文，非转发为 null |
| retweet.media | object[] | 是 | 媒体资源；新记录无媒体为 [] |
| retweet.links | object[] | 是 | 正文链接、用户提及、话题和特殊内容卡片；新记录无内容为 [] |
| retweet.article | object | 是 | 头条文章；无文章为 null |
| retweet.truncated | boolean | 否 | 上游为长文但未取得完整正文时为 true |
| retweet.unavailable | boolean | 否 | 原文被删除或原作者不可访问时为 true |
| retweet.media[].type | string | 否 | image 图片、video 视频、livephoto 动态照片 |
| retweet.media[].url | string | 否 | 资源地址，保留签名参数；livephoto 为视频地址 |
| retweet.media[].coverUrl | string | 是 | 封面地址；livephoto 为静态图片地址 |
| retweet.links[].type | string | 是 | 保留上游链接或卡片类型，不限定为固定枚举 |
| retweet.links[].title | string | 是 | 标题 |
| retweet.links[].url | string | 是 | 目标链接 |
| retweet.links[].description | string | 是 | 摘要 |
| retweet.links[].image | string | 是 | 卡片图片 |
| retweet.article.title | string | 是 | 文章标题 |
| retweet.article.url | string | 是 | 文章链接 |
| retweet.article.summary | string | 是 | 卡片摘要 |
| retweet.article.content | string | 是 | 全文纯文本；上游未提供时为 null |
| retweet.article.rawContent | string | 是 | 全文原始 HTML，使用前须清理 |
| retweet.article.publishedAt | string | 是 | 文章发布时间 |
| retweet.article.paid | boolean | 是 | 上游付费标志，未知为 null |
| retweet.article.trial | boolean | 是 | 上游试读标志，未知为 null |

#### 消息示例

```json
{
  "type": "weibo.updated",
  "uid": "1761587065",
  "screenName": "剑网3",
  "weiboId": "5140000000000000",
  "publishedAt": "2026-09-09 10:00:00",
  "content": "微博正文 #剑网3#",
  "rawContent": "<p>微博正文 #剑网3#</p>",
  "source": "微博网页版",
  "regionName": null,
  "repostsCount": 12,
  "commentsCount": 34,
  "attitudesCount": 56,
  "url": "https://m.weibo.cn/detail/5140000000000000",
  "images": [
    "https://example.com/main.jpg"
  ],
  "topics": [
    "剑网3"
  ],
  "videoCoverImages": [],
  "retweet": {
    "uid": "1234567890",
    "screenName": "原作者",
    "weiboId": "5139999999999999",
    "publishedAt": "2026-09-09 09:50:00",
    "content": "原微博正文",
    "rawContent": "<p>原微博正文</p>",
    "source": "微博网页版",
    "regionName": null,
    "repostsCount": 12,
    "commentsCount": 34,
    "attitudesCount": 56,
    "url": "https://m.weibo.cn/detail/5139999999999999",
    "images": [],
    "topics": [],
    "videoCoverImages": [],
    "retweet": null,
    "media": [],
    "links": [],
    "article": null,
    "truncated": false,
    "unavailable": false
  },
  "media": [
    {
      "type": "image",
      "url": "https://example.com/main.jpg",
      "coverUrl": null
    }
  ],
  "links": [
    {
      "type": "article",
      "title": "示例文章",
      "url": "https://example.com/article",
      "description": "文章摘要",
      "image": null
    }
  ],
  "article": {
    "title": "示例文章",
    "url": "https://example.com/article",
    "summary": "文章摘要",
    "content": null,
    "rawContent": null,
    "publishedAt": null,
    "paid": null,
    "trial": null
  },
  "truncated": false
}
```

**说明：** 管理 Token 同样必须订阅该 UID 才能接收。content 为纯文本，rawContent 为不可信上游 HTML；不要直接执行其中脚本。资源 URL 保留签名参数。retweet.retweet 递归使用相同转发结构，最多五层；原文不可访问时 unavailable=true，身份或内容字段可能为空。

### 5.6 微博管理告警与可靠补拉

三类事件共用脱敏快照。`weibo.account.invalid` 保留原事件类型；新增 `weibo.account.recovery_failed` 和 `weibo.pool.unavailable`。以下字段位于 WS 顶层，HTTP 响应位于 `R.obj` 顶层。

| 字段 | 说明 |
| --- | --- |
| eventId / incidentId | 稳定事件 ID / 故障或恢复轮次 ID；重投不改变 |
| type / level / message | 事件类型、warning、脱敏说明 |
| occurredAt | 首次记录故障的北京时间，yyyy-MM-dd HH:mm:ss |
| accountId | 账号告警为账号 ID；账号池告警为 null |
| status / recoverAt / failCount | 发生时的账号状态、恢复时间及失败次数 |
| totalCount / waitingCount / stoppedCount | 账号池总数、等待恢复或验证数、停用数；账号级事件为 0 |
| earliestRecoverAt | 账号池最早可重试时间；没有时为 null |
| reason | 脱敏原因：first_detection、no_usable_accounts、credentials_invalid 或异常类名 |
| successGeneration | 发生时成功代数，用于区分同一秒内的成功与后续故障；客户端可忽略 |
| currentState / resolvedAt | 仅 HTTP：unavailable、pending_verification、recovered、deleted；最近确认成功时间可空 |

**触发规则：** 普通失败继续使用累计失败 1 小时、3 小时退避，第 3 次不再自动恢复。仅实际选中的到期账号进入恢复轮次，其首次有效请求再次失败立即预警；明确登录失效仍立即停用，恢复期间失效合并成恢复失败告警。全部账号暂时不可用也开启账号池故障；同次中断只告警一次，真实请求成功后才结束。首次运行发现存量全停用账号会生成一次 first_detection 告警，不倒推历史恢复失败。Cookie 保存错误不计入账号请求失败。同一账号的完整抓取链串行占用，租约最长 10 分钟；进程中断后到期可重新选取，迟到结果不能覆盖新版本。

**`GET /scout/weibo/alerts?limit=100`**：读取当前管理 Token 尚未确认的事件，`limit` 为 1–100，默认 100，`obj` 为数组，按发生时间和事件 ID 排序。客户端每批先持久化再逐条 ACK，下一轮继续读取剩余事件；没有事件返回空数组。

**`GET /scout/weibo/alerts/{id}`**：返回完整事件及当前恢复状态。恢复状态按成功代数判定，不将时间到期或人工更新凭据当作请求成功。无法取得可靠状态时返回错误，客户端不得伪造已恢复。

**`POST /scout/weibo/alerts/{id}/ack`**：幂等确认当前 Token 已持久化接收该事件，返回 `R.success`。身份来自现有鉴权，不能替其他 Token 确认；确认身份以摘要保存，不保存令牌明文。不存在的事件返回 404，未授权和非管理 Token 仍分别返回 401/403。

所有告警先与状态变更原子保存，再幂等转存至告警日志；未完成转存每 60 秒重试。WebSocket 仅负责实时通知，HTTP 接收确认与平台私聊回执分开，未确认事件不会因 WS 写入成功消失。删除账号前必须完成待保存告警和恢复标记转存，否则返回 409；管理和请求并发导致状态版本变化时，管理更新返回 409。

机器人先持久化接收再 ACK，独立保存每个 Bot/超管的投递进度；启动、重连和每 60 秒补拉，私聊失败、离线或未建立 QQ 私聊路由时保留待发。QQ 路由只由通过现有权限校验的私聊管理命令登记，重启后可恢复。发送前再次核对超管权限并读取当前故障状态；已恢复的历史事件仍补发并注明已恢复，更新凭据或到期可重试显示待请求验证。平台已接收但本地回执尚未保存便崩溃时可能重复，语义为至少一次投递。

**兼容上线：** 先部署机器人消费者，再部署服务端。旧 `weibo.account.invalid` 消息仍能消费；新接口尚不存在时机器人记录版本不匹配，继续处理已有本地告警。旧账号缺少状态版本和成功代数时按初始状态读取，不要求批量改写；本次不回填历史恢复失败事件、不自动清除未确认告警。

### 5.7 剑三新闻（jx3.news.updated）

#### 消息参数

| 字段 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| type | string | 否 | 固定 jx3.news.updated |
| eventId | string | 否 | 服务端生成的事件 ID，可用于去重 |
| occurredAt | string | 否 | 首次观察到变化的北京时间，不是官方发布时间 |
| message | string | 否 | 可直接展示的纯文本，可能包含换行 |
| data | object | 否 | 此类事件的业务载荷 |
| data.articleId | string | 否 | 官方文章 ID |
| data.categoryId | string | 否 | 新闻分类 ID，上游缺失时可能为空字符串 |
| data.changeType | string | 否 | created 新增 / updated 内容更新 |
| data.title | string | 否 | 文章标题 |
| data.summary | string | 是 | 摘要，可能为 null；最多前 300 个 Unicode 码点后追加省略号 |
| data.url | string | 否 | 官方原文链接 |
| data.publishedAt | string | 否 | 官方有效发布时间，北京时间 yyyy-MM-dd HH:mm:ss |
| data.updatedAt | string | 是 | 官方更新时间，未提供时为 null |

#### 消息示例

```json
{
  "type": "jx3.news.updated",
  "eventId": "b81066e1-0fcf-48a9-86ca-3e14c7081204",
  "occurredAt": "2026-09-09 10:00:00",
  "message": "[10:00:00]剑网3新闻发布\n示例新闻\nhttps://jx3.xoyo.com/example",
  "data": {
    "articleId": "10001",
    "categoryId": "2458",
    "changeType": "created",
    "title": "示例新闻",
    "summary": null,
    "url": "https://jx3.xoyo.com/example",
    "publishedAt": "2026-09-09 09:59:00",
    "updatedAt": null
  }
}
```

**说明：** 官网 `catid=2458` 的新闻和 `catid=2461` 的活动均使用此类型，按官方文章 ID 去重。活动链接记录保留官方摘要和原文 URL，不要求正文非空。WS 发送摘要，不包含完整新闻正文 content。

### 5.8 剑三官方公告（jx3.maintenance.updated）

#### 消息参数

| 字段 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| type | string | 否 | 固定 jx3.maintenance.updated |
| eventId | string | 否 | 服务端生成的事件 ID，可用于去重 |
| occurredAt | string | 否 | 首次观察到变化的北京时间，不是官方发布时间 |
| message | string | 否 | 可直接展示的纯文本，可能包含换行 |
| data | object | 否 | 此类事件的业务载荷 |
| data.articleId | string | 否 | 官方文章 ID |
| data.changeType | string | 否 | created 新增 / updated 内容更新 |
| data.title | string | 否 | 文章标题 |
| data.summary | string | 是 | 摘要，可能为 null；最多前 300 个 Unicode 码点后追加省略号 |
| data.url | string | 否 | 官方原文链接 |
| data.publishedAt | string | 否 | 官方有效发布时间，北京时间 yyyy-MM-dd HH:mm:ss |
| data.updatedAt | string | 是 | 官方更新时间，未提供时为 null |
| data.maintenanceStatus | string | 否 | scheduled 计划维护 / delayed 延期 / cancelled 取消 / completed 完成 / unknown 无法判断 |
| data.startsAt | string | 是 | 计划开始时间；无法明确解析为 null |
| data.expectedEndsAt | string | 是 | 预计结束时间；无法明确解析为 null |

#### 消息示例

```json
{
  "type": "jx3.maintenance.updated",
  "eventId": "8955720d-b459-4708-8ca7-c36fbb0a489a",
  "occurredAt": "2026-09-09 20:01:00",
  "message": "[20:01:00]剑网3公告发布\n例行维护公告\nhttps://kefu.xoyo.com/example",
  "data": {
    "articleId": "10002",
    "changeType": "created",
    "title": "例行维护公告",
    "summary": null,
    "url": "https://kefu.xoyo.com/example",
    "publishedAt": "2026-09-09 20:00:00",
    "updatedAt": null,
    "maintenanceStatus": "scheduled",
    "startsAt": "2026-09-10 06:30:00",
    "expectedEndsAt": "2026-09-10 12:00:00"
  }
}
```

**说明：** 此类型覆盖官网 `catid=0` 的所有公告，包括版本更新、维护、处罚等；保留原事件类型和字段以兼容客户端，不再按标题关键词筛选公告。此消息没有 categoryId；普通公告的 maintenanceStatus 为 unknown，startsAt 和 expectedEndsAt 为 null。只有明确的正式服维护通知解析维护状态和时间，不会根据预计结束时间自动宣布开服。

### 5.9 剑三区服状态变化（jx3.server.changed）

#### 消息参数

| 字段 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| type | string | 否 | 固定 jx3.server.changed |
| eventId | string | 否 | 服务端生成的事件 ID，可用于去重 |
| occurredAt | string | 否 | 首次观察到变化的北京时间，不是官方发布时间 |
| message | string | 否 | 可直接展示的纯文本，可能包含换行 |
| data | object | 否 | 此类事件的业务载荷 |
| data.zoneId | string | 否 | 大区 ID |
| data.zoneName | string | 否 | 大区名称 |
| data.serverName | string | 否 | 服务器名称 |
| data.aliases | string[] | 否 | 同组其他服务器名称，无别名为 [] |
| data.previousStatus | string | 否 | 变化前状态：reachable / unreachable |
| data.status | string | 否 | 变化后状态：reachable / unreachable |
| data.detectedBy | string | 否 | 固定 tcp |
| data.confirmedAt | string | 否 | 满足确认条件的北京时间，可能晚于 occurredAt |

#### 消息示例

```json
{
  "type": "jx3.server.changed",
  "eventId": "a3b6f308-93a5-4958-b87f-f8e34bd8c360",
  "occurredAt": "2026-09-09 10:36:20",
  "message": "[10:36:20]梦江南开服啦！",
  "data": {
    "zoneId": "z05",
    "zoneName": "电信区",
    "serverName": "梦江南",
    "aliases": [
      "枫泾古镇"
    ],
    "previousStatus": "unreachable",
    "status": "reachable",
    "detectedBy": "tcp",
    "confirmedAt": "2026-09-09 10:36:50"
  }
}
```

**说明：** TCP 可达不保证玩家一定能进入游戏；超时不会直接作为关服消息。不可达文案为“[HH:mm:ss]服务器名暂时无法连接，可能维护中。”。

### 5.10 剑三更新包（jx3.patch.updated）

#### 消息参数

| 字段 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| type | string | 否 | 固定 jx3.patch.updated |
| eventId | string | 否 | 服务端生成的事件 ID，可用于去重 |
| occurredAt | string | 否 | 首次观察到变化的北京时间，不是官方发布时间 |
| message | string | 否 | 可直接展示的纯文本，可能包含换行 |
| data | object | 否 | 此类事件的业务载荷 |
| data.previousVersion | string | 否 | 变化前客户端版本 |
| data.version | string | 否 | 目标客户端版本 |
| data.packageCount | integer | 否 | 升级链中的更新包数量 |
| data.totalBytes | integer(int64) | 否 | 这些更新包的总字节数 |

#### 消息示例

```json
{
  "type": "jx3.patch.updated",
  "eventId": "fe058880-a902-4c9b-8058-fa6968dbe682",
  "occurredAt": "2026-09-09 16:46:20",
  "message": "[16:46:20]西山居又偷偷更新了！\n版本 1.5.0.9930->1.5.0.9932\n共2个更新包，总计4.01 MB",
  "data": {
    "previousVersion": "1.5.0.9930",
    "version": "1.5.0.9932",
    "packageCount": 2,
    "totalBytes": 4206627
  }
}
```

**说明：** 展示大小以 totalBytes / 1024² 计算、保留两位小数并标注 MB；精确大小以 totalBytes 为准。
