# 订单服务接口规范（V2.6）

> 文档编号：API-ORDER-11　|　版本：V2.6　|　状态：生效（2026-07-15）
> Owner：吴桐（P8）　|　Confluence：KOS-ARCH/ORDER　|　适用：order-api v2.6+

## 一、通用约定

| 项 | 约定 |
|---|---|
| 协议 | HTTP/HTTPS，JSON（UTF-8） |
| 版本 | 路径版本 `/api/v2/...`（旧 `/api/v1` 仅维护，2026 Q4 下线） |
| 鉴权 | `Authorization: Bearer <token>`，网关统一校验 |
| 幂等 | 写操作须传 `Idempotency-Key`（UUID），有效期 24 小时 |
| 分页 | `pageNo`（从 1 开始）+ `pageSize`（默认 20，最大 100）；深分页须用游标 `cursor` |
| 时间 | ISO-8601，时区 Asia/Shanghai |
| 金额 | 字符串传输，单位元，保留 4 位小数（如 `"12345.6789"`） |

## 二、统一响应结构

```json
{
  "code": "0",
  "message": "success",
  "traceId": "a1b2c3d4e5f6",
  "data": { }
}
```

| code | 含义 | 处理建议 |
|---|---|---|
| 0 | 成功 | — |
| 40001 | 参数错误 | 检查入参 |
| 40002 | 幂等冲突（重复请求） | 视为成功，勿重试 |
| 40301 | 无权限 | 检查 token 与授权范围 |
| 40401 | 资源不存在 | — |
| 40901 | 状态冲突（如已取消不可支付） | 查询最新状态 |
| 50001 | 系统异常 | 可重试（幂等键相同） |
| 50301 | 依赖不可用 | 退避重试 |

## 三、下单接口

`POST /api/v2/orders`

请求示例：

```json
{
  "merchantId": "M202608120001",
  "outOrderNo": "O20260812000137",
  "userId": "U100238871",
  "items": [
    { "skuId": "SKU-88231", "quantity": 2, "price": "199.0000" }
  ],
  "amount": "398.0000",
  "currency": "CNY",
  "channel": "ALIPAY",
  "expireSeconds": 900,
  "ext": { "source": "miniapp" }
}
```

响应示例：

```json
{
  "code": "0",
  "traceId": "a1b2c3d4e5f6",
  "data": {
    "orderId": "2026081200013701",
    "payId": "P2026081200013701",
    "status": "PAYING",
    "amount": "398.0000",
    "payUrl": "https://pay.kos.local/cashier?payId=P2026081200013701",
    "expireAt": "2026-08-12T16:25:00+08:00"
  }
}
```

错误码：40001（参数错误）、40002（outOrderNo 重复，视为成功）、40901（库存不足）。

## 四、订单查询接口

`GET /api/v2/orders/{orderId}`　`GET /api/v2/orders?outOrderNo=...`

响应字段：

| 字段 | 类型 | 说明 |
|---|---|---|
| orderId | string | 订单号 |
| status | string | INIT/PAYING/SUCCESS/CLOSED/REFUNDING/REFUNDED |
| amount | string | 订单金额 |
| paidAmount | string | 实付金额 |
| channel | string | 支付渠道 |
| items | array | 商品明细 |
| createdAt / updatedAt | string | ISO-8601 |

> 深分页问题：`/api/v2/orders/list?pageNo=5000` 曾导致超时与越界（KOS-3303），列表接口超过第 100 页必须使用 `cursor` 游标方式。

## 五、订单列表（游标版）

`GET /api/v2/orders/list?userId=U100238871&cursor=eyJ...&pageSize=20`

```json
{
  "code": "0",
  "data": {
    "items": [ { "orderId": "2026081200013701", "status": "SUCCESS" } ],
    "nextCursor": "eyJvZmZzZXQiOjIwfQ==",
    "hasMore": true
  }
}
```

## 六、取消与退款

| 接口 | 方法 | 说明 |
|---|---|---|
| `/api/v2/orders/{orderId}/cancel` | POST | 仅 INIT/PAYING 可取消 |
| `/api/v2/orders/{orderId}/refund` | POST | SUCCESS 可退款，需 `refundNo` 幂等键 |
| `/api/v2/refunds/{refundNo}` | GET | 退款查询 |

退款请求：

```json
{
  "refundNo": "R20260812000137",
  "refundAmount": "398.0000",
  "reason": "用户取消",
  "notifyUrl": "https://merchant.example.com/refund/callback"
}
```

## 七、回调通知

- 支付成功与退款结果通过回调通知商户，最多重试 8 次（间隔 1s/5s/30s/2m/10m/30m/2h/6h）；
- 回调签名：`sign = HMAC-SHA256(timestamp + nonce + body, appSecret)`，请求头 `X-Kos-Sign`；
- 商户返回 `{"code":"0"}` 视为成功，其它响应触发重试。

## 八、限流与配额

| 场景 | 限流规则 |
|---|---|
| 下单 | 单商户 500 TPS，突发 800 |
| 查询 | 单商户 1,000 QPS |
| 列表 | 单用户 50 QPS |
| 回调 | 单渠道 200 TPS |

超限返回 429 + `Retry-After`。

## 九、变更历史

| 版本 | 日期 | 变更 |
|---|---|---|
| V2.6 | 2026-07-15 | 列表接口增加 cursor；金额改为字符串 4 位小数 |
| V2.5 | 2026-05-20 | 回调重试策略调整（6 次 → 8 次） |
| V2.4 | 2026-03-10 | 新增退款接口幂等键 |

> 已知问题：接口文档与实际字段不一致（KOS-3316），修复中；新增接口须带埋点与告警（KOS-3317）。
