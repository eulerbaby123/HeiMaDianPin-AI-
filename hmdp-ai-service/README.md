# hmdp-ai-service

独立 AI 子服务（Sidecar），用于承接：

1. 分组摘要（chunk summarize）
2. 总摘要（final summarize）
3. 意图解析（intent parse）
4. 推荐理由生成（recommend reason）

## 启动

1. JDK 17
2. 设置环境变量：

```bash
OPENAI_API_KEY=你的key
OPENAI_MODEL=gpt-4o-mini
```

3. 运行：

```bash
mvn spring-boot:run
```

默认端口 `8090`。

## 内部接口

- `POST /internal/ai/summarize/chunk`
- `POST /internal/ai/summarize/final`
- `POST /internal/ai/intent/parse`
- `POST /internal/ai/recommend/reason`

说明：当模型不可用或超时时，服务会返回本地规则兜底结果，保证主业务可用。

