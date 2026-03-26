# 黑马点评 AI 改造说明（面试版）

## 1. 改造目标

本次改造在不升级原有主项目技术栈（`Spring Boot 2.3 + Java8`）的前提下，新增了 AI 能力：

1. 店铺探店博客智能总结（把“评论”定义为店铺探店博客 `tb_blog`）。
2. 点评 AI 助手（5km 推荐）。
3. Redis 预热与分组总结机制，避免每次都从头调用大模型。

备注：`tb_blog_comments` 仍未启用，本次不接入评论表。

---

## 2. 方案选型（Sidecar）

采用 **方案 A：主项目 + 独立 AI 子服务**。

- 主项目：继续负责业务检索（MySQL + Redis GEO）和缓存治理。
- AI 子服务：负责意图理解、文本总结、推荐理由生成。

这样做的核心收益：

1. 不破坏原有线上稳定性（无需一次性升级主项目到 Boot3/Java17）。
2. AI 依赖和成本策略可独立演进。
3. 主服务与模型供应商解耦，后续可切模型。

---

## 3. 主要改动文件

### 3.1 主项目（`dianping-nginx-1.18.0`）

- 新增控制器  
  - `src/main/java/com/hmdp/controller/AiController.java`
- 新增服务接口与实现  
  - `src/main/java/com/hmdp/service/IAiService.java`  
  - `src/main/java/com/hmdp/service/impl/AiServiceImpl.java`
- 新增 AI 调用客户端  
  - `src/main/java/com/hmdp/ai/client/AiRemoteClient.java`  
  - `src/main/java/com/hmdp/ai/client/AiRemoteClientImpl.java`  
  - `src/main/java/com/hmdp/ai/client/dto/*`
- 新增配置  
  - `src/main/java/com/hmdp/config/AiConfig.java`  
  - `src/main/java/com/hmdp/config/properties/AiProperties.java`  
  - `src/main/resources/application.yaml`（新增 `hmdp.ai.*`）
- Redis 常量扩展  
  - `src/main/java/com/hmdp/utils/RedisConstants.java`
- 登录拦截放行 AI 接口  
  - `src/main/java/com/hmdp/config/MvcConfig.java`
- 博客新增后失效 AI 摘要缓存  
  - `src/main/java/com/hmdp/service/impl/BlogServiceImpl.java`
- 前端页面改造  
  - `nginx-1.18.0 dianping/html/hmdp/shop-detail.html`（AI 总结卡片）  
  - `nginx-1.18.0 dianping/html/hmdp/shop-list.html`（AI 助手 5km 推荐）

### 3.2 AI 子服务（`../hmdp-ai-service`）

- `pom.xml`（Boot3 + Spring AI）
- `src/main/java/com/hmdp/ai/HmdpAiServiceApplication.java`
- `src/main/java/com/hmdp/ai/controller/InternalAiController.java`
- `src/main/java/com/hmdp/ai/service/AiOrchestrationService.java`
- `src/main/java/com/hmdp/ai/dto/*`
- `src/main/resources/application.yaml`

---

## 4. 功能设计

## 4.1 店铺探店博客智能总结

### 输入数据

- 来源：`tb_blog`
- 条件：`shop_id = ?`
- 排序：`liked desc, create_time desc`
- 上限：`summaryMaxBlogs`（默认 120）

### 分组预热流程（Map-Reduce 思路）

1. 按 `summaryGroupSize`（默认 20）分组。
2. 每组先查 Redis 分组摘要缓存。
3. 未命中时调用 AI 子服务 `/internal/ai/summarize/chunk`，失败则走本地规则兜底。
4. 所有分组结果再调用 `/internal/ai/summarize/final` 聚合总结。
5. 最终结果写入店铺摘要缓存。

### 为什么快

用户请求时不再对每条博客逐条调用模型；大量请求会复用已生成的“分组摘要 + 总摘要”。

---

## 4.2 点评 AI 助手（5km 推荐）

### 流程

1. 用户输入自然语言（如“附近想吃火锅”）。
2. 调用 AI 子服务 `/internal/ai/intent/parse` 解析意图关键词。
3. 主项目使用 Redis GEO（`shop:geo:{typeId}`）在 5km 内检索候选店铺。
4. 按评分/评论数/销量/距离/关键词匹配综合打分排序。
5. 调用 `/internal/ai/recommend/reason` 生成推荐理由（失败走模板兜底）。
6. 结果缓存后返回前端。

---

## 5. Redis 设计

## 5.1 新增 key

1. `ai:shop:summary:{shopId}`  
   店铺总摘要缓存（含指纹 fingerprint）。

2. `ai:shop:summary:chunk:{shopId}:{fingerprint}:{chunkIndex}`  
   分组摘要缓存。

3. `lock:ai:shop:summary:{shopId}`  
   店铺摘要构建互斥锁，防并发击穿。

4. `ai:assistant:rec:{queryHash}`  
   AI 助手推荐结果缓存。

### 缓存策略

- 总摘要/分组摘要默认 12 小时。
- 助手推荐默认 10 分钟。
- 博客新增时删除该店铺总摘要缓存，触发下次增量重建。

---

## 6. 接口清单

## 6.1 主项目对前端接口

1. `GET /ai/shop/{shopId}/summary?refresh=true|false`  
   查询店铺 AI 总结。

2. `POST /ai/shop/{shopId}/summary/warmup`  
   提交店铺总结预热任务。

3. `POST /ai/assistant/recommend`  
   AI 助手推荐。

示例请求：

```json
{
  "query": "附近有什么适合聚餐的火锅",
  "x": 120.149993,
  "y": 30.334229,
  "currentTypeId": 1
}
```

## 6.2 主项目对 AI 子服务内部接口

1. `POST /internal/ai/summarize/chunk`
2. `POST /internal/ai/summarize/final`
3. `POST /internal/ai/intent/parse`
4. `POST /internal/ai/recommend/reason`

---

## 7. 前端改造点

1. `shop-detail.html`  
   新增“AI 智能口碑总结”卡片，支持手动刷新。

2. `shop-list.html`  
   新增“AI 助手输入框 + 推荐结果列表”，点击可跳店铺详情。

---

## 8. 启动方式

## 8.1 启动主项目

在 `dianping-nginx-1.18.0` 目录下：

```bash
mvn spring-boot:run
```

默认端口：`8081`。

## 8.2 启动 AI 子服务

在 `hmdp-ai-service` 目录下：

1. JDK 17
2. 配置环境变量 `OPENAI_API_KEY`

```bash
mvn spring-boot:run
```

默认端口：`8090`。  
主项目通过 `hmdp.ai.base-url` 调用它。

---

## 9. 压测与风险点（面试可讲）

1. **评论/博客量过大**  
   通过分组摘要 + 总聚合降低 token 消耗。

2. **模型超时/失败**  
   主项目与 AI 子服务都有本地规则兜底，不阻断主流程。

3. **缓存击穿**  
   店铺摘要构建加分布式锁。

4. **一致性**  
   博客新增时主动失效店铺摘要缓存。

---

## 10. 可继续迭代（加分项）

1. 热门店铺定时预热（TOP N）。
2. 结果持久化落表（如 `tb_ai_shop_summary`）做审计与模型对比。
3. 增量重算（只处理新增博客）。
4. 引入向量检索做更细粒度语义召回。

