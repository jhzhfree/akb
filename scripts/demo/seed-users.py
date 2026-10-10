#!/usr/bin/env python3
"""Create the demo accounts used by the deployment showcase.

Every account shares one password so a demo audience can switch roles without
asking for credentials; ``admin`` is reset to the same password when it already
exists, so the console is reachable right after ``reset-storages.sh``.

Usage
-----
    python3 scripts/demo/seed-users.py
    python3 scripts/demo/seed-users.py --password '1qaz@WSX' --base-url http://127.0.0.1:8080
"""

from __future__ import annotations

import argparse
import sys
from typing import Any

import requests

# 演示账号：一个角色一个人，覆盖全部五类角色。姓名沿用数据集的虚构主体「蓝湖科技」。
DEMO_USERS = [
    {"username": "kb-admin", "displayName": "陈静（知识管理员）", "role": "KNOWLEDGE_ADMIN",
     "email": "chenjing@lanhu.demo", "note": "知识域全功能：生成（含连接器、裁决、衍生）+ 检索（含策略、评测）"},
    {"username": "kb-operator", "displayName": "李伟（知识运营员）", "role": "KNOWLEDGE_OPERATOR",
     "email": "liwei@lanhu.demo", "note": "简化知识生成：导入文档、查看知识对象、检索；不含裁决与衍生"},
    {"username": "kb-auditor", "displayName": "王芳（知识审核员）", "role": "KNOWLEDGE_AUDITOR",
     "email": "wangfang@lanhu.demo", "note": "知识运营员 + 知识裁决 + 知识测评"},
    {"username": "kb-viewer", "displayName": "赵敏（知识浏览员）", "role": "KNOWLEDGE_VIEWER",
     "email": "zhaomin@lanhu.demo", "note": "只读：知识工作台（总览 / 资产 / 版本 / 质量）与知识检索"},
]

ADMIN = {"username": "admin", "displayName": "系统管理员", "role": "ADMIN", "email": "admin@demo.local"}


class Client:
    def __init__(self, base_url: str) -> None:
        self.base_url = base_url.rstrip("/")
        self.session = requests.Session()

    def url(self, path: str) -> str:
        return self.base_url + path

    def unwrap(self, response: requests.Response) -> Any:
        if response.status_code >= 400:
            raise SystemExit("API %s %s 失败（%s）：%s"
                             % (response.request.method, response.request.path_url,
                                response.status_code, response.text[:300]))
        body = response.json()
        if isinstance(body, dict) and body.get("success") is False:
            raise SystemExit("API 返回失败：%s" % (body.get("message") or body))
        return body.get("data") if isinstance(body, dict) and "data" in body else body

    def login(self, username: str, password: str) -> dict[str, Any]:
        data = self.unwrap(self.session.post(self.url("/api/auth/login"),
                                             json={"username": username, "password": password}, timeout=30))
        token = (data or {}).get("token")
        if not token:
            raise SystemExit("登录成功但未返回 token")
        self.session.headers["X-Auth-Token"] = token
        return data

    def list_users(self) -> list[dict[str, Any]]:
        data = self.unwrap(self.session.get(self.url("/api/users"), timeout=30))
        return data if isinstance(data, list) else []

    def create_user(self, payload: dict[str, Any]) -> dict[str, Any]:
        return self.unwrap(self.session.post(self.url("/api/users"), json=payload, timeout=30))

    def reset_password(self, user_id: Any, password: str) -> None:
        self.unwrap(self.session.post(self.url("/api/users/%s/password" % user_id),
                                      json={"password": password}, timeout=30))


def main() -> int:
    parser = argparse.ArgumentParser(description="创建演示账号（默认口令 1qaz@WSX）")
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--admin-username", default=ADMIN["username"])
    parser.add_argument("--password", default="1qaz@WSX")
    args = parser.parse_args()

    client = Client(args.base_url)
    client.login(args.admin_username, args.password)
    print("已登录：%s" % args.admin_username)

    existing = {str(u.get("username")): u for u in client.list_users()}
    print("现有账号：%s" % ", ".join(sorted(existing)) or "（无）")

    for spec in DEMO_USERS:
        if spec["username"] in existing:
            client.reset_password(existing[spec["username"]].get("id"), args.password)
            print("· 已存在，重置口令：%-14s %s" % (spec["username"], spec["role"]))
            continue
        payload = {
            "username": spec["username"],
            "password": args.password,
            "displayName": spec["displayName"],
            "email": spec["email"],
            "role": spec["role"],
        }
        client.create_user(payload)
        print("· 已创建：%-14s %-18s %s" % (spec["username"], spec["role"], spec["note"]))

    # admin 放在最后：重置口令会撤销该账号的全部会话，之后当前 token 立即失效。
    admin = {str(u.get("username")): u for u in client.list_users()}.get(args.admin_username)
    if admin is not None:
        client.reset_password(admin.get("id"), args.password)
        print("· 已重置口令：%s" % args.admin_username)

    print("\n全部演示账号口令：%s" % args.password)
    print("%-14s %-18s %s" % ("用户名", "角色", "说明"))
    print("%-14s %-18s %s" % (args.admin_username, "ADMIN", "系统管理员：平台配置与账号治理"))
    for spec in DEMO_USERS:
        print("%-14s %-18s %s" % (spec["username"], spec["role"], spec["note"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
