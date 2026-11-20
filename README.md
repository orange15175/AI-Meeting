# AI 模拟面试平台

面向 Java 求职者的文字面试平台，围绕简历解析、个性化出题、回答评分、动态追问与报告复盘组织面试流程，并提供普通 AI 文字聊天。

## 项目结构

```text
AI-Meeting/
├── backend/       # Java / Spring Boot 后端、SQL、部署配置与设计文档
├── frontend/      # Vue 3 / TypeScript 前端
└── README.md      # 项目介绍、环境配置与启动说明
```

旧 React 工程不纳入本仓库；当前前端统一使用 `frontend/`。

## 技术栈

- 后端：Java 17、Spring Boot 3、Spring AI、MyBatis-Plus、Sa-Token。
- 流程与调用治理：枚举状态机、LiteFlow、Resilience4j、Micrometer。
- 存储：MySQL 保存正式业务数据；Redis / Redisson 承担登录态、限流与会话协调。
- 模型：DeepSeek 处理文本；图片与扫描 PDF 使用独立视觉模型配置。
- 前端：Vue 3、TypeScript、Vite、Vue Router、Pinia、Element Plus。

## 本地启动

准备 JDK 17、Node.js 22 LTS、MySQL 8.x、Redis 7.x。以下命令均从仓库根目录开始，在两个独立终端运行前后端。

### 1. 数据库和配置

新建数据库 `mainshi_agent`，按顺序导入 `backend/admin/src/main/resources/sql/` 下的文件：

1. `schema.sql`
2. `interview_core.sql`
3. `admin_permission.sql`
4. `ai_properties.sql`
5. `t_user.sql`

上述步骤用于全新数据库；已有数据库请先备份并核对结构，不要重复执行初始化脚本。

后端读取环境变量，也支持本地的 `backend/admin/application-local.yaml`。已有本地配置随目录迁移保留，该文件不纳入 Git。

常用环境变量：

| 变量 | 说明 |
| --- | --- |
| `MYSQL_HOST` / `MYSQL_PORT` | 默认 `127.0.0.1:3306` |
| `MYSQL_DATABASE` / `MYSQL_USERNAME` / `MYSQL_PASSWORD` | 默认数据库 `mainshi_agent`、用户 `root`；密码需自行配置 |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | 默认 `127.0.0.1:6379`，按本机配置设置 |
| `LLM_BASE_URL` / `LLM_MODEL` / `LLM_API_KEY` | 文本模型地址、名称和密钥，默认 DeepSeek / `deepseek-chat` |
| `VISION_BASE_URL` / `VISION_MODEL` / `VISION_API_KEY` | 图片和扫描 PDF 的视觉模型配置，需使用服务商实际可用的接口与模型名称 |

密钥只配置在后端；不要写入前端源码或提交到 Git。Docker 的 `.env` 由 Compose 加载，直接启动 Java 时不会自动读取该文件。

### 2. 后端

```powershell
cd backend
.\mvnw.cmd -pl admin spring-boot:run
```

macOS / Linux 使用 `./mvnw -pl admin spring-boot:run`。默认端口 `8002`，健康检查地址：`http://localhost:8002/actuator/health`。

### 3. 前端

```powershell
cd frontend
npm ci
npm run dev
```

默认地址：`http://localhost:5173`；Vite 将 `/api` 转发至 `http://127.0.0.1:8002`。后端地址变化时，可在启动前设置环境变量 `VITE_API_TARGET`。

## 检查与构建

后端（工作目录 `backend/`）：

```powershell
.\mvnw.cmd -B -ntp clean verify
```

前端（工作目录 `frontend/`）：

```powershell
npm run typecheck
npm test
npm run build
```

前端构建输出到 `frontend/dist/`，后端 JAR 输出到 `backend/admin/target/`。测试、构建通过不等于真实模型、数据库迁移和浏览器全流程均已验收。

GitHub Actions 位于根目录 `.github/workflows/`，分别检查后端和前端。

## Docker 部署

```powershell
cd backend
Copy-Item .env.example .env
# 编辑 .env，设置数据库密码与模型凭据
docker compose up -d --build
```

该 Compose 部署 MySQL、Redis 和后端，不包含前端容器。首次使用空数据卷时自动初始化数据库；已有数据卷不会重新执行初始化脚本。部署前确认 `3306`、`6379`、`8002` 端口未被占用。

前端单独执行 `npm run build`，将 `frontend/dist/` 部署到静态服务器；配置 Vue Router 的 history 回退到 `index.html`，并将 `/api` 反向代理至后端。SSE 聊天路由应关闭代理缓冲并配置适当读取超时。

## 文档与许可

- [后端说明](backend/README.md)
- [设计与实施文档](backend/docs/)
- [MIT 许可证](LICENSE)

历史文档中的旧目录名称和技术方案仅供追溯；启动路径以本说明为准，功能状态以当前代码与验收结果为准。
