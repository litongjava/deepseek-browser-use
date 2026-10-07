#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
把 myprofessor.cn 的评测视频页「增量」提交到百度搜索资源平台（普通收录）。

为什么要有这个脚本：百度对本站的配额是**按天、不累计**的，实测
    API 提交 / 手动提交：10 条/天（同一个池子）
    sitemap 提交：0 条/天（未实名或未备案账号会被限流，页面提示「填写主体备案号可提高上限」）
所以 476 条评测视频不可能一次提交完，必须有一个「今天提交一批、记下来、明天接着提交」
的台账。本脚本就是那张台账 + 提交器。

两种通道，按可用性自动选：
  1) sitemap 通道：把站点自己的 https://<site>/sitemap.xml 登记到资源平台（1 个文件 = 全部 URL）。
     配额够时**一次就能把 476 条全部带进去**，优先走它。
  2) API 通道：POST 到 http://data.zz.baidu.com/urls?site=<site>&token=<token>，
     每次最多 10 条、按天限额；接口回执里的 remain 就是今天还剩几条。

用法：
    # 看一眼今天还剩多少配额、还有多少条没提交
    python scripts/baidu/submit_eval_urls.py --status

    # 先干跑，看这一批会提交哪些 URL
    python scripts/baidu/submit_eval_urls.py --dry-run

    # 真提交（自动按 remain 截断）
    python scripts/baidu/submit_eval_urls.py --commit

    # 只提交前 N 条 / 只提交某个语言
    python scripts/baidu/submit_eval_urls.py --commit --limit 7 --lang zh

    # 一次性把整份 sitemap 登记上去（需要 sitemap 配额 > 0）
    python scripts/baidu/submit_eval_urls.py --sitemap --commit

台账默认落在 data/baidu-submit/ 下：
    state.json          已提交的 URL 与时间、每天用了多少配额
    pending-urls.txt    还没提交的 URL（按 --order 排序，一行一条）

依赖：psql（导出清单）+ 标准库（提交）。不依赖 deepseek-browser-use 服务，
只有 --sitemap 需要那台浏览器服务在跑（走 dsb 客户端点页面）。
"""
import argparse
import json
import os
import shutil
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime

from _localenv import ENV_FILE, load_local_env

load_local_env()

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
STATE_DIR = os.path.join(REPO, "data", "baidu-submit")
STATE_FILE = os.path.join(STATE_DIR, "state.json")
PENDING_FILE = os.path.join(STATE_DIR, "pending-urls.txt")

# Windows 控制台默认代码页（cp1252/936）打不出中文，这里直接钉死 UTF-8；
# 重定向到文件时也一样，保证日志可读。
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass

DEFAULT_SITE = "https://myprofessor.cn"
# 准入密钥不写死在源码里（硬编码会触发 GitHub push protection）：
# 从环境变量 BAIDU_PUSH_TOKEN 或本地未入库的 scripts/baidu/.env 读。
PUSH_ENDPOINT = "http://data.zz.baidu.com/urls"
# API 提交单次上限（接口硬限制 10 条）
API_BATCH_MAX = 10


def out(msg):
    sys.stdout.write(msg + "\n")


def load_state():
    if not os.path.exists(STATE_FILE):
        return {"site": DEFAULT_SITE, "submitted": {}, "days": {}, "sitemap": {}}
    with open(STATE_FILE, "r", encoding="utf-8") as fh:
        state = json.load(fh)
    state.setdefault("submitted", {})
    state.setdefault("days", {})
    state.setdefault("sitemap", {})
    return state


def daily_remain(state, today):
    """
    今天还能提交几条。

    百度不提供「只查配额」的接口（查一次就要推一条 URL），所以配额靠台账记：
    每次推送回执里的 remain 落盘，下一次用它算「今天已用」。当天没有记录时
    用 API_REMAIN_DEFAULT（站点新账号的默认额度 10 条/天）。
    还没跑过 --commit 的那一天，状态里没有 remain_after，返回 None 表示未知。
    """
    day = state["days"].get(today)
    if day and isinstance(day.get("remain_after"), int):
        return day["remain_after"]
    return None


def save_state(state):
    os.makedirs(STATE_DIR, exist_ok=True)
    tmp = STATE_FILE + ".tmp"
    with open(tmp, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(state, fh, ensure_ascii=False, indent=2)
        fh.write("\n")
    os.replace(tmp, STATE_FILE)


def gen_urls(site, lang, order):
    cmd = [sys.executable, os.path.join(HERE, "gen_eval_urls.py"), "--site", site, "--lang", lang]
    if order == "newest":
        cmd.append("--newest-first")
    proc = subprocess.run(cmd, capture_output=True, text=True,
                          encoding="utf-8", errors="replace")
    if proc.returncode != 0:
        sys.stderr.write(proc.stderr or "")
        raise SystemExit("生成 URL 清单失败")
    urls = [line.strip() for line in proc.stdout.splitlines() if line.strip()]
    return urls


def push(urls, site, token, timeout=60):
    """
    POST 一批 URL，返回 (http_status, dict)。

    坑：site 参数**不能**做百分号编码。写成 site=https%3A%2F%2Fmyprofessor.cn
    时接口会回 400 {"error":400,"message":"site init fail"}，看起来像配额用完了，
    其实只是它认不出站点。token 是纯字母数字，编不编码都一样。
    """
    if not token:
        raise SystemExit(
            "缺少百度准入密钥：设环境变量 BAIDU_PUSH_TOKEN，或在 %s 里写一行 "
            "BAIDU_PUSH_TOKEN=..." % ENV_FILE)
    body = "\n".join(urls).encode("utf-8")
    endpoint = "%s?site=%s&token=%s" % (PUSH_ENDPOINT, site, urllib.parse.quote(token, safe=""))
    req = urllib.request.Request(endpoint, data=body, method="POST")
    req.add_header("Content-Type", "text/plain")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8", "replace")
            return resp.status, json.loads(raw)
    except urllib.error.HTTPError as err:
        raw = err.read().decode("utf-8", "replace")
        try:
            return err.code, json.loads(raw)
        except ValueError:
            return err.code, {"raw": raw}


def cmd_status(args):
    state = load_state()
    urls = gen_urls(args.site, args.lang, args.order)
    done = state["submitted"]
    pending = [u for u in urls if u not in done]
    out("站点            : %s" % args.site)
    out("评测视频 URL 总数: %d" % len(urls))
    out("台账已提交      : %d" % len([u for u in urls if u in done]))
    out("待提交          : %d" % len(pending))
    today = datetime.now().strftime("%Y-%m-%d")
    used = state["days"].get(today, {}).get("api", 0)
    remain = daily_remain(state, today)
    out("今天(%s)已用 API 配额: %d 条" % (today, used))
    out("今天剩余配额    : %s" % ("未知（今天还没提交过）" if remain is None else "%d 条" % remain))
    out("sitemap 登记记录 : %s" % (json.dumps(state["sitemap"], ensure_ascii=False) or "无"))
    out("台账文件        : %s" % STATE_FILE)
    if pending:
        out("下一条待提交    : %s" % pending[0])
    return 0


def cmd_sitemap(args):
    """把站点 sitemap.xml 登记到资源平台的 sitemap 提交页。"""
    # dsb 已装在 PATH 上;找不到时退回仓库里的 Go 源码构建产物路径,便于没装 PATH 的机器
    dsb = shutil.which("dsb") or shutil.which("dsb.exe") or os.path.join(REPO, "dsb", "dsb.exe")
    sitemap_url = args.site.rstrip("/") + "/sitemap.xml"

    def dsb_run(*argv, summary=True):
        cmd = [dsb, "--port", str(args.port), "--id", str(args.task_id)] + list(argv)
        if summary:
            cmd.append("--summary")
        proc = subprocess.run(cmd, capture_output=True, text=True,
                              encoding="utf-8", errors="replace", cwd=REPO)
        return proc.returncode, (proc.stdout or "") + (proc.stderr or "")

    if args.dry_run:
        out("[dry-run] 会提交 sitemap 文件地址：%s" % sitemap_url)
        return 0

    page = "https://ziyuan.baidu.com/linksubmit/index?site=" + urllib.parse.quote(args.site, safe="")
    code, log = dsb_run("run", "go_to_url", "-p", "url=" + page)
    if code != 0:
        raise SystemExit("打开普通收录页失败：\n" + log)
    code, log = dsb_run("run", "click_element_by_selector",
                        "-p", "selector=#submit-method-type > li[tab-value=sitemap]")
    if code != 0:
        raise SystemExit("切到 sitemap 标签失败：\n" + log)
    code, log = dsb_run("run", "input_text_by_selector",
                        "-p", "selector=.submit-content textarea", "-p", "text=" + sitemap_url,
                        summary=False)
    if code != 0:
        raise SystemExit("填 sitemap 地址失败（多半是配额 0 时输入框被禁用）：\n" + log)
    code, log = dsb_run("run", "click_element_by_selector",
                        "-p", "selector=button:has-text('提交')")
    if code != 0:
        raise SystemExit("点提交失败：\n" + log)
    state = load_state()
    state["sitemap"][sitemap_url] = datetime.now().isoformat(timespec="seconds")
    save_state(state)
    out("已提交 sitemap：%s" % sitemap_url)
    out("请在资源平台页面上确认状态列的抓取结果。")
    return 0


def cmd_submit(args):
    state = load_state()
    state["site"] = args.site
    urls = gen_urls(args.site, args.lang, args.order)
    os.makedirs(STATE_DIR, exist_ok=True)

    done = state["submitted"]
    pending = [u for u in urls if u not in done]
    if args.limit:
        pending = pending[:args.limit]

    if not pending:
        out("没有待提交的 URL（台账里 %d 条都已提交）。" % len(urls))
        return 0

    with open(PENDING_FILE, "w", encoding="utf-8", newline="\n") as fh:
        fh.write("\n".join(pending) + "\n")

    # 按今天已知的剩余配额截断，避免推出 400 site init fail（超配额）
    today = datetime.now().strftime("%Y-%m-%d")
    remain_known = daily_remain(state, today)
    cap = API_BATCH_MAX
    if remain_known is not None:
        cap = min(cap, max(remain_known, 0))
    if cap <= 0:
        out("今天配额已用完（台账记录剩余 0 条）。明天再跑同一条命令即可，台账不会丢。")
        return 0
    batch = pending[:cap]

    if args.dry_run:
        out("[dry-run] 台账待提交 %d 条，本次会提交 %d 条：" % (len(pending), len(batch)))
        for u in batch:
            out("  " + u)
        out("落盘清单：%s" % PENDING_FILE)
        return 0

    status, payload = push(batch, args.site, args.token, timeout=args.timeout)
    out("百度推送回执 HTTP %s：%s" % (status, json.dumps(payload, ensure_ascii=False)))
    if status != 200 or payload.get("error"):
        raise SystemExit("推送失败（HTTP %s），台账未改动，稍后可原样重跑。" % status)

    success = int(payload.get("success") or 0)
    remain = payload.get("remain")
    now = datetime.now().isoformat(timespec="seconds")

    # 百度按「非重复」计数：success 可能小于提交条数。这里以接口为准，
    # 但整批都计入台账（重复提交同一条 URL 百度也会忽略，不会浪费配额）。
    for u in batch:
        done[u] = now
    day = state["days"].setdefault(today, {"api": 0})
    day["api"] = day.get("api", 0) + success
    day["remain_after"] = remain
    save_state(state)

    out("成功 %s 条，配额剩余 %s 条（接口回执）。" % (success, remain))
    out("台账：已提交 %d / %d，待提交 %d 条。"
        % (len([u for u in urls if u in done]), len(urls), len([u for u in urls if u not in done])))
    out("下次继续：python scripts/baidu/submit_eval_urls.py --commit")
    return 0


def cmd_quota(args):
    """探今天剩余配额：百度没有只读接口，只能真推一条 URL 再读回执里的 remain。"""
    state = load_state()
    urls = gen_urls(args.site, args.lang, args.order)
    if not urls:
        raise SystemExit("没有可用 URL")
    # 推第一条（重复提交同一条 URL 时 success 会是 0，但 remain 照样准确）
    _, payload = push([urls[0]], args.site, args.token, timeout=args.timeout)
    out("探测回执：%s" % json.dumps(payload, ensure_ascii=False))
    remain = payload.get("remain")
    if isinstance(remain, int):
        today = datetime.now().strftime("%Y-%m-%d")
        state["days"].setdefault(today, {"api": 0})["remain_after"] = remain
        save_state(state)
        out("台账已按回执更新：今天剩余 %d 条。" % remain)
    else:
        out("回执里没有 remain，配额未知。")
    return 0


def cmd_seed_remain(args):
    """手工登记今天的剩余配额（接口没返回 remain 时用）。"""
    state = load_state()
    today = datetime.now().strftime("%Y-%m-%d")
    state["days"].setdefault(today, {"api": 0})["remain_after"] = args.seed_remain
    save_state(state)
    out("已登记：%s 剩余配额 %d 条。" % (today, args.seed_remain))
    return 0


def cmd_mark(args):
    """把若干 URL 直接记成「已提交」——用于补记脚本外面手工推过的 URL。"""
    if not args.mark:
        raise SystemExit("--mark 后面要跟 URL")
    state = load_state()
    now = datetime.now().isoformat(timespec="seconds")
    for url in args.mark:
        state["submitted"][url] = now
    save_state(state)
    for url in args.mark:
        out("已补记：%s" % url)
    out("台账现有已提交 %d 条。" % len(state["submitted"]))
    return 0


def main():
    ap = argparse.ArgumentParser(description="把评测视频 URL 增量提交到百度普通收录")
    ap.add_argument("--site", default=os.environ.get("MP_SITE", DEFAULT_SITE))
    ap.add_argument("--token", default=os.environ.get("BAIDU_PUSH_TOKEN"),
                    help="百度准入密钥；默认取 BAIDU_PUSH_TOKEN 或 scripts/baidu/.env")
    ap.add_argument("--lang", choices=["zh", "en", "all"], default="all")
    ap.add_argument("--order", choices=["newest", "oldest"], default="newest",
                    help="提交顺序：默认先提交最新的作品")
    ap.add_argument("--limit", type=int, default=0, help="本次最多提交几条")
    ap.add_argument("--timeout", type=float, default=60)
    ap.add_argument("--port", type=int, default=10049, help="browser-use 服务端口（--sitemap 用）")
    ap.add_argument("--task-id", type=int, default=1001, help="browser-use 任务 id（--sitemap 用）")
    ap.add_argument("--status", action="store_true", help="只看台账与待提交数量")
    ap.add_argument("--quota", action="store_true", help="推一条 URL 探今天剩余配额")
    ap.add_argument("--seed-remain", type=int, default=None,
                    help="手工登记今天的剩余配额，例如 --seed-remain 3")
    ap.add_argument("--mark", nargs="*", default=None,
                    help="把 URL 补记为已提交（脚本外面手工推过的用它补台账）")
    ap.add_argument("--sitemap", action="store_true", help="走 sitemap 通道（需要 sitemap 配额）")
    ap.add_argument("--dry-run", action="store_true", help="只打印这一批会提交什么")
    ap.add_argument("--commit", action="store_true", help="真的提交")
    args = ap.parse_args()

    if args.status:
        return cmd_status(args)
    if args.mark:
        return cmd_mark(args)
    if args.seed_remain is not None:
        return cmd_seed_remain(args)
    if args.quota:
        return cmd_quota(args)
    if args.sitemap:
        if not args.commit and not args.dry_run:
            raise SystemExit("加 --commit 才会真的提交，或先 --dry-run 看看要提交什么")
        return cmd_sitemap(args)
    if not args.commit and not args.dry_run:
        raise SystemExit("加 --commit 才会真的提交，或先 --dry-run 看看要提交什么")
    return cmd_submit(args)


if __name__ == "__main__":
    raise SystemExit(main())
