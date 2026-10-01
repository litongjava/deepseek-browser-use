# 把 myprofessor.cn 的评测视频提交到百度搜索资源平台

站点：<https://myprofessor.cn> ｜ 评测库入口：<https://myprofessor.cn/eval>
本文记录的是**实测到的配额现状**与**本项目里现成的两个脚本**，不是百度官方文档的转述。

---

## 一、要提交的是什么

「评测视频」= 评测库里的每道题及其讲解视频，数据源只有一张表 `ef_eval_question`
（与后端 `EvalQuestionService` 的可见性条件一致）：

```sql
select count(*) from ef_eval_question where status = 2 and deleted = 0 and is_public = 1;
-- 476
```

URL 规则与站点自己的 `sitemap.xml` 完全一致（`myprofessor-frontend/app/routes/sitemap[.]xml.ts`）：

| 语言 | URL 形态 | 条数 |
| --- | --- | --- |
| 中文 | `https://myprofessor.cn/eval/{id}` | 238 |
| 英文 | `https://myprofessor.cn/en/eval/{id}` | 238 |
| 合计 | | **476** |

线上核对过：`https://myprofessor.cn/sitemap.xml` 里 `<loc>` 共 1019 条，其中 `/eval/` **476** 条，与库里一致。

---

## 二、实测到的配额（2026-10-01，账号 李通2410438230）

| 通道 | 页面位置 | 实测配额 | 说明 |
| --- | --- | --- | --- |
| API 提交 | 普通收录 → API提交 | **10 条/天**，不累计 | 接口回执里的 `remain` 就是当天剩余，用完就回 400 |
| 手动提交 | 普通收录 → 手动提交 | 与 API **共用**同一个 10 条/天 | 页面原话：「API提交和手动提交共享配额」 |
| sitemap 提交 | 普通收录 → sitemap | **0 条/天** | 「今日提交上限：0条 今日提交余额：0条」，输入框禁用、提交按钮禁用 |
| 快速抓取 | 快速抓取 | 无权限 | 「当前站点暂无快速抓取权限」 |

结论：**靠 API 提交 476 条要 48 天**（476 ÷ 10）。
唯一能一次做完的路是 sitemap 通道，但它现在被封在 0 条。

### 为什么 sitemap 是 0

百度官方公告《关于回收网站提交配额的通知》
（<https://ziyuan.baidu.com/college/videoinfo?id=3531>）说得很明确：
针对**非实名账户内站点、低质站点**关停 sitemap 提交能力并下调 API 每日额度。
页面上给的提升办法有两条：

1. 「**填写站点的主体备案号**，可以提高每日提交上限」—— 本账号已于 2026-10-01 填写
   `沪ICP备2026010870号`（站点属性 → 主体备案号，状态已变为「已填写」）；
   填完当场复查 sitemap 页，仍是 0 条 —— 说明这一项需要平台侧复核，不是即时生效。
2. 账号**实名认证**（百度 App → 我的 → 设置 → 账号管理 → 身份认证）与站点质量整改后，
   通过 <https://ziyuan.baidu.com/feedback/index> 反馈申请恢复权益。

---

## 三、本项目里的两个脚本

都在 `scripts/baidu/` 下，只用标准库 + `psql`：

### 1. `gen_eval_urls.py` —— 生成清单

```powershell
python scripts\baidu\gen_eval_urls.py --newest-first --with-label --out urls.txt
# 评测视频合计 476 条（中文 238 / 英文 238），站点 https://myprofessor.cn
```

| 参数 | 作用 |
| --- | --- |
| `--lang zh\|en\|all` | 只导出某一语言（默认全部） |
| `--newest-first` / `--oldest-first` | 排序，默认按 id 升序 |
| `--with-label` | 每行后面跟标题，便于人工核对 |
| `--out 文件` | 写文件；不传就打屏 |

库连接默认取 `myprofessor-backend/my.txt` 那套线上库（`118.31.175.40:1032`），
可用 `PGHOST/PGPORT/PGUSER/PGPASSWORD/PGDATABASE` 覆盖。

### 凭据放在哪（**源码里没有任何明文密钥**）

两个脚本原本把线上库密码和百度准入密钥硬编码在 `.py` 里，2026-10-01 因此被
GitHub push protection 拦下（`GH013: Push cannot contain secrets`）。
现在它们统一这样取凭据，优先级从高到低：

1. 命令行参数（`--token`）；
2. 环境变量：`PGHOST / PGPORT / PGUSER / PGPASSWORD / PGDATABASE / BAIDU_PUSH_TOKEN / MP_SITE`；
3. 本地文件 `scripts/baidu/.env`（一行一条 `KEY=VALUE`，`.gitignore` 已排除 `.env`，**永远不要提交**）。

`scripts/baidu/.env` 长这样（值换成自己的）：

```ini
PGHOST=118.31.175.40
PGPORT=1032
PGUSER=postgres
PGPASSWORD=***
PGDATABASE=defaultdb
BAIDU_PUSH_TOKEN=***
```

缺 `PGPASSWORD` / `BAIDU_PUSH_TOKEN` 时脚本会**直接报错并提示去 .env 里补**，
不会静默用一个错的默认值去连库。

### 2. `submit_eval_urls.py` —— 带台账的增量提交器

台账落在 `data/baidu-submit/state.json`（已提交的 URL + 每天用了多少配额）
和 `data/baidu-submit/pending-urls.txt`（还没提交的 URL）。

```powershell
# 今天还剩多少配额、还有多少条没提交
python scripts\baidu\submit_eval_urls.py --status

# 干跑：看这一批会提交什么（不消耗配额）
python scripts\baidu\submit_eval_urls.py --dry-run

# 真提交：按台账里记的「今天剩余配额」自动截断
python scripts\baidu\submit_eval_urls.py --commit

# 只提交中文 / 限制条数
python scripts\baidu\submit_eval_urls.py --commit --lang zh --limit 5

# 走 sitemap 通道（需要 sitemap 配额 > 0；走 dsb 客户端点页面）
python scripts\baidu\submit_eval_urls.py --sitemap --dry-run
python scripts\baidu\submit_eval_urls.py --sitemap --commit
```

配额是「按天不累计」的，所以**每天跑一次 `--commit` 就行**，台账会自己接着往下走，
重复跑同一天不会重复消耗（台账里记了 `remain_after`）。

两个配套参数：

- `--seed-remain N`：手工登记今天还剩 N 条（接口没回 `remain` 时用）
- `--mark URL...`：把脚本外面手工推过的 URL 补记进台账

### 踩过的坑（都写进代码注释了）

1. **`site` 参数不能做百分号编码**。写成 `site=https%3A%2F%2Fmyprofessor.cn` 时接口回
   `400 {"error":400,"message":"site init fail"}`，看起来特别像「配额用完了」，
   其实只是它认不出站点。`urllib.parse.urlencode` 默认就会编码，必须手工拼。
2. **超配额时 `data.zz.baidu.com` 回的是 400 且 body 可能为空**，不要按「HTTP 400 = 参数错」处理。
3. 页面上的**准入密钥是按站点选的**：切站点下拉框之后要重新读一次 token
   （本账号两个站点当前取到的是同一个值，但别依赖这个巧合）。

---

## 四、当前进度

| 日期 | 动作 | 结果 |
| --- | --- | --- |
| 2026-10-01 | 填写主体备案号 `沪ICP备2026010870号` | ✅ 站点属性已显示「已填写」 |
| 2026-10-01 | API 提交最新 5 条中文评测视频 | ✅ 台账已提交 5 / 476，当日配额用尽（`remain: 0`） |
| 2026-10-01 | sitemap 提交 | ❌ 配额 0 条，输入框与提交按钮均禁用 |

已提交的 5 条：

```
https://myprofessor.cn/eval/691366327052366063   解释哈蒙德假说
https://myprofessor.cn/eval/691366327052366062   解释分子轨道理论
https://myprofessor.cn/eval/691366327052366061   解释玻恩–哈伯循环
https://myprofessor.cn/eval/691366327052366060   解释伍德沃德–霍夫曼规则
https://myprofessor.cn/eval/691366327052366059   解释艾林方程
```

剩下的 471 条，按下列任一方式推进：

- **sitemap 配额恢复后**：`python scripts\baidu\submit_eval_urls.py --sitemap --commit`，一次带完 476 条。
- **配额没恢复**：每天 `python scripts\baidu\submit_eval_urls.py --commit`，10 条/天，约 48 天。
  可以挂进 Windows 任务计划程序（每天固定时间跑 `scripts\baidu\daily-submit.cmd`）。

> 站点自己的 `sitemap.xml` 一直在正常输出（`/eval/` 476 条 + `/player/` 若干），
> 所以**即使一条都不主动提交**，百度蜘蛛也会通过 sitemap 自己发现这些页面 ——
> 主动提交只是把「被发现」提前，不是「能不能被收录」的前提。
