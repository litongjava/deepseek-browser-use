#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
从线上库把「评测视频」的页面 URL 全部导出成提交清单。

数据源就是评测列表页/详情页唯一的那张表：ef_eval_question。
可见性条件与后端 EvalQuestionService.VISIBLE_CONDITION 保持一致：
    status = 2（生成成功） and deleted = 0 and is_public = 1

URL 规则与 sitemap.xml 一致（myprofessor-frontend/app/routes/sitemap[.]xml.ts）：
    中文题 -> https://<site>/eval/<id>
    英文题 -> https://<site>/en/eval/<id>

用法：
    python scripts/baidu/gen_eval_urls.py                 # 打印清单
    python scripts/baidu/gen_eval_urls.py --out urls.txt  # 写入文件
    python scripts/baidu/gen_eval_urls.py --lang zh|en|all
    python scripts/baidu/gen_eval_urls.py --newest-first | --oldest-first

库连接信息（默认取 myprofessor-backend/my.txt 里那套线上库；可用环境变量覆盖）：
    PGHOST / PGPORT / PGUSER / PGPASSWORD / PGDATABASE

密码**不写死在源码里**（硬编码曾导致 GitHub push protection 拒推）：
从环境变量 PGPASSWORD 读，或写进本地未入库的 scripts/baidu/.env。
"""
import argparse
import os
import subprocess
import sys

from _localenv import ENV_FILE, load_local_env

load_local_env()

DEFAULT_HOST = "118.31.175.40"
DEFAULT_PORT = "1032"
DEFAULT_USER = "postgres"
DEFAULT_DB = "defaultdb"

DEFAULT_SITE = "https://myprofessor.cn"

# Windows 控制台默认代码页打不出中文，钉死 UTF-8（清单文件本身也一直是 UTF-8）
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass

SQL = """
select id, language, coalesce(nullif(title, ''), left(question, 60)) as label, update_time, create_time
from ef_eval_question
where status = 2 and deleted = 0 and is_public = 1
order by id
"""


def run_psql(sql):
    env = dict(os.environ)
    password = env.get("PGPASSWORD")
    if not password:
        raise SystemExit(
            "缺少线上库密码：设环境变量 PGPASSWORD，或在 %s 里写一行 PGPASSWORD=..."
            % ENV_FILE)
    env["PGPASSWORD"] = password
    # 输出里有中文标题，必须钉死编码，否则 Windows 默认代码页会在解码时炸掉
    env["PGCLIENTENCODING"] = "UTF8"
    cmd = [
        "psql",
        "-h", env.get("PGHOST") or DEFAULT_HOST,
        "-p", env.get("PGPORT") or DEFAULT_PORT,
        "-U", env.get("PGUSER") or DEFAULT_USER,
        "-d", env.get("PGDATABASE") or DEFAULT_DB,
        "-t", "-A", "-F", "\t",
        "-c", sql,
    ]
    proc = subprocess.run(cmd, env=env, capture_output=True, text=True,
                          encoding="utf-8", errors="replace")
    if proc.returncode != 0:
        sys.stderr.write(proc.stderr)
        raise SystemExit("psql 退出码 %d" % proc.returncode)
    return [line.split("\t") for line in proc.stdout.splitlines() if line.strip()]


def lang_of(raw, site):
    """与前端 videoLangOf 同义：英文题进 /en/eval，其余进 /eval。"""
    return "en" if (raw or "").strip().lower().startswith("en") else "zh"


def url_of(row_id, lang, site):
    return "%s/en/eval/%s" % (site, row_id) if lang == "en" else "%s/eval/%s" % (site, row_id)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--site", default=os.environ.get("MP_SITE", DEFAULT_SITE))
    ap.add_argument("--lang", choices=["zh", "en", "all"], default="all")
    ap.add_argument("--out", default=None, help="写入文件；不传就打印")
    ap.add_argument("--newest-first", action="store_true")
    ap.add_argument("--oldest-first", action="store_true")
    ap.add_argument("--with-label", action="store_true", help="输出 URL<TAB>标题，便于人工核对")
    args = ap.parse_args()

    rows = run_psql(SQL)
    if args.newest_first:
        rows = list(reversed(rows))

    out_lines = []
    counts = {"zh": 0, "en": 0}
    for row in rows:
        if len(row) < 3:
            continue
        row_id, raw_lang, label = row[0], row[1], row[2]
        lang = lang_of(raw_lang, args.site)
        if args.lang != "all" and lang != args.lang:
            continue
        counts[lang] += 1
        line = url_of(row_id, lang, args.site)
        if args.with_label:
            line += "\t" + label
        out_lines.append(line)

    total = len(out_lines)
    sys.stderr.write("评测视频合计 %d 条（中文 %d / 英文 %d），站点 %s\n"
                     % (total, counts["zh"], counts["en"], args.site))

    text = "\n".join(out_lines) + ("\n" if out_lines else "")
    if args.out:
        with open(args.out, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(text)
        sys.stderr.write("已写入 %s\n" % args.out)
    else:
        sys.stdout.write(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
