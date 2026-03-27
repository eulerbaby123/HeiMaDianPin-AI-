黑马点评・大模型 AI 升级版
✨ 基于原生黑马点评扩展
📌 项目简介
本项目在原生黑马点评本地生活业务底座之上，完成全链路大模型智能化改造。采用 Sidecar 侧向架构 独立拆分 AI 服务，不侵入原有稳定业务核心，兼顾老旧技术栈兼容性与 AI 前沿能力落地，适配简历 & 面试亮点展示。
✅ 保留原有秒杀、缓存、异步下单、商圈定位全能力✅ 新增三大大模型场景✅ 多级降级兜底
当前仓库统一包含：
主业务后端：dianping-ngx-1.18.0
移动端前端静态资源：Nginx 托管页面
独立 AI 侧向子服务：hmdp-ai-service
一键全量初始化 SQL 脚本
深度面试拆解私有文档
🎬 核心 AI 功能演示（手机端截图）
<div align="center"><table><tr><td align="center"><b>🤖 AI智能点评推荐助手</b></td><td align="center"><b>📝 AI店铺口碑智能总结</b></td><td align="center"><b>🛡️ AI评论内容风控校验</b></td></tr><tr><td><img src="./docs/images/1.png" width="240"></td><td><img src="./docs/images/2.png" width="240"></td><td><img src="./docs/images/3.png" width="240"></td></tr></table></div>
🚀 核心改造亮点
<div style="background:#f6f8fa;padding:16px;border-radius:8px">
🧠 AI 店铺口碑聚合总结：批量游记 Chunk 分片摘要 + 全局精炼汇总，多级缓存加速
📍 AI 智能点评推荐助手：结合地理位置 + 品类偏好 + 距离权重，大模型重排 & 推荐理由生成
⚖️ AI 评论智能风控审核：拦截广告引流、隐私手机号、辱骂违法内容，双端双层校验
📄 扩展商户核心字段 shop_desc：标准化结构化文案，适配大模型语义理解
⚡ Redis 全链路优化：结果缓存、指纹防重、分组聚合、热点数据预热兜底
🎯 原生秒杀架构保留：Redis+Lua 原子控库存、RabbitMQ 异步削峰、死信队列补偿
</div>
🏗️ 大模型落地架构设计
采用 Sidecar 旁挂模式，彻底解耦业务与 AI 算力
🔹 主业务服务：负责 GEO 商圈检索、DB 读写、缓存管理、基础业务规则、HTTP 调度编排
🔹 AI 独立子服务：负责意图识别、文本摘要、智能重排、风控判别、Prompt 编排
🔹 通信方式：标准 HTTP 内网调用，轻量无侵入
架构分层优势
主服务稳态运行：SpringBoot2.3 + JDK8，线上成熟稳定底座不动
AI 服务独立演进：SpringBoot3.3 + JDK17 + SpringAI，无缝迭代模型
厂商无感切换：统一抽象层，快速兼容通义、GPT、本地私有模型
双路降级兜底：模型超时 / 限流 / 异常时，本地规则无缝熔断保障可用
当前默认接入：阿里通义千问 DashScope，API Key 采用环境变量注入
📁 标准仓库目录结构
tree
.
├── README.md                 # 项目公共说明文档
├── myself-readme.md          # 面试深度拆解&个人笔记
├── sql/                      # 数据库初始化脚本
│   └── open-source-full-init.sql
├── docs/
│   └── images/               # 功能演示配图资源
│       ├── 1.png  👉 AI点评助手
│       ├── 2.png  👉 口碑总结
│       └── 3.png  👉 评论风控
├── dianping-nginx-1.18.0/    # 核心主业务后端+Nginx前端
│   ├── pom.xml
│   ├── src/main/java/com/hmdp
│   ├── src/main/resources/
│   └── nginx-1.18.0 dianping/
└── hmdp-ai-service/          # 独立大模型侧向服务
    ├── pom.xml
    └── src/main/resources/
🛠️ 全域技术栈
🔹 核心主业务
Spring Boot 2.3.12 | JDK 8
MyBatis-Plus | MySQL 5.7+/8.x
Redis | Redisson 分布式锁
RabbitMQ 异步削峰
Nginx 反向代理 & 静态资源托管
🔹 AI 智能化子服务
Spring Boot 3.3.5 | JDK 17
Spring AI 一体化大模型编排
阿里 DashScope 通义系列模型
💡 三大 AI 核心业务详解
1. AI 店铺口碑总结
数据源：商户游记表 tb_blog
执行逻辑：店铺维度聚合 → 分片局部摘要 → 全局统一精炼
优化方案：多级内存缓存 + 内容指纹防重复计算
前端入口：shop-detail.html
2. AI 智能点评推荐助手
入参：用户自然语言诉求 + GPS 定位坐标 + 商铺品类
检索链路：Redis GEO 就近检索优先 → MySQL 降级兜底
排序逻辑：基础规则粗排 → LLM 智能精排 → 定制推荐文案生成
依赖基底：商户结构化简介 tb_shop.shop_desc
前端入口：shop-list.html
3. AI 点评风控内容审核
双层防护：前端提交预校验 + 后端接口强制拦截
风控范围：广告导流、联系方式泄露、辱骂敏感、违禁词汇
容灾兜底：大模型异常自动切本地敏感词规则
前端入口：blog-edit.html
🐰 RabbitMQ 秒杀异步架构保留
前端秒杀请求流量收口
Redis+Lua 脚本原子校验：库存防超卖 + 一人一单限制
削峰投递：秒杀消息异步推入 RabbitMQ
消费落地：业务消费者异步创建订单，分布式锁 + 事务保障
异常补偿：消费失败转入死信队列重试兜底
核心关联源码：
秒杀业务实现：VoucherOrderServiceImpl.java
消息消费者监听：SeckillVoucherListener.java
队列交换机配置：RabbitMQConfig.java
⚙️ 本地快速环境部署
1. 基础依赖环境
JDK 8（主服务） | JDK 17（AI 子服务）
Maven 3.9+
MySQL 5.7+/8.x | Redis 6.0+
RabbitMQ 3.x | Nginx 1.18+
2. 数据库一键初始化
执行根目录全量脚本：
sql
sql/open-source-full-init.sql
脚本自动完成：
创建业务库 hmdp & 基础数据表
补齐大模型所需商户结构化简介
预置演示商户 + 海量游记样本数据
专项构造 30 家美食 AI 测试商户，模拟真实演示场景
3. 主服务核心配置修改
路径：dianping-nginx-1.18.0/src/main/resources/application.yaml按需修改：数据库、Redis、RabbitMQ、AI 子服务基础地址等核心连接参数
默认主服务端口：8081
4. AI 子服务安全配置
路径：hmdp-ai-service/src/main/resources/application.yaml已全局环境变量隔离密钥，无需硬编码提交仓库
powershell
# Windows终端配置临时环境变量
set DASHSCOPE_API_KEY=你的阿里通义密钥
set DASHSCOPE_MODEL=qwen-turbo-flash
set HMDP_AI_PORT=8090
默认 AI 服务端口：8090
5. 本地图片上传路径适配
修改常量配置：dianping-nginx-1.18.0/src/main/java/com/hmdp/utils/SystemConstants.java校正 IMAGE_UPLOAD_DIR 为你本机 Nginx 静态资源真实路径
6. Nginx 反向代理配置
路径：dianping-nginx-1.18.0/nginx-1.18.0 dianping/conf/nginx.conf默认配置：
Nginx 访问端口：8080
/api/** 反向代理至 127.0.0.1:8081
建议通过 Nginx 域名访问前端，勿直接打开本地 HTML
7. 标准启动顺序
MySQL → Redis → RabbitMQ
执行全量数据库初始化脚本
优先启动 AI 侧向子服务
启动 Java 主业务后端
启动 Nginx 静态代理
8. 快捷启动命令
powershell
# AI子服务启动
cd hmdp-ai-service
mvn spring-boot:run

# 主后端启动
cd dianping-nginx-1.18.0
mvn spring-boot:run

# Nginx启动
cd "dianping-nginx-1.18.0/nginx-1.18.0 dianping"
start nginx.exe
9. 项目访问地址
📱 移动端前端首页：http://127.0.0.1:8080
🖥️ 核心业务后端：http://127.0.0.1:8081
🤖 AI 智能化子服务：http://127.0.0.1:8090
🔥 Redis 缓存预热方案
SQL 脚本仅初始化磁盘库数据，不会主动写入内存缓存项目内置两种自动化预热方案：
懒加载：用户真实访问按需回写缓存
主动预热：内置启动器批量灌入商圈 GEO、商户热点缓存
预热入口：dianping-nginx-1.18.0/src/main/java/com/hmdp/tools/DemoDataSeedRunner.javaIDE 直接运行即可一键完成全量 Redis 业务预热
📂 核心源码快速导航
✅ 主业务核心
AI 统一入口控制器：AiController.java
AI 编排调度核心：AiServiceImpl.java
AI 远程调用客户端：AiRemoteClientImpl.java
Redis 全局常量定义：RedisConstants.java
游记发布风控拦截：BlogController.java
✅ AI 侧向子服务
内部私有接口入口：InternalAiController.java
Prompt 管理 & 降级兜底：AiOrchestrationService.java
