# 写技能与站点案例的约定

## 只有一个技能

仓库只提供一个技能：`.agents/skills/deepseek-browser-use/`。站点经验不再单独做成技能，而是作为案例放在它的 `references/cases/` 下，由模型按需读取。这样宿主的技能列表里只有一条描述，入口提示词也只讲怎么用 dsb 操作浏览器。

```
.agents/skills/deepseek-browser-use/
  SKILL.md                 入口：dsb 使用规则、核心循环、常见情况、按需读取索引
  references/
    setup.md               环境配置：开源地址、安装 dsb、构建启动后端、更新
    *.md                   通用参考（命令、参数、读页面、可靠性、批量与 JS…）
    web-data.md            取数规范
    cases/<站点>.md        站点案例
    cases/<站点>/...       案例附带的脚本与复盘
```

DSH 按 `<项目根>/.dsh/skills` → `<项目根>/.agents/skills` → `customSkillDirs` → `~/.dsh/skills` → `~/.agents/skills` 扫描，只认 `<根>/<技能名>/SKILL.md` 这一层；在仓库里启动会话即可发现本技能。frontmatter 必须有 `name` 与 `description`，`name` 与目录名一致。

| 文件 | 作用 | 谁维护 |
| --- | --- | --- |
| `SKILL.md` | 入口提示词，每次触发都整体进上下文，**只放必读内容** | 跟着服务端一起改 |
| `references/*.md` | 通用参考，按需读取 | 跟着服务端一起改 |
| `references/cases/<站点>.md` | 某个站点「必须这么点」的经验，不写 frontmatter，以 `# 标题` 开头 | 跑完一次真实任务后补 |

新增案例时同时在 `SKILL.md` 的「站点案例」表里加一行，否则模型不知道它存在。案例引用通用参考写 `../xxx.md`，引用其他案例写 `<站点>.md`；附带脚本放在 `cases/<站点>/` 下，文中写明按技能目录解析绝对路径，不假定当前工作目录。

构建时 `SkillDocConsistencyTest`（`playwright-server/src/test/java/nexus/io/ai/browser/docs/`）校验入口和全部案例与服务端命令表的一致性。

### 安装与同步

`node scripts/sync-skills.mjs` 按宿主改写 frontmatter 后安装：`dsh`（`~/.dsh/skills`）、`deepseek-code`（`~/.deepseek-code/skills`）、`claude`（`~/.claude/skills/dsb-skills`，调用名 `dsb-skills:deepseek-browser-use`）、`codex`（`~/.codex/skills`）。插件清单位于 `.agents/.claude-plugin/plugin.json`。

- 同步前先读源与已安装文件并检查差异，保留双方已有改动；不要用 `--force` 覆盖非本脚本安装的同名技能。
- 清单记录的技能在仓库删除后，同步时移到目标旁的 `skill-backups/`，不会和新技能重复加载。
- 同步后用 `--status` 比较，确认新增的参考文件也已安装。

### 入口与参考的分工

1. **入口和参数表一起覆盖全部命令**：`everyCommandIsDocumented` 读 `SKILL.md` 加自动生成的 `references/parameters.md`，新方法由参数表覆盖；常用方法写进入口的核心循环或「常见情况」表。
2. **入口和案例里的单反引号命令名会被检查**；其余参考文件不检查，要自己对照 `CommandTable` 核对。

## 最重要的一条：单反引号 vs 双反引号

**测试把单反引号包起来的东西当作命令名检查**：

```
这个方法不存在 → `no_such_command`     ← 会被检查：命令表里没有它 → 测试失败
这是页面里的名字 → ``no_such_command``  ← 不会被检查
```

为什么要这样分：站点案例**必然**会大量提到 snake_case 的**非命令标识符**，而检查只能靠词形猜。没有这条约定时，每写一个站点案例都要把二十多处标识符改成「加点号 / 加 `#` / 加对象前缀」的写法——**让文档迁就测试**，而且这条规则没有任何地方写明，下一个人还会踩。

| 类别 | 例子 | 写法 |
| --- | --- | --- |
| Vue / 表单字段 | ``subject_name``、``socialcredit_code``、``business_license_stuff`` | 双反引号 |
| CSS 类名 | ``mall_invoice_dialog_container``、``qui_dialog``、``form_err`` | 双反引号（也可以写成 `.xxx` 的点号形式） |
| HTML id | ``dsh_up_0`` | 双反引号（也可以写成 `#dsh_up_0`） |
| URL 查询参数 | ``order_id`` | 双反引号 |
| 接口响应字段 | ``has_cname_record`` | 双反引号 |
| **「这些方法不存在」的反例** | ``get_frames()``、``switch_frame()`` | 双反引号 |
| **真要调的命令** | `click_element_by_index`、`get_browser_state` | 单反引号 |

> 反例那一类最容易被误判：文档里明确写「这个方法不存在」时，测试反而会因为「它不在命令表里」而报错，等于**把「记录一个已知缺失的能力」判成了错误**。用双反引号就对了。

站点案例没有 frontmatter，非命令标识符统一用双反引号：零配置、词法可判，视觉上也分得清「这是命令」与「这是页面里的名字」。

## 其余约定

1. **命令名必须真实存在**：入口 `SKILL.md` 和 `references/cases/*.md` 里的单反引号 snake_case 名字都会对着命令表查一遍。写错一个（例如把 `get_tabs` 写成 ``list_tabs``），模型照着发请求就会拿到「不支持的方法」。不知道有哪些命令时先 `list_methods`。
2. **`recipes/*.json` 里的命令名同样会被检查**：配方写错命令名要到 `run_recipe` 运行时才报错，所以构建阶段就过一遍。
3. **主技能必须覆盖全部命令**：命令表里的每个方法都要出现在 `SKILL.md` 或自动生成的 `references/parameters.md` 里（`everyCommandIsDocumented`）。
4. **不写本机绝对路径**：`E:\` / `D:\` 这类路径会让文档换台机器就失效。
5. **不写旧名字**：``get_dom_text``、`/api/v1/playwright`、``PlaywrightController``、``com.litongjava``、``project-nexus`` 都已被拉黑。
6. **症状导向**：常见症状写进入口的「常见情况」表，长尾问题写进 `references/pitfalls.md`。
7. **文档检查不需要打开真实站点**：CLI 示例通过 `--help` 核对；只有真的运行过的检查才能写「通过」。

## 站点案例建议的结构

按「能不能一次跑通」组织，而不是按命令顺序罗列：

1. **站点特征**：URL、技术栈（Vue 2 / ant-design / 跨域 iframe…）、登录态在哪份 profile 里；
2. **必须这么做的步骤**：每一步给出可直接复制的请求体；
3. **坑**：这个站点特有的假成功（点了返回 `ok` 但页面不动）、必须直调的组件方法、一次性 token 之类的；
4. **怎么确认这一步真的成了**：`data.changed` / `get_form_state` / `diff_dom_text` / 某个必然出现的文本。
