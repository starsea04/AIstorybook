# AI 绘本创作平台后端

Java 17 / Spring Boot 3.5.6 API。后端通过 JDBC 使用 MySQL 持久化用户、绘本、绘本页、角色卡与 AI 任务；社区互动、审核和管理配置也有数据库实现。运行时会将绘本数据载入进程内索引。

## 启动

1. 在仓库根目录按 [db/schema.sql](../db/schema.sql) 与 [db/data.sql](../db/data.sql) 初始化 MySQL 8 的 `storybook` 数据库。Spring 不会自动导入 SQL，`CREATE TABLE IF NOT EXISTS` 也不会迁移已存在的表。升级已有数据库前先备份并检查结构；如缺少敏感词命中日志表，执行 [db/migrations/20261102_sensitive_word_hit.sql](../db/migrations/20261102_sensitive_word_hit.sql)。
2. 在 `backend` 目录将 `.env.example` 复制为 `.env`，设置 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD` 和长度至少 32 字节的随机 `JWT_SECRET`。数据库严格模式 `DB_STRICT` 默认启用，数据库不可用或启动加载失败时服务会拒绝启动。需要实际模型功能时，配置 `AI_API_KEY`、`AI_BASE_URL`、`AI_CHAT_MODEL`、`IMAGE_API_KEY`、`IMAGE_BASE_URL=https://api-inference.modelscope.cn/` 和 `IMAGE_MODEL=Qwen/Qwen-Image-2.1-Turbo`。图片生成使用 ModelScope 异步任务接口，服务端会轮询任务并下载 `output_images` 到本地文件库。数据库模型密钥加密配置项为 `APP_AI_CONFIG_ENCRYPTION_KEY`；更换它会使已存储密文无法解密。不要将 `.env` 或密钥提交到版本库。
3. 在 `backend` 目录运行：

   ```bash
   mvn spring-boot:run
   ```

服务默认监听 `http://localhost:8080`，Swagger UI 位于 `/swagger-ui.html`，健康检查位于 `/actuator/health`。文件默认写入 `./uploads`，可通过 `FILE_STORAGE_DIR` 调整；需要中文 PDF 时可用 `app.pdf.font-path` 指定 TTF/TTC 字体路径，未指定时服务会尝试发现常见系统字体。

默认数据库管理员为 `admin / Admin@123`，由根目录 `db/data.sql` 初始化。部署前请替换默认账号密码和 JWT 密钥。

## 当前功能

- Azure 文本转语音：在后端 `.env` 配置 `AZURE_SPEECH_KEY`、`AZURE_SPEECH_ENDPOINT`；中文默认 `zh-CN-XiaoshuangNeural`，英文默认 `en-US-JennyNeural`，可用 `AZURE_SPEECH_VOICE_ZH` / `AZURE_SPEECH_VOICE_EN` 修改。区域资源终结点（例如 `https://eastasia.api.cognitive.microsoft.com/`）会转换为同区域的 Speech 合成终结点。密钥只在后端使用，不进入前端打包产物。
- `POST /api/v1/books/{bookId}/pages/{pageId}/speech` 返回 MP3，需登录且有权阅读该绘本（作者、管理员或已发布作品的读者）。可选 JSON `{ "text": "当前页文字" }` 仅允许作者预览未保存的编辑；其他读者始终朗读已保存文字。单页最多 2000 字，SSML 文本会转义；按文本与音色缓存最近音频（进程内最多 128 条 / 32 MB），相同请求合并，最多 4 个并行 Azure 请求。未开启朗读时不请求语音服务。

- 内页成功生成后自动成为随机且稳定的封面候选；启动时为历史绘本回填封面并保留原更新时间。图像任务记录故事原文、实际提示词、角色 ID 和参考图，支持传入 `regenerate: true` 重画已成功的页面。
- 分镜使用全书统一角色设定，并兼容旧数据库提示词。故事原文始终单独传给模型；不完整的分镜或非法角色引用会阻止插画生成。角色参考图在本书内复用。

- `POST /api/v1/books/auto` 创建后台一键创作任务；使用 `GET /api/v1/books/auto/{taskId}` 轮询进度与结果。故事生成及质量检查最多尝试三次，随后执行角色提取、分镜处理和逐页插画任务。完整创作需要可用的聊天和图像模型。没有聊天密钥时故事与质量检查会使用本地演示逻辑；没有图像密钥时插画任务失败，可在检查模型配置后重试。
- 手动创作支持故事、角色卡和分镜编辑、逐页插画生成与重试、提交审核，以及 PDF/ZIP 导出。角色参考图片上传端点为 `POST /api/v1/books/{bookId}/characters/reference-image`，接受 PNG/JPG/WebP，最大 5 MB。预设角色图打包在 `src/main/resources/static/files/presets/`，通过 `/files/presets/**` 访问；用户上传和生成文件通过 `/files/**` 提供。
- 社区支持已发布绘本的列表与详情、评论、点赞和收藏；敏感词检测及命中日志、内容审核、模型配置与提示词模板由数据库支持。未连接数据库的开发模式会将部分功能留在进程内，不能用于需要跨重启保存数据的运行环境。
- `GET /api/v1/books/{id}/export/pdf` 生成横向 A4 PDF；`GET /api/v1/books/{id}/export/zip` 生成 UTF-8 ZIP，包含 `story.txt` 和可用的本地原始插画。远程图片不会自动下载；PDF 会优先使用配置或系统中文字体，找不到时回退到内置的 STSong-Light 字体。

## 修复已有绘本的插画

`scripts/RepairBookIllustrations.java` 直接读取本地 `.env` 和数据库，使用生产代码生成新插画。先备份数据、暂存图片，检查后再应用；不会在生成过程中覆盖原书。

```powershell
mvn -q package dependency:build-classpath '-Dmdep.outputFile=target/runtime-classpath.txt' '-Dmdep.includeScope=runtime'
$cp = 'target/classes;' + (Get-Content target/runtime-classpath.txt -Raw).Trim()
java -cp $cp scripts/RepairBookIllustrations.java 11
# 失败时按输出的目录继续，仅重试未成功的页面：
java -cp $cp scripts/RepairBookIllustrations.java --resume <修复目录>
# 检查 ready.json 和实际图片；停止后端后再应用，然后重启后端：
java -cp $cp scripts/RepairBookIllustrations.java --apply <修复目录>
```

备份、实际提示词和暂存结果位于 `uploads/repairs/`，原图片保留。应用前会检查原故事、页面身份、更新时间和未完成任务；如果原书在生成期间被编辑，则拒绝覆盖。

