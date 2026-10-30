// 由 scripts/sync-intranet-rules.mjs 从 contracts/intranet-http.json 生成，请勿手改。
// 改动规则请改真源，再跑 npm run sync:intranet-rules。
export default {
  "$comment": "明文 HTTP 允许哪类 origin 的唯一事实来源。后端（Java 从 classpath 读）与前端（由 scripts/sync-intranet-rules.mjs 生成模块）都用它，任何一边改规则都必须改这里。公网一律要求 HTTPS：工作负载身份证明在明文链路上可被中间人直接替换。",
  "loopback": {
    "$comment": "本机单独一类场景，由独立开关控制，不算「内网」——避免内网开关顺带放开本机。",
    "hosts": [
      "localhost",
      "::1"
    ],
    "ipv4Prefixes": [
      "127."
    ]
  },
  "ipv4Ranges": [
    {
      "first": 10,
      "$comment": "RFC1918 私有地址 10/8"
    },
    {
      "first": 172,
      "secondFrom": 16,
      "secondTo": 31,
      "$comment": "RFC1918 私有地址 172.16/12"
    },
    {
      "first": 192,
      "second": 168,
      "$comment": "RFC1918 私有地址 192.168/16"
    },
    {
      "first": 169,
      "second": 254,
      "$comment": "链路本地 169.254/16"
    },
    {
      "first": 100,
      "secondFrom": 64,
      "secondTo": 127,
      "$comment": "运营商级 NAT 100.64/10，容器与云内网常见"
    }
  ],
  "ipv6Prefixes": [
    "fd",
    "fc",
    "fe80"
  ],
  "hostSuffixes": [
    ".local",
    ".internal",
    ".intranet",
    ".lan",
    ".corp",
    ".home",
    ".localdomain",
    ".private"
  ],
  "bareHostnameIsIntranet": true,
  "normalize": {
    "$comment": "判定前对主机名做的处理：转小写、去掉 IPv6 的方括号。不解析 DNS——解析会阻塞、可能失败，且结果随时可变。",
    "lowercase": true,
    "stripIpv6Brackets": true
  }
};
