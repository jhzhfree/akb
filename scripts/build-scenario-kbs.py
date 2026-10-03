#!/usr/bin/env python3
"""Build the three demo knowledge bases from the scenario datasets in ``dataset/``.

The script drives the whole lifecycle through the public console APIs only, so it can be
re-run against a running stack (docker compose or a deployed environment):

    login/token -> create knowledge base -> batch upload -> poll batch until every file
    reaches a terminal state -> apply the scenario schema -> publish the schema ->
    create the scenario retrieval profile -> activate it -> (optional) import the
    scenario evaluation set -> write a build report.

Usage
-----
    python3 scripts/build-scenario-kbs.py                       # all three scenarios
    python3 scripts/build-scenario-kbs.py --scenario ops        # one scenario
    python3 scripts/build-scenario-kbs.py --username admin --password <pwd>
    python3 scripts/build-scenario-kbs.py --token <token>
    python3 scripts/build-scenario-kbs.py --skip-upload         # only schema + profile
    python3 scripts/build-scenario-kbs.py --no-wait             # submit and exit

Credentials are read from ``--token`` / ``KOS_TOKEN`` first, then ``--username`` +
``--password`` (or ``KOS_ADMIN_USER`` / ``KOS_ADMIN_PASSWORD``); when the backend runs with
authentication disabled no credential is needed at all.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path
from typing import Any

import requests

REPO_ROOT = Path(__file__).resolve().parent.parent
DATASET_DIR = REPO_ROOT / "dataset"
SCENARIO_DIR = DATASET_DIR / "_scenarios"

SCENARIOS = {
    "ops": ("ops-knowledge", "运维知识库"),
    "policy": ("company-policy", "公司制度知识库"),
    "eng": ("engineering-workspace", "工程协同知识库"),
}

TERMINAL_STATES = {"SUCCEEDED", "SUCCESS", "FAILED", "CANCELLED", "CANCELED", "SKIPPED", "DONE"}


class Client:
    """Thin console-API client: JSON verbs plus one multipart batch upload."""

    def __init__(self, base_url: str, token: str | None) -> None:
        self.base_url = base_url.rstrip("/")
        self.session = requests.Session()
        if token:
            self.session.headers["X-Auth-Token"] = token

    # ------------------------------------------------------------------ auth
    def _url(self, path: str) -> str:
        return self.base_url + path

    def login(self, username: str, password: str) -> str:
        response = self.session.post(self._url("/api/auth/login"), json={"username": username, "password": password}, timeout=30)
        payload = self._unwrap(response)
        token = None
        for key in ("token", "accessToken", "access_token", "idToken"):
            value = payload.get(key) if isinstance(payload, dict) else None
            if isinstance(value, str) and value:
                token = value
                break
        if token is None and isinstance(payload, dict):
            nested = payload.get("user") or payload.get("session") or {}
            token = nested.get("token")
        if not token:
            raise SystemExit("登录成功但未返回 token，请改用 --token 传入")
        self.session.headers["X-Auth-Token"] = token
        return token

    # ------------------------------------------------------------------ verbs
    def _unwrap(self, response: requests.Response) -> Any:
        if response.status_code >= 400:
            raise SystemExit("API %s %s 失败（%s）：%s" % (response.request.method, response.request.path_url, response.status_code, response.text[:400]))
        body = response.json()
        if isinstance(body, dict) and body.get("success") is False:
            raise SystemExit("API 返回失败：%s" % (body.get("message") or body))
        return body.get("data") if isinstance(body, dict) and "data" in body else body

    def get(self, path: str, **params: Any) -> Any:
        return self._unwrap(self.session.get(self._url(path), params=params or None, timeout=60))

    def post(self, path: str, payload: Any | None = None, **params: Any) -> Any:
        return self._unwrap(self.session.post(self._url(path), json=payload if payload is not None else {}, params=params or None, timeout=120))

    def put(self, path: str, payload: Any | None = None) -> Any:
        return self._unwrap(self.session.put(self._url(path), json=payload if payload is not None else {}, timeout=120))

    def upload_batch(self, kb_id: int, files: list[Path], authority: float | None, permission_tags: str | None) -> Any:
        form: list[tuple[str, tuple[str, bytes, str]]] = []
        for path in files:
            form.append(("files", (path.name, path.read_bytes(), "text/markdown")))
        data = {"knowledgeBaseId": str(kb_id)}
        if authority is not None:
            data["authorityScore"] = str(authority)
        if permission_tags:
            data["permissionTags"] = permission_tags
        response = self.session.post(self._url("/api/knowledge/documents/batch-upload"), files=form, data=data, timeout=300)
        return self._unwrap(response)


# ---------------------------------------------------------------------- steps
def ensure_base(client: Client, scenario_key: str, schema: dict[str, Any]) -> dict[str, Any]:
    """Create the knowledge base when it does not exist yet; reuse it otherwise."""
    meta = schema.get("knowledgeBase") or {}
    name = meta.get("name") or SCENARIOS[scenario_key][1]
    for base in client.get("/api/knowledge/bases") or []:
        if isinstance(base, dict) and base.get("name") == name:
            print("  · 复用已存在的知识库：%s（id=%s）" % (name, base.get("id")))
            return base
    created = client.post("/api/knowledge/bases", {
        "name": name,
        "description": meta.get("description") or "",
        "visibility": meta.get("visibility") or "PUBLIC",
    })
    print("  · 已创建知识库：%s（id=%s）" % (name, created.get("id")))
    return created


def upload_scenario(client: Client, kb_id: int, files: list[Path], batch_size: int, authority: float | None,
                    permission_tags: str | None, wait: bool, timeout: int) -> list[dict[str, Any]]:
    batches: list[dict[str, Any]] = []
    for start in range(0, len(files), batch_size):
        chunk = files[start:start + batch_size]
        result = client.upload_batch(kb_id, chunk, authority, permission_tags)
        code = result.get("batchCode") or result.get("batch") or result.get("code")
        print("  · 已提交批次 %s（%d 个文件）" % (code, len(chunk)))
        batches.append({"batchCode": code, "files": [p.name for p in chunk], "status": result})
        if not wait or not code:
            continue
        batches[-1]["final"] = poll_batch(client, code, timeout)
    return batches


def poll_batch(client: Client, code: str, timeout: int) -> dict[str, Any]:
    deadline = time.time() + timeout
    last = ""
    while time.time() < deadline:
        status = client.get("/api/knowledge/documents/batch/%s" % code)
        total = status.get("total") or status.get("totalCount") or 0
        done = status.get("processedCount") or status.get("processed") or status.get("finishedCount") or 0
        failed = status.get("failedCount") or status.get("failed") or 0
        state = str(status.get("status") or "")
        line = "    · 批次 %s：%s/%s 已完成，失败 %s，状态 %s" % (code, done, total, failed, state)
        if line != last:
            print(line)
            last = line
        if state and state.upper() in TERMINAL_STATES:
            return status
        if total and done >= total:
            return status
        time.sleep(10)
    print("    · 批次 %s 等待超时（%ss），稍后可用 batchCode 查询" % (code, timeout))
    return {"status": "TIMEOUT"}


def apply_schema(client: Client, kb_id: int, schema: dict[str, Any]) -> dict[str, Any]:
    applied: dict[str, list[str]] = {}
    for section in ("knowledgeTypes", "entityTypes", "relationTypes"):
        applied[section] = []
        for item in schema.get(section) or []:
            body = {
                "kind": item["kind"],
                "typeCode": item["typeCode"],
                "label": item.get("label") or item["typeCode"],
                "description": item.get("description") or "",
                "status": item.get("status") or "ACTIVE",
            }
            if item.get("properties"):
                body["properties"] = item["properties"]
            try:
                client.post("/api/knowledge/schema/types", body, kb=kb_id)
                applied[section].append(item["typeCode"])
            except SystemExit as exc:
                print("    ! 类型 %s 写入失败：%s" % (item["typeCode"], exc))
    published = None
    try:
        published = client.post("/api/knowledge/schema/publish", {}, kb=kb_id)
    except SystemExit as exc:
        print("    ! schema 发布失败：%s" % exc)
    print("  · schema：知识类型 %d / 实体类型 %d / 关系类型 %d，发布：%s" % (
        len(applied["knowledgeTypes"]), len(applied["entityTypes"]), len(applied["relationTypes"]), "已发布" if published else "跳过"))
    return {"applied": applied, "publish": published}


def apply_profile(client: Client, profile: dict[str, Any], activate: bool) -> dict[str, Any]:
    name = profile["name"]
    payload = {"name": name, "label": profile.get("label") or name, "policy": profile["policy"]}
    result = None
    try:
        result = client.post("/api/knowledge/settings/query-policy/profiles", payload)
    except SystemExit as exc:
        # 已存在同名策略时改为覆盖更新
        print("    · 新建策略失败（%s），尝试更新已存在的同名策略" % str(exc)[:120])
        result = client.put("/api/knowledge/settings/query-policy/profiles/%s" % requests.utils.quote(name), {"policy": profile["policy"]})
    activated = None
    if activate:
        try:
            activated = client.post("/api/knowledge/settings/query-policy/profiles/%s/activate" % requests.utils.quote(name))
        except SystemExit as exc:
            print("    ! 策略激活失败：%s" % exc)
    print("  · 检索策略「%s」已写入%s" % (name, "并激活" if activated else "（未激活）"))
    return {"created": result, "activated": activated}


def import_eval(client: Client, kb_id: int, eval_file: Path) -> Any:
    cases = json.loads(eval_file.read_text(encoding="utf-8"))
    created = client.post("/api/knowledge/evaluations/datasets/%s" % kb_id, {
        "name": cases.get("name") or eval_file.stem,
        "description": cases.get("description") or "",
    })
    code = created.get("code") or created.get("datasetCode") or created.get("id")
    imported = 0
    for case in cases.get("cases") or []:
        try:
            client.post("/api/knowledge/evaluations/datasets/%s/cases" % code, case)
            imported += 1
        except SystemExit as exc:
            print("    ! 评测用例导入失败：%s" % str(exc)[:120])
    print("  · 评测集「%s」：%d 条用例" % (code, imported))
    return {"dataset": created, "imported": imported}


# ---------------------------------------------------------------------- main
def load_json(path: Path) -> dict[str, Any]:
    if not path.exists():
        raise SystemExit("缺少配置文件：%s" % path)
    return json.loads(path.read_text(encoding="utf-8"))


def collect_files(directory: Path) -> list[Path]:
    return sorted(p for p in directory.glob("*.md") if p.is_file())


def main() -> int:
    parser = argparse.ArgumentParser(description="按场景数据集构建知识库（创建 / 批量导入 / schema / 检索策略）")
    parser.add_argument("--base-url", default=os.environ.get("KOS_BASE_URL", "http://localhost:8080"))
    parser.add_argument("--token", default=os.environ.get("KOS_TOKEN"))
    parser.add_argument("--username", default=os.environ.get("KOS_ADMIN_USER", "admin"))
    parser.add_argument("--password", default=os.environ.get("KOS_ADMIN_PASSWORD"))
    parser.add_argument("--scenario", default="all", choices=["all", *SCENARIOS.keys()])
    parser.add_argument("--batch-size", type=int, default=10, help="每批上传的文件数")
    parser.add_argument("--authority", type=float, default=0.9, help="权威分（0–1）")
    parser.add_argument("--wait-timeout", type=int, default=1800, help="单批次最长等待秒数")
    parser.add_argument("--no-wait", action="store_true", help="提交批次后立即返回，不轮询")
    parser.add_argument("--skip-upload", action="store_true", help="跳过文档导入，只做 schema 与策略")
    parser.add_argument("--skip-schema", action="store_true")
    parser.add_argument("--skip-profile", action="store_true")
    parser.add_argument("--no-activate", action="store_true", help="写入策略但不切换为生效策略")
    parser.add_argument("--with-eval", action="store_true", help="同时导入场景评测用例")
    args = parser.parse_args()

    client = Client(args.base_url, args.token)
    if not args.token and args.password:
        client.login(args.username, args.password)

    selected = list(SCENARIOS.keys()) if args.scenario == "all" else [args.scenario]
    report: dict[str, Any] = {"baseUrl": args.base_url, "finishedAt": None, "scenarios": {}}

    for key in selected:
        directory_name, fallback_name = SCENARIOS[key]
        source_dir = DATASET_DIR / directory_name
        schema = load_json(SCENARIO_DIR / ("%s.schema.json" % directory_name))
        profile_path = SCENARIO_DIR / ("%s.profile.json" % directory_name)
        files = collect_files(source_dir)
        print("\n== %s（%s，%d 个文档）==" % (fallback_name, directory_name, len(files)))
        if not files:
            print("  ! 目录为空，跳过：%s" % source_dir)
            continue

        entry: dict[str, Any] = {"directory": str(source_dir), "fileCount": len(files)}
        base = ensure_base(client, key, schema)
        kb_id = base.get("id")
        entry["knowledgeBase"] = {"id": kb_id, "name": base.get("name")}

        if not args.skip_upload:
            entry["batches"] = upload_scenario(client, kb_id, files, args.batch_size, args.authority,
                                               (schema.get("knowledgeBase") or {}).get("permissionTags"),
                                               not args.no_wait, args.wait_timeout)
        if not args.skip_schema:
            entry["schema"] = apply_schema(client, kb_id, schema)
        if not args.skip_profile and profile_path.exists():
            entry["profile"] = apply_profile(client, load_json(profile_path), not args.no_activate)
        if args.with_eval:
            eval_file = SCENARIO_DIR / ("%s.eval.json" % directory_name)
            if eval_file.exists():
                entry["evaluation"] = import_eval(client, kb_id, eval_file)
        report["scenarios"][directory_name] = entry

    report["finishedAt"] = time.strftime("%Y-%m-%d %H:%M:%S")
    report_path = SCENARIO_DIR / ("build-report-%s.json" % time.strftime("%Y%m%d-%H%M%S"))
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print("\n构建报告：%s" % report_path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
