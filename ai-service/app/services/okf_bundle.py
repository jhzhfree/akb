"""Open Knowledge Format (OKF) v0.2 bundle inspection and normalization."""

from __future__ import annotations

import hashlib
import io
import posixpath
import re
import zipfile
from datetime import datetime, timezone
from pathlib import PurePosixPath
from typing import Any, Dict, List, Optional, Tuple

import yaml


class OkfBundleError(ValueError):
    """Raised when an OKF bundle cannot be safely consumed."""


class OkfBundleInspector:
    """Best-effort OKF v0.2 consumer with explicit safety and conformance checks."""

    MAX_ARCHIVE_BYTES = 25 * 1024 * 1024
    MAX_EXPANDED_BYTES = 100 * 1024 * 1024
    MAX_FILES = 5000
    MAX_FILE_BYTES = 2 * 1024 * 1024
    RESERVED = {"index.md", "log.md"}
    STATUS_VALUES = {"draft", "stable", "deprecated"}
    LINK_PATTERN = re.compile(r"(?<!!)\[[^\]]*\]\(([^)]+)\)")

    def inspect(self, content: bytes, filename: str = "bundle.zip") -> Dict[str, Any]:
        if not content:
            raise OkfBundleError("OKF bundle is empty")
        if len(content) > self.MAX_ARCHIVE_BYTES:
            raise OkfBundleError("OKF archive exceeds 25 MiB")
        if not zipfile.is_zipfile(io.BytesIO(content)):
            raise OkfBundleError("OKF bundle must be a ZIP archive")

        files = self._read_archive(content)
        markdown = {name: data for name, data in files.items() if name.lower().endswith(".md")}
        if not markdown:
            raise OkfBundleError("OKF bundle contains no Markdown files")

        root = self._detect_root(markdown)
        relative = {name[len(root):]: data for name, data in markdown.items() if name.startswith(root)}
        paths = set(relative)
        concepts: List[Dict[str, Any]] = []
        errors: List[Dict[str, str]] = []
        warnings: List[Dict[str, str]] = []
        version: Optional[str] = None

        for path in sorted(relative):
            raw = relative[path]
            try:
                text = raw.decode("utf-8")
            except UnicodeDecodeError:
                errors.append(self._issue(path, "INVALID_UTF8", "Markdown must be UTF-8"))
                continue
            try:
                frontmatter, body = self._parse_markdown(text)
            except OkfBundleError as exc:
                errors.append(self._issue(path, "INVALID_FRONTMATTER", str(exc)))
                continue

            basename = posixpath.basename(path)
            if basename in self.RESERVED:
                if path == "index.md" and frontmatter.get("okf_version") is not None:
                    version = str(frontmatter["okf_version"])
                continue

            concept_type = frontmatter.get("type")
            if not isinstance(concept_type, str) or not concept_type.strip():
                errors.append(self._issue(path, "TYPE_REQUIRED", "Concept frontmatter requires non-empty type"))
                continue
            concept, concept_warnings = self._normalize_concept(path, frontmatter, body, paths)
            concepts.append(concept)
            warnings.extend(concept_warnings)

        if version and version != "0.2":
            warnings.append(self._issue("index.md", "UNKNOWN_OKF_VERSION", "Consumed with v0.2 best-effort semantics"))

        return {
            "format": "OKF",
            "specVersion": version,
            "consumerVersion": "0.2",
            "sourceName": filename,
            "bundleSha256": hashlib.sha256(content).hexdigest(),
            "conformant": not errors,
            "summary": {
                "conceptCount": len(concepts),
                "errorCount": len(errors),
                "warningCount": len(warnings),
                "trustTiers": self._counts(concepts, "trustTier"),
                "statuses": self._counts(concepts, "status"),
            },
            "concepts": concepts,
            "errors": errors,
            "warnings": warnings,
        }

    def _read_archive(self, content: bytes) -> Dict[str, bytes]:
        result: Dict[str, bytes] = {}
        expanded = 0
        with zipfile.ZipFile(io.BytesIO(content)) as archive:
            entries = [item for item in archive.infolist() if not item.is_dir()]
            if len(entries) > self.MAX_FILES:
                raise OkfBundleError("OKF bundle contains too many files")
            for item in entries:
                name = item.filename.replace("\\", "/")
                path = PurePosixPath(name)
                if path.is_absolute() or ".." in path.parts or not name or "\x00" in name:
                    raise OkfBundleError("OKF archive contains an unsafe path")
                if item.file_size > self.MAX_FILE_BYTES:
                    raise OkfBundleError("OKF bundle contains an oversized file")
                expanded += item.file_size
                if expanded > self.MAX_EXPANDED_BYTES:
                    raise OkfBundleError("OKF expanded content exceeds 100 MiB")
                result[posixpath.normpath(name)] = archive.read(item)
        return result

    @staticmethod
    def _detect_root(files: Dict[str, bytes]) -> str:
        roots = [name[:-len("index.md")] for name in files if name.endswith("index.md")]
        if "" in roots:
            return ""
        # A common enclosing directory is packaging noise only when every file
        # is actually nested. A single root-level Markdown filename is not a root.
        if files and all("/" in name for name in files):
            top = {name.split("/", 1)[0] for name in files}
            return next(iter(top)) + "/" if len(top) == 1 else ""
        return ""

    @staticmethod
    def _parse_markdown(text: str) -> Tuple[Dict[str, Any], str]:
        if not text.startswith("---"):
            raise OkfBundleError("YAML frontmatter opening delimiter is required")
        match = re.match(r"^---[ \t]*\r?\n(.*?)\r?\n---[ \t]*(?:\r?\n|$)", text, re.DOTALL)
        if not match:
            raise OkfBundleError("YAML frontmatter closing delimiter is required")
        try:
            frontmatter = yaml.safe_load(match.group(1)) or {}
        except yaml.YAMLError as exc:
            raise OkfBundleError("YAML frontmatter is not parseable") from exc
        if not isinstance(frontmatter, dict):
            raise OkfBundleError("YAML frontmatter must be a mapping")
        return frontmatter, text[match.end():]

    def _normalize_concept(self, path: str, fm: Dict[str, Any], body: str,
                           paths: set) -> Tuple[Dict[str, Any], List[Dict[str, str]]]:
        warnings: List[Dict[str, str]] = []
        status = fm.get("status", "stable")
        if status not in self.STATUS_VALUES:
            warnings.append(self._issue(path, "UNKNOWN_STATUS", "Unknown lifecycle status was preserved"))
        verified = self._actors(fm.get("verified"))
        generated = self._actors(fm.get("generated"))
        trust = "human-reviewed" if any(v.startswith("human:") for v in verified) else (
            "machine-confirmed" if verified else "unverified"
        )
        links = []
        for raw_target in self.LINK_PATTERN.findall(body):
            target = raw_target.strip().split("#", 1)[0]
            if not target or "://" in target or target.startswith(("mailto:", "#")):
                continue
            resolved = posixpath.normpath(posixpath.join(posixpath.dirname(path), target))
            exists = resolved in paths
            links.append({"target": raw_target, "resolvedPath": resolved, "exists": exists})
            if not exists:
                warnings.append(self._issue(path, "BROKEN_LINK", raw_target))
        stale_after = fm.get("stale_after")
        stale = self._is_stale(stale_after, path, warnings)
        known = {"type", "title", "description", "resource", "tags", "sources", "generated",
                 "verified", "status", "stale_after", "runtime", "parameters", "computation",
                 "executor", "attester"}
        return {
            "conceptId": path[:-3],
            "path": path,
            "type": fm["type"].strip(),
            "title": fm.get("title"),
            "description": fm.get("description"),
            "resource": fm.get("resource"),
            "tags": fm.get("tags") if isinstance(fm.get("tags"), list) else [],
            "sources": fm.get("sources") if isinstance(fm.get("sources"), list) else [],
            "generated": generated,
            "verified": verified,
            "trustTier": trust,
            "status": status,
            "staleAfter": stale_after,
            "stale": stale,
            "links": links,
            "bodyMarkdown": body,
            "extensions": {key: value for key, value in fm.items() if key not in known},
            "frontmatter": fm,
        }, warnings

    @staticmethod
    def _actors(value: Any) -> List[str]:
        if value is None:
            return []
        values = value if isinstance(value, list) else [value]
        return [str(item).strip() for item in values if str(item).strip()]

    def _is_stale(self, value: Any, path: str, warnings: List[Dict[str, str]]) -> bool:
        if value is None:
            return False
        try:
            stamp = str(value).replace("Z", "+00:00")
            parsed = datetime.fromisoformat(stamp)
            if parsed.tzinfo is None:
                raise ValueError("offset required")
            return parsed.astimezone(timezone.utc) <= datetime.now(timezone.utc)
        except ValueError:
            warnings.append(self._issue(path, "INVALID_STALE_AFTER", "Expected ISO 8601 timestamp with UTC offset"))
            return False

    @staticmethod
    def _counts(items: List[Dict[str, Any]], key: str) -> Dict[str, int]:
        counts: Dict[str, int] = {}
        for item in items:
            value = str(item.get(key))
            counts[value] = counts.get(value, 0) + 1
        return counts

    @staticmethod
    def _issue(path: str, code: str, message: str) -> Dict[str, str]:
        return {"path": path, "code": code, "message": message}
