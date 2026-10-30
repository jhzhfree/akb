#!/usr/bin/env node
/**
 * 把 contracts/intranet-http.json（明文 HTTP 判定的唯一事实来源）同步成前端可直接 import
 * 的模块。
 *
 * 后端从 classpath 读同一个文件（pom 把 contracts 挂成了资源目录，不复制），前端生成一份
 * 副本——Node 与浏览器都直接 import，不需要 JSON import attribute。workload-identity.test.mjs
 * 会断言副本与真源一致，所以改了规则忘了跑这个脚本，测试会红而不是静默漂移。
 */
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const source = join(root, "contracts", "intranet-http.json");
const target = join(root, "frontend", "src", "utils", "intranet-rules.mjs");

const rules = JSON.parse(readFileSync(source, "utf8"));
writeFileSync(
  target,
  `// 由 scripts/sync-intranet-rules.mjs 从 contracts/intranet-http.json 生成，请勿手改。\n` +
    `// 改动规则请改真源，再跑 npm run sync:intranet-rules。\n` +
    `export default ${JSON.stringify(rules, null, 2)};\n`,
);
console.log("已同步 contracts/intranet-http.json →", target);
