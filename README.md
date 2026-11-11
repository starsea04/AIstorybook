# AI 绘本创作平台

一个 Vue 3 与 Spring Boot 组成的 AI 绘本创作平台，包含分步创作、一键创作、绘本编辑与阅读、作品管理、社区互动和管理员工作台。用户、绘本、页面、角色卡及 AI 任务通过 JDBC 持久化；社区互动、审核记录与主要管理配置也使用 MySQL。

## 技术栈

- 后端：Java 17、Spring Boot 3.5.6、Spring Security/JWT、Spring JDBC、MySQL 8。
- 前端：Vue 3、TypeScript、Vite 6、Pinia、Vue Router、Element Plus。
- AI：聊天服务使用 DeepSeek；图像服务使用 ModelScope 的异步图片生成接口（`Qwen/Qwen-Image-2.1`）。

## 启动

1. 首次建库时导入 SQL：

   ```bash
   mysql -uroot -p < db/schema.sql
   mysql -uroot -p storybook < db/data.sql
   ```

   这两份根目录脚本用于初始化 `storybook` 数据库，Spring 不会自动导入 SQL。`CREATE TABLE IF NOT EXISTS` 不会升级已存在的表；请先备份已有数据库，并根据实际结构执行迁移。若既有数据库缺少敏感词命中日志表，执行：

   ```bash
   mysql -uroot -p storybook < db/migrations/20261102_sensitive_word_hit.sql
   ```

2. 在 `backend` 目录复制 `.env.example` 为 `.env`，再填入本机 MySQL 密码和服务商密钥。PowerShell 可运行：

   ```powershell
   Copy-Item .env.example .env
   ```

   至少检查以下配置：

   ```text
   DB_URL=jdbc:mysql://localhost:3306/storybook?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
   DB_STRICT=true
   DB_USERNAME=root
   DB_PASSWORD=your_mysql_password
   AI_BASE_URL=https://api.deepseek.com
   AI_API_KEY=your_deepseek_key
   AI_CHAT_MODEL=deepseek-chat
   IMAGE_BASE_URL=https://api-inference.modelscope.cn/
   IMAGE_API_KEY=your_modelscope_token
   IMAGE_MODEL=Qwen/Qwen-Image-2.1
   JWT_SECRET=change-this-to-a-random-secret-at-least-32-bytes
   ```

   请勿提交 `.env` 或真实密钥。`DB_STRICT=true` 是默认设置，MySQL 不可用或启动加载失败时后端会拒绝启动。数据库连接 URL 使用 Connector/J 支持的 `characterEncoding=utf8`，表字符集仍为 `utf8mb4`。管理端将模型密钥保存到数据库时，还需要设置 `APP_AI_CONFIG_ENCRYPTION_KEY`；密钥会以 AES-GCM 密文保存。配置密钥后不要更换，否则已有密文将无法解密。

3. 在 `backend` 目录启动后端：

   ```bash
   mvn spring-boot:run
   ```

4. 在 `frontend` 目录安装依赖并启动前端：

   ```bash
   npm install
   npm run dev
   ```

打开 <http://localhost:5173>。后端接口文档位于 <http://localhost:8080/swagger-ui.html>。
健康检查位于 <http://localhost:8080/actuator/health>。

## 创作与文件

- 一键创作通过 `POST /api/v1/books/auto` 提交后台任务，再轮询 `GET /api/v1/books/auto/{taskId}` 获取阶段、进度及结果。流程会生成并检查故事（最多尝试三次）、创建绘本、提取角色、生成分镜并排队生成插画。完整流程需要配置可用的聊天与图像模型；未配置聊天密钥时故事与质量检查使用本地演示逻辑，未配置图像密钥时图像任务会失败并显示错误，可配置密钥后重试。
- 手动创作支持编辑故事、角色、分镜和页面；页面插画任务可以查看状态并重试。角色参考图可上传 PNG、JPG 或 WebP 文件，最大 5 MB；预设角色插画打包在后端资源中，并通过 `/files/presets/**` 提供。
- 封面从本书成功生成的内页插画中按绘本种子随机选取，刷新后保持稳定；插画替换或删除时同步更新。启动时为历史绘本补齐封面。首页、我的绘本和社区共用真实内页封面；尚未生成插画时显示文字状态。
- 分镜会将预设角色设计绑定到故事中的实际角色名，并为其他出场角色补齐固定设定。插画生成始终带入本页故事原文、画风、角色身份及参考图；预设多视角图先转换为本书的单姿态参考图，避免多个视角变成重复角色。任务保存实际提示词和参考图，便于排查图文错配。
- 编辑页支持“重新生成插画”。限流时会等待后重试；阅读器完整显示插画，避免裁掉风筝等关键情节物体。
- 插画编辑预览完整显示原图，并可点击放大；重画按钮根据实际任务状态禁用和恢复。文字自动保存只更新可编辑内容，不回写图片地址或生成状态；任务轮询只合并图片结果，保留正在输入的文字。重新打开已有绘本会恢复任务轮询，启动时会校正已完成任务遗留的“生成中”状态。
- 阅读器使用 StPageFlip（`page-flip@2.0.7`），桌面显示插画与文字双页，窄屏逐张翻阅插画页和文字页，保留缩略图、键盘、自动翻页与全屏。安装依赖时自动应用一个局部补丁，修复该版本关闭阅读器后动画循环未停止的问题；升级库版本前需复核补丁。
- `shared/book-page.json` 是阅读器和后端共同的书页尺寸定义：当前为 1024×1280（4:5）。页面生图请求显式发送此尺寸，并检查下载图片的实际宽高；尺寸不符时报告错误，不裁切拉伸，也不重复消耗生成次数。角色参考图使用独立的模型尺寸配置。旧内页插画完整显示；随机内页封面放大铺满卡片并居中裁切。
- 上传与生成的文件默认写入后端工作目录下的 `uploads`（可通过 `FILE_STORAGE_DIR` 修改），并通过 `/files/**` 访问。启用参考图生图还取决于图像服务及数据库模型参数是否支持该能力。
- `GET /api/v1/books/{id}/export/pdf` 导出横向 A4 PDF；`GET /api/v1/books/{id}/export/zip` 导出 UTF-8 ZIP，包含 `story.txt` 和可用的本地插画。PDF 需要可用的中文字体；可通过 `app.pdf.font-path` 指定 TTF/TTC 文件。没有可用中文字体时 PDF 导出会失败。远程图片地址不会由导出器自动下载。

## 绘本语音朗读

在 `backend/.env` 配置 `AZURE_SPEECH_KEY` 与 `AZURE_SPEECH_ENDPOINT`（例如 `https://eastasia.api.cognitive.microsoft.com/`），重启后端即可。密钥不放入前端；语音使用 Azure Speech REST API，中文和英文分别使用对应的神经网络音色，配置示例见 `backend/.env.example`。

阅读器默认关闭语音。用户可独立开启／关闭语音和自动翻页，并暂停、继续或重播当前页。仅自动翻页时，每张展示页面停留 5 秒；语音与自动翻页同时开启时，等待音频真实 `ended` 事件后再翻到下一故事页，不按字数估算时长。窄屏的插画叶与文字叶属于同一故事页，语音自动模式每个故事页只朗读一次。空白页停留 5 秒；加载、暂停和失败期间不提前翻页，失败时暂停自动翻页并允许重试。末页朗读结束后停止自动翻页，手动换页和关闭阅读器会取消旧声音与请求。

语音接口需登录；在创作预览、我的绘本和社区阅读器中均可使用。单页最多朗读 2000 字。可在 `frontend` 运行 `npm run test:reader` 验证播放调度（需要支持 TypeScript 类型擦除的 Node.js 22.6+）；后端运行 `mvn test`。

## 数据与默认账号

- MySQL 模式持久化用户、绘本、绘本页、角色卡、AI 任务、社区评论与互动、审核和管理配置。运行时会将绘本与关联数据载入进程内索引；AI 任务重启后会恢复未完成任务。
- 管理员：`admin` / `Admin@123`。该账号由 `db/data.sql` 初始化；部署前请更换默认凭据及 JWT 密钥。
- 默认不应将开发内存模式用于部署。若项目配置为无数据库运行，演示账号为 `admin / Admin@123` 和 `demo / Demo@123`，数据仅保存在进程内。

## 目录

```text
backend/                 Spring Boot API
frontend/                Vue 3 用户端与管理端
shared/book-page.json    阅读器与生图共用的书页尺寸
db/schema.sql            MySQL 表结构
db/data.sql              初始化数据、提示词、画风、预设角色和敏感词
db/migrations/           既有数据库迁移脚本
```
