# -*- coding: utf-8 -*-
"""本地凭据加载：scripts/baidu/.env（未入库）→ 环境变量。

为什么有这个东西：2026-10-01 那次 `git push origin main` 被 GitHub push protection
拦下（GH013），原因是 gen_eval_urls.py 里硬编码了 Aiven 线上库密码。
从此**源码里不再出现任何明文密钥**：凭据统一从环境变量读，
本地开发则在 scripts/baidu/.env 里写一行一条 KEY=VALUE（.gitignore 已排除 .env）。

约定的键：
    PGHOST / PGPORT / PGUSER / PGPASSWORD / PGDATABASE   线上库
    BAIDU_PUSH_TOKEN                                     百度普通收录准入密钥
    MP_SITE                                              站点
"""
import os

ENV_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".env")


def load_local_env(path=ENV_FILE):
    """把 .env 里的 KEY=VALUE 灌进 os.environ。已经存在的环境变量优先，不覆盖。"""
    if not os.path.exists(path):
        return
    with open(path, "r", encoding="utf-8") as fh:
        for raw in fh:
            line = raw.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, _, value = line.partition("=")
            key = key.strip()
            if key.startswith("export "):
                key = key[len("export "):].strip()
            value = value.strip().strip('"').strip("'")
            if key and value and not os.environ.get(key):
                os.environ[key] = value
