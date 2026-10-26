# 制作产品安装包

`deploy/pack.sh` 把编排文件、管理脚本、配置模板与（可选）全部镜像组装成一个 tar.gz，
即交付给客户的产品安装包。包本身不包含源码，目标机无需编译环境。

仓库内的 `deploy/package/` 是**包内容的源文件**：改脚本、改 `.env.example`、改 README 都在
这里改，再由 `pack.sh` 打进包里。不要直接改打出来的包——改了下次打包会被覆盖。

## 一、前提条件

- 本机已安装 Docker 与 docker compose v2 插件
- 制作**离线包**时，compose 里出现的镜像必须在本机已存在（业务镜像可 `--build` 现构建，
  依赖镜像可 `--pull` 现拉取）
- 制作**在线包**无此要求，镜像由目标机安装时从仓库拉取

## 二、打包命令

```bash
cd deploy

# 在线包：包内不含镜像，几百 KB。目标机安装时从 IMAGE_PREFIX 指定的仓库拉取。
./pack.sh

# 离线包：先构建业务镜像、拉取依赖镜像，再把全部镜像导出进包（十余 GB）。
./pack.sh --offline --build --pull

# 指定版本与镜像前缀
./pack.sh --version 1.2.0 --prefix registry.example.com/knowledge-os

# 指定产物目录
./pack.sh --out /data/release
```

## 三、参数

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| `--version <x.y.z>` | 取 `frontend/package.json` 的 version | 写入 `release.env` 与 `VERSION`，决定镜像 tag |
| `--prefix <前缀>` | `knowledge-os` | 镜像前缀。推到私有仓库时填仓库地址 |
| `--offline` | 关 | 导出全部镜像到 `images/`，目标机无需外网 |
| `--build` | 关 | 先用 `docker-compose.prod.yml` 构建 ai-service / backend / frontend |
| `--pull` | 关 | 先拉取 compose 里的依赖镜像（mysql、es、milvus、neo4j…） |
| `--out <目录>` | `deploy/dist` | 产物输出目录 |
| `-h` / `--help` | — | 打印用法 |

版本未指定时从 `frontend/package.json` 读取，三个业务镜像共用同一个版本号，与前端版本对齐。

## 四、包内容

```
ai-knowledge-os-<VERSION>[-offline]-<日期>/
├── docker-compose.yml      ← deploy/docker-compose.release.yml
├── .env.example            ← deploy/package/.env.example
├── release.env             VERSION / IMAGE_PREFIX（由打包写入）
├── VERSION
├── config/*.json           后端出厂默认配置
├── monitoring/             ← 仓库根 monitoring/（Prometheus / Grafana 配置）
├── images/*.tar.gz         仅离线包：每个镜像一份
├── install.sh start.sh stop.sh status.sh logs.sh uninstall.sh
└── README.md
```

`monitoring/` 与 `config/` 是必带项：前者被容器 bind 挂载，少了没有监控；后者是运维对照用
的出厂副本。打包脚本对两者都做了存在性校验，缺失会直接报错而不是打出坏包。

打包过程中会做一次 `docker compose config` 校验。编排文件有语法错误或变量缺失时，打包失败，
不会产出半成品。

## 五、离线包的制作流程

镜像必须与版本严格对应，离线包不能跨版本复用。

```bash
cd deploy
# 1. 构建业务镜像（image 名与 release 一致：${IMAGE_PREFIX}/<name>:${VERSION}）
./pack.sh --offline --build --pull

# 或者分两步，先在仓库里把镜像准备好：
docker compose -f docker-compose.prod.yml build ai-service backend index-worker frontend
./pack.sh --offline --pull
```

产物：

```
deploy/dist/ai-knowledge-os-1.1.0-offline-20261026.tar.gz
deploy/dist/ai-knowledge-os-1.1.0-offline-20261026.tar.gz.sha256
```

缺失镜像时脚本会列出镜像名并退出，不会打出缺镜像的包：

```
[pack][ERROR] 以下镜像在本机不存在，无法制作离线包：
           knowledge-os/ai-service:1.1.0
[pack][ERROR] 业务镜像加 --build 构建；依赖镜像加 --pull 拉取
```

## 六、发布与校验

1. 把 `.tar.gz` 与 `.sha256` 一起交付（校验文件只含摘要与文件名，与目标机目录无关）。
2. 目标机校验：`sha256sum -c ai-knowledge-os-<VERSION>.tar.gz.sha256`。
3. 目标机按包内 `README.md` 安装。

建议在内网先完整走一遍：解开 → 改 `.env` → `./start.sh --wait` → `./status.sh`，确认五个
健康端点全 OK 再对外发布。

## 七、与源码部署的关系

| 场景 | 用什么 |
| --- | --- |
| 开发调试 | `docker-compose.dev.yml`（挂载仓库目录，改代码即生效） |
| 服务器现场部署 | `docker-compose.prod.yml`（在本机构建镜像后启动） |
| **交付客户** | 本流程打出的安装包（`docker-compose.release.yml` 作为包内编排） |

`release.yml` 里所有服务都是 `image:`，不在目标机构建；`prod.yml` 里业务服务是 `build:`，
供本机构建使用。两者共用同一套镜像名与版本号，所以 `prod` 构建出的镜像可以直接被
`pack.sh --offline` 导出。

## 八、常见问题

**打包时报镜像清单解析失败**：compose 版本过旧不支持 `config --images`。脚本会自动退回解析
`image:` 行；两者都失败才报错，此时请升级 docker compose 插件。

**离线包特别大**：正常。全部中间件镜像十余 GB。只需在线安装就别加 `--offline`。

**目标机 `docker load` 后镜像名不对**：确认 `release.env` 里的 `IMAGE_PREFIX` 与 `VERSION`
和打包时一致；改过 `.env` 里的 `IMAGE_PREFIX` 会导致容器找不到镜像。

**改了脚本没生效**：包内脚本来自 `deploy/package/`，重新执行 `pack.sh` 才会更新。
