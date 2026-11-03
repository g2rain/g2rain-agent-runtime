# 构建、镜像与部署

## Maven API 发布与双工作流顺序

`publish-ghcr.yml` 在根目录执行 `mvn --batch-mode --no-transfer-progress clean install`，再在 Startup 模块执行 Jib 推送。镜像双标签成功后，`needs: publish` 的 job 调用本仓 `publish-maven.yml`；后者也支持手动 `workflow_dispatch`，所选提交必须属于 `origin/main` 历史，手动与自动调用通过同一并发组串行执行。

Maven 发布使用 `-pl g2rain-agent-runtime-api -am clean deploy`，Reactor 仅选中父 POM `com.g2rain:g2rain-agent-runtime:1.0.0` 与 API `com.g2rain:g2rain-agent-runtime-api:1.0.0`（含 sources），不发布 Biz 或 Startup。目标仓库为 `https://maven.pkg.github.com/g2rain/g2rain-agent-runtime`。保留 flatten 的 `resolveCiFriendliesOnly` 配置以解析版本占位符。

Maven 上传失败会使工作流失败，但先前推送的镜像不会回滚；两者不是事务。重复上传相同稳定版本可能冲突，不删除或覆盖既有版本，应按项目版本策略处理。

消费者需在 POM 配置上述 repository，并在 Maven settings 的同名 server 中安全注入认证。跨仓库使用 `GITHUB_TOKEN` 读取须向消费仓库授予包访问权限；个人读取使用具备 `read:packages` 的 PAT classic，通过环境变量注入 settings，禁止提交 Token。GitHub Maven Packages 需要认证，区别于 Public GHCR 的匿名拉取。发布仓库还需具有 Maven 包写权限；维护者需检查包访问授权及组织 Packages 策略。

本次本地离线 API verify 已确认 Reactor 范围，但在 `ModelHint.java` 编译时因当前解析到的 `g2rain-common` 缺少 `Strings.defaultIfBlank` 而失败；未运行远程 deploy，Maven 上传和消费者解析未验证。该失败需另行核对源码与依赖兼容性，不能由工作流变更解决。

## 构建与镜像边界

| 操作 | 命令或位置 | 已覆盖的范围 | 不代表 |
| --- | --- | --- | --- |
| 本地 Maven 验证 | `mvn clean verify` | Maven 验证生命周期与仓库测试 | 容器已构建、已推送或已部署。 |
| 本地容器构建 | `./build.sh 1.0.0` | 完整 Maven 构建与测试后，Jib 使用同一配置将镜像载入本地 Docker | 镜像已推送、可匿名拉取或在环境中可运行。需要 Docker daemon。 |
| CI 构建与测试 | tag 工作流中的 `./build.sh 1.0.0 push` | 完整 Maven 构建与测试，以及同一 Jib 镜像内容的构建 | 实际部署、在线演示或匿名拉取。 |
| GHCR 推送 | 同一 CI 的 Jib `push` | `ghcr.io/g2rain/g2rain-agent-runtime:1.0.0` 和 `sha-<完整提交 SHA>` | GitHub Release、`latest`、包公开、服务部署。 |
| 匿名拉取 | 包公开后 `docker pull ghcr.io/g2rain/g2rain-agent-runtime:1.0.0` | 包的公共可见性与拉取路径 | 服务配置正确、健康检查成功或生产部署。 |
| 在线演示 | 平台演示入口 | 演示入口的可访问性 | 本组件版本已发布或目标环境已经部署。 |
| 实际部署验证 | 目标环境的发布流程 | 环境依赖、路由、权限和健康检查 | 仅由本地或 CI 构建自动证明。 |

本项目采用 Jib，不使用 Dockerfile 或 `.dockerignore`。Jib 配置位于 `g2rain-agent-runtime-startup/pom.xml`：基础镜像为 `eclipse-temurin:25.0.4.1_1-jre`，入口为 `com.g2rain.agent.runtime.Application`，声明端口为 `8080`（运行时可由 `SERVER_PORT` 覆盖）。本地构建和 CI 推送复用该配置。OCI 元数据为仓库 source、Apache-2.0 license、title 和 Maven project version。

## GHCR 发布

默认镜像地址是 `ghcr.io/g2rain/g2rain-agent-runtime`。发布工作流仅监听 Git tag 推送；它会严格拒绝不是 `v<major>.<minor>.<patch>` 的 tag，并校验 tag 指向的提交已经在 `origin/main` 历史中、`docs/releases/v<版本>.md` 在该提交中且非空、以及 tag 去掉 `v` 后与根 `pom.xml` 的 Maven 项目版本一致。普通分支、Pull Request 和手动触发均不发布。

发布顺序：

1. 将版本、`docs/releases/v<版本>.md`、工作流和部署文档合入 `main`，并在合并后验证。
2. 在该已验证的 `main` 提交创建 `v<版本>` tag，推送 tag。
3. CI 使用 `GITHUB_TOKEN` 登录 GHCR，执行完整构建和测试，随后用 Jib 同时推送 `<版本>` 和 `sha-<完整 Git 提交 SHA>`。
4. 仅两个标签都成功推送后，Actions Summary 才会显示镜像地址和两个标签。

工作流权限仅为 `contents: read` 和 `packages: write`，不使用 PAT Secret；它不创建 GitHub Release、不部署服务，也不更新 `latest`。

首次成功推送后，维护者须在 GitHub Packages 的 **Package settings → Change visibility → Public** 将包设为公开；若组织管理员禁止更改，请联系管理员。已有 GHCR package 可能还需要在 **Package settings → Actions access** 中授予 `g2rain/g2rain-agent-runtime` 写权限。若收到 `denied`，先检查该访问设置和组织 Packages 策略，而不是仅以 `packages: write` 推断权限充分。

## 部署前检查

- 镜像版本、`sha-<完整提交 SHA>`、Git tag 与根 `pom.xml` 的 `<revision>` 必须相互一致；不使用 `latest`。
- Nacos namespace、配置分组、服务发现、MySQL、Redis 与依赖服务均须按环境隔离。
- 网关路由、服务身份、授权和 `GET /actuator/health` 健康检查须在目标环境验证。
- Agent Studio、Model Nexus、MCP Registry 的契约版本与可用性须在目标环境验证。
- 发布策略应包含失败回滚；有副作用的执行任务须评估滚动发布期间的恢复策略。

Jib 最终镜像包含启动模块的应用类、依赖和打包 resources；`g2rain-agent-runtime-biz` 的 MyBatis mapper XML、prompt 与 Lua 脚本作为 classpath 资源包含在依赖 JAR 中。已跟踪的 `g2rain-agent-runtime-startup/src/main/resources/application-dev.yml` 中有本地 MySQL、Redis、Nacos 默认凭据，`application-nacos.yml` 中有 Nacos 默认凭据；这些配置资源会进入应用 JAR，因而也会进入镜像。请在发布前评估与轮换这些值。生产密钥、Token、私有服务地址和凭据必须从平台 Secret、环境变量或受控 Nacos 配置注入，不得使用镜像内默认值。
