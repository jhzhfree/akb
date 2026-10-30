"""Frontend secure-context API contract.

内网部署拿不到公网证书，控制台经常是明文 HTTP 打开的。浏览器把一批 API 限定在「安全
上下文」（HTTPS 或 localhost）里，非安全上下文下它们根本不存在——crypto.randomUUID 就是
这样炸在新建主体那一步的（CONTRIBUTING 强制边界第 9 条）。

这个检查只拦「直接取用」的写法：

- 能力探测不算违规：typeof x?.y === 'function'、navigator.clipboard?.writeText 正是降级
  该有的样子
- 封装文件里的取用不算违规：utils/uid.ts、utils/clipboard.ts 的职责就是做探测与降级，
  白名单显式列在这里，改动时一眼能看见
- 确实必须在安全上下文下才能用的调用，在行尾加 // secure-context-ok 并说明原因
"""
import re
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[1] / "frontend" / "src"

# (调用形态正则, 名字, 为什么在内网明文下会出问题)
RULES = [
    (r"crypto\.randomUUID\s*\(", "crypto.randomUUID", "非安全上下文不存在，调用即 TypeError"),
    (r"window\.crypto\.randomUUID\s*\(", "crypto.randomUUID", "同上"),
    (r"crypto\.subtle\s*\.", "crypto.subtle", "非安全上下文为 undefined（WebCrypto 签名/哈希）"),
    (r"navigator\.clipboard\s*\.", "navigator.clipboard", "非安全上下文下不存在，直接取用会抛且没有提示"),
    (r"navigator\.serviceWorker\s*\.", "Service Worker", "仅安全上下文可注册"),
    (r"navigator\.mediaDevices\s*\.|navigator\.getUserMedia\s*\(", "getUserMedia", "仅安全上下文（摄像头/麦克风）"),
    (r"navigator\.geolocation\s*\.", "navigator.geolocation", "非安全上下文被拒"),
    (r"navigator\.credentials\s*\.|\bPublicKeyCredential\b", "WebAuthn", "仅安全上下文"),
    (r"showOpenFilePicker\s*\(|showDirectoryPicker\s*\(|showSaveFilePicker\s*\(", "File System Access", "仅安全上下文"),
    (r"\bPaymentRequest\s*\(", "Payment Request", "仅安全上下文"),
]

# 职责就是做探测与降级的封装文件，允许在里面直接取用对应 API
ALLOWED = {
    "frontend/src/utils/uid.ts": {"crypto.randomUUID"},
    "frontend/src/utils/clipboard.ts": {"navigator.clipboard"},
}

SCAN_SUFFIXES = (".vue", ".ts", ".tsx", ".js", ".mjs")


def is_exempt_line(line):
    stripped = line.strip()
    # 整行注释（含 JSDoc 的 * 行）不是调用
    if stripped.startswith(("*", "//", "#", "/*")):
        return True
    # 能力探测：typeof x?.y === 'function'
    if "typeof" in line:
        return True
    # 显式豁免并注明原因
    if "secure-context-ok" in line:
        return True
    return False


violations = []
for path in sorted(root.rglob("*")):
    if not path.is_file() or path.suffix not in SCAN_SUFFIXES:
        continue
    relative = path.relative_to(root.parents[1]).as_posix()
    allowed = ALLOWED.get(relative, set())
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if is_exempt_line(line):
            continue
        for pattern, name, why in RULES:
            if name in allowed:
                continue
            if re.search(pattern, line):
                violations.append("%s:%d  %s — %s\n      %s" % (relative, number, name, why, line.strip()[:120]))

if violations:
    print("前端直接使用了仅安全上下文可用的 API（内网明文 HTTP 下会不存在）：\n", file=sys.stderr)
    for item in violations:
        print("  " + item, file=sys.stderr)
    print("\n改法：用已有的封装（frontend/src/utils/uid.ts、utils/clipboard.ts），"
          "或先探测再降级；确需直连的在该行加 // secure-context-ok 并注明原因。", file=sys.stderr)
    sys.exit(1)

print("frontend secure-context contract passed (封装豁免: %s)" % ", ".join(sorted(ALLOWED)))
