"""Contract resource packaging contract.

contracts/intranet-http.json 是后端与前端共用的判定规则真源，后端靠 pom 把 ../contracts
挂成资源目录读它。这件事有个静默失败模式：Maven 对不存在的资源目录只警告不打包，所以
构建上下文里没有 contracts 时，构建照样成功、jar 里却没这个文件——线上表现为
「Could not initialize class IntranetAddressRules」，从错误信息完全看不出是打包漏了。

踩过一次：backend/Dockerfile 的构建上下文是 backend/，pom 里的 ../contracts 在那里不存在。
这里把「backend 镜像的构建上下文必须包含仓库根」固化成检查。
"""
import sys
from pathlib import Path

import yaml

root = Path(__file__).resolve().parents[1]

COMPOSE_FILES = [
    "docker-compose.yml",
    "docker-compose.dev.yml",
    "deploy/docker-compose.prod.yml",
    "deploy/docker-compose.release.yml",
]

dockerfile = (root / "backend" / "Dockerfile").read_text(encoding="utf-8")
assert "COPY contracts" in dockerfile, "backend/Dockerfile 必须把 contracts/ 复制进构建上下文"

pom = (root / "backend" / "pom.xml").read_text(encoding="utf-8")
assert "../contracts" in pom, "backend/pom.xml 必须把 ../contracts 挂成资源目录"

checked = 0
for relative in COMPOSE_FILES:
    path = root / relative
    if not path.exists():
        continue
    services = (yaml.safe_load(path.read_text(encoding="utf-8")) or {}).get("services") or {}
    for name, service in services.items():
        build = service.get("build") if isinstance(service, dict) else None
        if not build:
            continue
        if not isinstance(build, dict):
            # backend 的字符串写法（build: ./backend）意味着上下文就是 backend/，contracts 进不来
            if str(build).replace("\\", "/").rstrip("/").split("/")[-1] != "backend":
                continue
            raise AssertionError(
                "%s 的 %s 用了 build: %s —— 后端镜像的构建上下文必须是仓库根，"
                "否则 contracts/ 进不了上下文，jar 里会缺 intranet-http.json" % (relative, name, build))
        if build.get("dockerfile") != "backend/Dockerfile":
            continue
        context = (path.parent / build["context"]).resolve()
        assert (context / "contracts" / "intranet-http.json").exists(), \
            "%s 的 %s 构建上下文 %s 里没有 contracts/intranet-http.json" % (relative, name, context)
        assert (context / "backend" / "Dockerfile").exists(), \
            "%s 的 %s 构建上下文 %s 里没有 backend/Dockerfile" % (relative, name, context)
        checked += 1

assert checked > 0, "没有检查到任何 backend 构建配置，脚本可能失效了"
print("contract resource packaging passed (%d 处 backend 构建配置)" % checked)
sys.exit(0)
