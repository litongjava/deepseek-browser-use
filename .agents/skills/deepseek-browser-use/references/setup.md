# 环境配置：安装 dsb 与启动后端

`dsb` 不可用、后端起不来或需要更新到最新代码时读本文。环境已就绪（`dsb health` 成功）时不需要读。

- 开源地址：https://github.com/litongjava/deepseek-browser-use（Gitee 镜像：https://gitee.com/ppnt/deepseek-browser-use）
- 文档站：https://deepseek-browser-use.io.nexus/
- 组成：`dsb/` 是 Go 客户端；`playwright-server/` 是 Java 后端，默认监听 `10049`；`.agents/skills/` 是本技能源文件。

## 1. 先检查，再安装

```bash
dsb --version            # 客户端是否在 PATH 上
dsb health               # 后端是否可达；本机不可达时会自动拉起已配置的后端并重试一次
dsb server status        # 仓库位置、候选 jar、pid、日志位置
```

`dsb health` 成功就直接回到主技能执行任务。只有缺什么才装什么。

## 2. 依赖

| 依赖 | 用途 | 检查 |
| --- | --- | --- |
| Git | 获取源码 | `git --version` |
| Go 1.24+ | 构建 `dsb` | `go version` |
| JDK 21+、Maven 3.8+ | 构建并运行后端 | `java -version`、`mvn -v` |
| Google Chrome（推荐） | 默认浏览器；没有时用 Playwright Chromium | `google-chrome --version` |

缺少依赖时优先用用户目录下的安装方式（解压 JDK/Go/Maven 到用户目录并加入 PATH），不要擅自 sudo、改系统配置或改全局 PATH；需要管理员权限时先说明原因并征得用户同意。

## 3. 获取源码并安装 dsb

```bash
git clone https://github.com/litongjava/deepseek-browser-use.git
cd deepseek-browser-use/dsb
go install .                 # 生成 $(go env GOPATH)/bin/dsb（Windows 为 dsb.exe）
cd ..
dsb --version
```

`dsb` 找不到时把 `$(go env GOPATH)/bin` 加入 PATH。已有仓库时先 `git pull` 再重装。

## 4. 构建并启动后端

在仓库根目录执行：

```bash
dsb server init --repo-dir .      # 记录仓库位置到 ~/.dsb/config.json，之后在任意目录可用
dsb server build                  # Maven 构建，产物归档到 .dsb-backend/releases/<commit>/backend.jar
dsb server start                  # 已在运行则复用
dsb health
dsb selftest --browser chrome     # 可选：本地自检页端到端验证，不依赖外网
```

也可直接运行发行包：`java -jar deepseek-browser-use-<版本>-<平台>.jar`，发行包内嵌 Chromium 和 Playwright driver。

起不来时看 `dsb server logs --lines 80`，按日志处理；常见原因是 JDK 版本不对、端口被占用、首次运行需下载 Playwright 浏览器资源。

## 5. 更新到最新代码

```bash
git -C <仓库> pull
cd <仓库>/dsb && go install .     # 客户端有变化时
dsb server build
dsb server restart --when-idle     # 等活动任务结束再重启；--force 才会中断别人的任务
```

`dsb server status` 会标出正在运行的 jar 和是否需要重启。

## 6. Linux 无显示器

`start` 默认显示窗口。服务没有 `DISPLAY` 时报 `DISPLAY_UNAVAILABLE`：

- 不需要人看页面：`dsb start --headless`。
- 需要用户在 VNC 中登录、扫码或过人机验证：先确认 `:1` 上有 X 服务（Xvfb/TigerVNC 等），再用新的任务 ID `dsb --id <新ID> start --display :1`。

## 7. 远端或多实例

```bash
dsb --host 10.0.0.5 --port 10049 health
dsb server target add lab --host 10.0.0.5 --port 10049 --note "实验机"
dsb --use lab health
```

远端后端需在远端机器上启动；上传本机文件用 `dsb upload`。同一台机器跑多个实例用不同 `--port`，各自独立 profile。环境变量：`DSB_BASE_URL`、`DSB_HOST`、`DSB_PORT`、`DSB_TASK_ID`、`DSB_SESSION`、`DSB_REPO_DIR`、`DSB_AUTO_START`。

## 8. 安装本技能到智能体

```bash
node scripts/sync-skills.mjs --all --dry-run   # 预览
node scripts/sync-skills.mjs deepseek-code claude  # 安装到指定宿主：dsh / deepseek-code / claude / codex
node scripts/sync-skills.mjs --status          # 检查是否与仓库一致
```

Claude Code 中调用名为 `dsb-skills:deepseek-browser-use`。
