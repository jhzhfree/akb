/**
 * intranet-rules.mjs 是生成物（由 contracts/intranet-http.json 生成，勿手改），这里只补类型，
 * 让 TS 侧能直接 import 而不是靠 any。
 */
declare const rules: {
  loopback?: { hosts?: string[]; ipv4Prefixes?: string[] };
  ipv4Ranges?: { first?: number; second?: number }[];
  ipv6Prefixes?: string[];
  hostSuffixes?: string[];
  bareHostnameIsIntranet?: boolean;
};
export default rules;
