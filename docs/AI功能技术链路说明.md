# AI 功能技术链路说明

本文档以当前代码实现为准，说明每个 AI 功能使用的模型、调用方式、提示词、数据上下文和任务机制。文档中的“已使用”表示代码中已经存在并参与运行的实现；“未使用”表示项目虽然可能声明了相关依赖或概念，但当前业务链路没有真正接入。

## 1. 结论先看

当前项目的核心链路可以概括为：

```text
用户输入
  -> Spring Boot 接口
  -> 业务数据校验与敏感词检查
  -> 数据库提示词模板 + 业务字段组装
  -> RestClient 调用 DeepSeek 文本模型
  -> Jackson 解析并校验 JSON
  -> MySQL 保存故事、页面、角色卡和 AI 任务
  -> 后端把故事上下文、分镜和角色身份重新组装为图片提示词
  -> ModelScope 异步图片生成 + 角色参考图
  -> 本地文件库保存结果，前端轮询任务状态
```

与常见的“ChatClient 基础 → 提示词模板 → 结构化输出 → 记忆 → Tools → RAG → MCP”示例对应关系如下：

| 技术环节 | 当前项目实际情况 | 结论 |
|---|---|---|
| 模型客户端 | `AiGenerationService` 使用 Spring `RestClient`，手工调用 OpenAI 兼容的 `/chat/completions` 接口 | 已使用，但不是 `ChatClient` |
| 提示词模板 | `prompt_template` 表保存模板，使用 `{{变量名}}` 替换；代码同时提供 fallback 模板 | 已使用 |
| 结构化输出 | 提示词要求模型只返回 JSON；`ObjectMapper` 解析后检查字段、页数、角色 ID 等 | 已使用，属于“提示词约束 + 应用层校验” |
| 记忆 | 绘本、页面、角色卡和任务保存到 MySQL，并在后续步骤重新作为上下文传给模型 | 已使用业务上下文；未使用对话记忆 |
| Tools 工具调用 | 后端直接调用 DeepSeek、ModelScope、Azure Speech；没有把函数或工具暴露给模型 | 未使用 LLM Tool Calling |
| RAG | 没有向量数据库、Embedding、知识库检索或相似度召回 | 未使用 |
| MCP | 没有 MCP Server、MCP Client 或 MCP 工具注册 | 未使用 |
| 图片生成 | ModelScope `Qwen/Qwen-Image-2.1` 异步任务，支持角色参考图 | 已使用多模态/图像生成链路 |
| 语音朗读 | Azure Speech REST API，服务端返回 MP3 | 已使用，但不是文本大模型链路 |

`backend/pom.xml` 中虽然声明了 `spring-ai-starter-model-openai`，但业务代码没有导入或创建 `ChatClient`。依赖声明不等于 `ChatClient` 已经参与运行；当前实际入口是 `AiGenerationService.chatText()` 中的 `RestClient`。

## 2. 一键创作的完整技术链路

入口为 `POST /api/v1/books/auto`，任务状态通过 `GET /api/v1/books/auto/{taskId}` 查询。`AutoBookTaskService` 将流程拆成可恢复的阶段：

| 阶段 | 主要处理 | 使用的 AI 技术 |
|---|---|---|
| `STORY_GENERATION` | 根据主题、年龄、页数、风格和语言生成绘本 | DeepSeek Chat + 提示词模板 + JSON 输出 |
| 质量检查 | 检查页数、语言、年龄难度和故事结构 | DeepSeek Chat + JSON 输出；失败时有本地规则校验 |
| `BOOK_CREATION` | 将已通过检查的标题、页面文字、摘要和画面描述保存为绘本 | 业务持久化，不调用额外模型 |
| `CHARACTER_EXTRACTION` | 从全书页面文字中提取角色卡 | DeepSeek Chat + JSON 数组 |
| `STORYBOARD_GENERATION` | 为每页生成英文分镜、摘要和出场角色 ID | DeepSeek Chat + JSON 对象 + ID 白名单校验 |
| `IMAGE_GENERATION` | 生成或复用角色参考图，组装页面图片提示词 | DeepSeek Chat 负责提示词，ModelScope 负责图片 |
| `WAITING_FOR_IMAGES` | 等待每页图片任务完成 | 异步任务、状态持久化、重试 |
| `COMPLETED` | 记录绘本和任务结果 | MySQL/进程内索引 |

故事和质量检查最多尝试 3 次；页面图片任务最多尝试 3 次，并区分限流、无效请求和可重试错误。应用重启后，数据库中仍处于 `PENDING` 或 `PROCESSING` 的任务会被重新调度。

## 3. 模型调用层：使用 RestClient，不是 ChatClient

### 3.1 文本模型

`AiGenerationService.chatText()` 使用 Spring Web 的 `RestClient`，请求体结构为：

```json
{
  "model": "deepseek-chat",
  "messages": [
    {"role": "user", "content": "最终提示词"}
  ],
  "temperature": 0.8,
  "max_tokens": 4096
}
```

服务端读取 `choices[0].message.content` 作为模型结果。默认配置为 DeepSeek：

- `AI_BASE_URL=https://api.deepseek.com`
- `AI_CHAT_MODEL=deepseek-chat`
- `AI_API_KEY` 只在后端使用

运行时也可以从 `ai_model_config` 表读取模型地址、名称、温度、最大 token 数和加密保存的密钥。环境变量优先级高于数据库中的同类配置。

### 3.2 图片模型

页面插画使用 ModelScope 的异步接口，默认模型为 `Qwen/Qwen-Image-2.1`。后端提交提示词和尺寸，轮询任务状态，下载 `output_images`，再保存到本地文件库。页面尺寸由 `shared/book-page.json` 统一为 1024×1280 的 4:5 页面规格。

图片请求还可以携带角色参考图。参考图的作用是固定物种、脸部、体型、颜色、服装和配饰；提示词明确要求模型不要复制参考图的排版、姿势或背景。

## 4. 提示词模板：数据库模板加代码 fallback

模板表为 `prompt_template`，初始化数据位于 `db/data.sql`。当前模板包括：

| `template_key` | 用途 |
|---|---|
| `story_gen` | 故事生成 |
| `character_extract` | 角色提取 |
| `character_polish` | 角色描述润色 |
| `character_image_gen` | 角色参考图提示词 |
| `storyboard_gen` | 全书分镜 |
| `image_prompt` | 页面插画提示词 |
| `auto_quality_check` | 一键创作质量检查 |

`AiGenerationService.template()` 的处理顺序是：

1. 先使用代码内置的 fallback 模板。
2. 数据库可用时，按 `template_key` 读取已启用模板。
3. 用 `{{theme}}`、`{{story}}`、`{{characters}}` 等占位符替换业务数据。
4. 将结果发送给文本模型。

这是一种可由管理员修改的 Prompt Template 方案。它不是 RAG，也不是工具调用；模板本身只是模型输入的组织方式。

## 5. 结构化输出：JSON 约束、解析和业务校验

项目没有使用模型供应商的原生 JSON Schema 或 `ChatClient.entity()` 映射，而是采用三层约束：

1. **提示词约束**：要求模型“只输出 JSON”，并明确字段结构。例如故事要求 `title`、`coverText`、`pages`，分镜要求 `pageNo`、`scenePrompt`、`summary`、`characters`。
2. **格式解析**：`chatJson()` 使用 Jackson `ObjectMapper.readTree()` 解析模型文本，并从最外层对象或数组开始读取。
3. **业务校验**：检查页数是否完整、页码是否重复、画面描述是否为空、角色 ID 是否属于当前绘本角色卡；不满足时终止后续图片任务。

故事生成和质量检查还会在模型结果之外执行本地校验，例如页数、语言和敏感内容检查。因此这里的“结构化输出”应准确表述为：

> 基于 Prompt 的 JSON 结构化输出，结合 Jackson 解析和应用层字段校验；不是严格的服务商原生结构化输出。

## 6. 记忆：有业务上下文，没有聊天记忆

### 6.1 已实现的业务上下文

系统把绘本创作过程中的数据持久化到 MySQL，并在后续阶段重新读取：

- `book`：主题、语言、故事风格、画风、随机种子和作品状态。
- `book_page`：每页原文、摘要、分镜、出场角色 ID、图片地址和图片状态。
- `character_card`：角色名称、年龄性别、外观、服装、特征和参考图地址。
- `ai_task`：任务类型、阶段、进度、重试次数、提示词、参考图和结果。

生成页面图片时，后端会把当前页故事原文、摘要、分镜、全书故事上下文、画风和当前页角色身份再次拼接到提示词中。这个机制可以称为“显式业务上下文”或“应用层记忆”。

### 6.2 当前没有实现的对话记忆

当前没有以下能力：

- 没有用户与 AI 的连续聊天会话 ID。
- 没有 `ChatMemory`、消息历史表或上下文窗口管理。
- 没有根据上一轮聊天自动恢复语境的聊天机器人。

因此不能把当前项目描述成“接入了 AI 对话记忆”；更准确的说法是“通过 MySQL 持久化绘本创作状态，并在多阶段生成中显式传递作品上下文”。

## 7. Tools、RAG 和 MCP 的实际状态

### 7.1 Tools 工具调用

当前没有让模型自主选择工具。模型不会返回 `tool_calls`，后端也没有注册 `@Tool`、函数 Schema 或工具回调。

现有的外部服务调用都由 Java 业务代码固定编排：

- `RestClient` 调用 DeepSeek 文本接口。
- `RestClient` 调用 ModelScope 图片接口。
- `HttpClient` 调用 Azure Speech 语音接口。

这属于“后端确定性调用外部 API”，不属于 LLM Tool Calling。

### 7.2 RAG 检索增强生成

当前没有 Embedding 模型、向量表、向量数据库、文档切分、相似度检索或召回结果拼接。因此不应把 `storyContext(book)` 或 MySQL 查询称为 RAG。

当前做的是：直接读取本绘本的故事、页面和角色卡，并把这些确定性数据放进 Prompt。这是结构化业务上下文注入，不是知识库检索。

### 7.3 MCP

当前后端没有实现 MCP Server 或 MCP Client，也没有通过 MCP 暴露数据库、文件系统或第三方服务工具。项目中的“工具”如果后续需要接入 MCP，应单独增加 MCP 传输、Server/Client 配置和工具发现流程；不能仅因为存在外部 API 就称为 MCP。

## 8. 其他 AI 相关能力

### 8.1 角色一致性和参考图

`IllustrationPreparationService` 先调用模型建立全书固定角色设定，再调用分镜模型为每页绑定角色 ID。生成图片前，每个角色在本书内复用同一张参考图；预设角色的多视角图会先转换为单角色参考图。

这部分采用的是：

- 角色结构化信息。
- 角色参考图。
- 固定随机种子。
- 每页角色 ID 白名单。
- 图片提示词中的身份约束。

它是应用层的角色一致性方案，不是向量检索，也不是模型微调。

### 8.2 内容安全

`SensitiveWordService` 在提交主题、生成结果、保存绘本、角色和图片提示词等环节执行敏感词检查。故事生成后还会对完整故事文本再次检查；命中时不会进入后续图片任务。

### 8.3 语音朗读

`AzureSpeechService` 将页面文字转换为 SSML，按中文或英文选择 Azure 神经网络音色，调用 Azure Speech REST API 返回 MP3。服务端保管密钥，并在进程内缓存音频、合并相同请求，最多并行 4 个语音请求。

语音功能属于 TTS（Text-to-Speech），与 DeepSeek 文本生成和 ModelScope 图片生成是三条独立的模型服务链路。

## 9. 按功能查看技术选型

| 产品功能 | 主要入口 | 使用技术 | 是否涉及 RAG/Tools/MCP |
|---|---|---|---|
| 一键生成故事 | `POST /api/v1/books/auto` | DeepSeek Chat、Prompt Template、JSON 解析、质量检查、异步任务 | 否 |
| 手动生成故事 | `POST /api/v1/books/{id}/story` | DeepSeek Chat、Prompt Template、JSON 解析 | 否 |
| 自动提取角色 | `POST /api/v1/books/{id}/characters/extract` | DeepSeek Chat、JSON 数组、Jackson | 否 |
| 角色描述润色 | `POST /api/v1/books/{id}/characters/polish` | DeepSeek Chat、文本 Prompt | 否 |
| 角色参考图 | `POST /api/v1/books/{id}/characters/reference` | DeepSeek Chat 生成英文画面提示词 + ModelScope 图片生成 | 否 |
| 生成分镜 | `POST /api/v1/books/{id}/storyboard` | 故事/角色上下文注入、DeepSeek Chat、JSON 校验 | 否 |
| 生成页面插画 | `POST /api/v1/books/{id}/images` | 页面 Prompt、角色参考图、ModelScope 异步任务、重试 | 否 |
| 绘本朗读 | `POST /api/v1/books/{bookId}/pages/{pageId}/speech` | Azure Speech TTS、SSML、音频缓存 | 否 |
| 敏感内容检查 | 主题、故事、角色和 Prompt 保存前 | MySQL 敏感词表 + 本地规则 | 否 |

## 10. 代码和配置定位

| 内容 | 文件 |
|---|---|
| 文本/图片模型调用、Prompt 组装、JSON 解析 | `backend/src/main/java/com/storybook/ai/AiGenerationService.java` |
| 一键创作阶段、质量检查和任务恢复 | `backend/src/main/java/com/storybook/ai/AutoBookTaskService.java` |
| 角色统一、分镜准备和参考图复用 | `backend/src/main/java/com/storybook/ai/IllustrationPreparationService.java` |
| 图片任务并发、重试和状态持久化 | `backend/src/main/java/com/storybook/ai/ImageTaskService.java` |
| 绘本 AI 接口 | `backend/src/main/java/com/storybook/controller/BookController.java` |
| 语音服务 | `backend/src/main/java/com/storybook/speech/AzureSpeechService.java` |
| Prompt 初始数据和模型配置 | `db/data.sql` |
| `prompt_template`、`ai_model_config`、`ai_task` 表 | `db/schema.sql` |
| 模型地址、密钥和默认参数 | `backend/src/main/resources/application.yml`、`backend/.env.example` |

## 11. 可用于论文或答辩的准确表述

可以这样描述当前系统：

> 系统采用 Spring Boot 构建 AI 编排服务，通过 `RestClient` 调用 OpenAI 兼容的 DeepSeek Chat 接口生成故事、角色和分镜，通过数据库维护可配置 Prompt Template，并采用“Prompt 约束 JSON + Jackson 解析 + 业务规则校验”的方式实现结构化输出。绘本、页面、角色卡和 AI 任务保存到 MySQL，在后续阶段以显式业务上下文重新注入模型，从而实现跨阶段的创作状态保持。页面插画使用 ModelScope 的异步图像生成接口，并结合角色参考图保持角色一致性；朗读使用 Azure Speech TTS。当前系统未接入对话记忆、LLM Tool Calling、RAG 向量检索或 MCP。

如果未来要升级为完整的“ChatClient → Memory → Tools → RAG → MCP”架构，应分别增加：`ChatClient` 适配层、会话消息存储、工具 Schema 和回调、Embedding 与向量库、MCP Server/Client；这些能力不能仅通过修改 Prompt 模板获得。
