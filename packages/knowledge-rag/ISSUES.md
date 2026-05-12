# ISSUES.md — Knowledge-RAG 踩坑笔记

> 项目搭建过程中遇到的问题集，按"现象 → 根因 → 解决 → 复习要点"四段式整理。
> 复习时只看 **复习要点** 也能回忆起来。

---

## 目录

1. [Spring AI 1.0.0 starter 找不到（`pom:unknown`）](#1-spring-ai-100-starter-找不到pomunknown)
2. [`FATAL: role "dev" does not exist`（容器层假象）](#2-fatal-role-dev-does-not-exist容器层假象)
3. [`FATAL: role "dev" does not exist`（端口路由真相）](#3-fatal-role-dev-does-not-exist端口路由真相)
4. [多服务开发的优雅启停](#4-多服务开发的优雅启停)

---

## 1. Spring AI 1.0.0 starter 找不到（`pom:unknown`）

**现象**

```
Could not find artifact org.springframework.ai:spring-ai-openai-spring-boot-starter:pom:unknown
```

**根因**

Spring AI **1.0.0 GA** 重命名了所有 starter；M 系列的旧名字不在 BOM 里。Maven 解不出版本 → 错误信息显示 `unknown`。

| 类型 | 旧名（≤ 1.0.0-Mx） | 新名（1.0.0 GA） |
|---|---|---|
| Model | `spring-ai-{model}-spring-boot-starter` | `spring-ai-starter-model-{model}` |
| Vector Store | `spring-ai-{store}-store-spring-boot-starter` | `spring-ai-starter-vector-store-{store}` |
| MCP | `spring-ai-mcp-{type}-spring-boot-starter` | `spring-ai-starter-mcp-{type}` |
| Document Reader | `spring-ai-tika-document-reader` | 不变（不是 starter） |

**解决**

按上表替换 `artifactId`，然后强制刷新依赖：

```bash
mvn -U clean package
```

**复习要点**

- 看到 `version:pom:unknown` → 第一反应：**BOM 没管这个 artifact**（不是网络问题）
- 升级 Spring 生态库前必看官方 [upgrade-notes](https://docs.spring.io/spring-ai/reference/upgrade-notes.html)
- BOM 的代价：换 artifactId 时旧名字会"静默"失效，错误信息也不直白
- `mvn -U` = "force update"，刷新 BOM 缓存的标配开关

---

## 2. `FATAL: role "dev" does not exist`（容器层假象）

**现象**

`docker-compose.yml` 明明写了 `POSTGRES_USER: dev`，容器也是 healthy，应用却报：

```
FATAL: role "dev" does not exist
```

**根因**

Postgres 镜像（包括 `pgvector/pgvector`）的 `POSTGRES_USER` / `POSTGRES_PASSWORD` / `POSTGRES_DB` 三个环境变量 **只在数据目录为空时**才生效。

如果你之前用过同名命名卷（这里是 `pgdata`）跑过别的 Postgres 镜像，`PG_VERSION` 文件已经存在 → 镜像启动时直接跳过 `initdb` → env 全部被忽略 → 容器里只剩默认的 `postgres` 超级用户，没有 `dev`。

**解决**

```bash
docker-compose down -v        # -v 同时删除命名卷
docker-compose up -d
docker-compose exec postgres psql -U dev -d knowledge_rag -c '\conninfo'
# 期望输出：You are connected to database "knowledge_rag" as user "dev" ...
```

**复习要点**

- Postgres / MySQL / Mongo 等数据库镜像几乎都有"first-run init only"约定
- `docker-compose down` ≠ `docker-compose down -v`：前者保数据，后者连卷一起删
- 排查"配置似乎没生效"时，第一步永远是验证容器内部状态，而不是反复看 yaml

---

## 3. `FATAL: role "dev" does not exist`（端口路由真相）

**现象**

按 #2 的方案 `down -v` 重建后，**仍然**报同样的错。

**根因：宿主机有第二个 Postgres 抢占了 5432 的路由**

`lsof -nP -iTCP:5432 -sTCP:LISTEN` 显示：

```
com.docke   *:5432 (LISTEN)               ← Docker，通配绑定
postgres    127.0.0.1:5432 (LISTEN)       ← 本地 Postgres（Homebrew / Postgres.app）
postgres    [::1]:5432 (LISTEN)
```

操作系统的端口路由规则：**精确绑定（`127.0.0.1`）优先于通配绑定（`*`）**。Spring 连 `localhost:5432` 时，请求被送进本地 Postgres，根本到不了 Docker 容器。本地那个没有 `dev` 角色，于是报错。

**解决**

最稳妥：**Docker 改用 5433，避开本地 Postgres，谁都不动**。

```yaml
# docker-compose.yml
ports:
  - "5433:5432"

# application.yml
url: jdbc:postgresql://localhost:5433/knowledge_rag
```

```bash
docker-compose down && docker-compose up -d   # 不需要 -v，数据保留
lsof -nP -iTCP:5432,5433 -sTCP:LISTEN          # 确认 5433 = Docker、5432 = 本地
```

**复习要点**

- 同一端口能被多个进程"同时"监听，只要绑定地址不重叠（`*` vs `127.0.0.1` vs `[::1]`）
- 端口冲突排查口诀：`lsof -nP -iTCP:<port> -sTCP:LISTEN`
- macOS 上排查数据库类问题，永远先怀疑：是不是 Homebrew/Postgres.app 装过本地实例
- 学习/开发环境，优先 **换端口**，而不是 **杀进程**——副作用最小
- 同一个错误信息在不同根因下都成立，**不能因为"看上去一样"就断定原因相同**

---

## 4. 多服务开发的优雅启停

**问题**

后端开发涉及 Postgres、Redis、Ollama、前端 dev server、Spring Boot 等多个进程。怎么一键启动 + 优雅退出？

**心智模型：服务分两层**

| 层 | 例子 | 重启频率 | 工具 |
|---|---|---|---|
| 基础设施 | Postgres、Redis、Ollama daemon | 低（一天 ≤1 次） | `docker-compose` + `Makefile` |
| 会话进程 | Spring Boot、Next.js、临时调试脚本 | 高（每次编码一次） | `mprocs`（信号传播 + TUI） |

**为什么必须分层**

- 基础设施有状态、启动慢、可能跨项目共用 → 不该跟着每次 Ctrl+C 一起死
- 会话进程无状态、启动快、生命周期 = 编码会话 → 一个 supervisor 统一管理

**优雅关闭的信号链**

```
Ctrl+C
  ↓
mprocs (发 SIGINT/SIGTERM 给所有子进程，超时升级 SIGKILL)
  ↓
Spring Boot (server.shutdown: graceful)
  ↓
拒绝新请求 → 等待存量请求 → 关闭 HikariCP → JVM 退出
```

任何一环没配置好，链路就断了：

1. **Spring Boot 必须显式开启 graceful**：
   ```yaml
   server:
     shutdown: graceful
   spring:
     lifecycle:
       timeout-per-shutdown-phase: 20s
   ```
2. **bash `trap` 的常见坑**：`mvn` / `npm` 会 fork 子进程，必须用 `kill -TERM 0` 给**整个进程组**发信号，否则脚本退了 JVM 还活着
3. **Ollama 别放进会话层**：模型加载到显存要几十秒，按基础设施跑（`brew services start ollama`）

**推荐落地**

- `Makefile`：`infra-up` / `infra-down` / `dev` / `infra-nuke`
- `mprocs.yaml`：列出 backend、frontend，可选 ollama
- `application.yml`：开 `server.shutdown: graceful`

**复习要点**

- 多进程编排，先问"**哪些有状态、哪些无状态**"，再选工具
- supervisor 的核心价值是**正确的信号传播**，不是炫酷 TUI
- "优雅关闭"从来不是默认行为，链路上每一层都得显式配
- 自己写 bash trap 容易踩坑，能用 `mprocs` / `overmind` 就别 DIY

---

## 复习模式建议

- **30 秒速读**：只看每节的 **现象** 和 **复习要点**
- **5 分钟回忆**：盖住 **根因**，看现象自己说出原因
- **遇到新报错时**：搜本文档，确认是不是已知坑的变种
