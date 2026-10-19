# ---------- 版本与镜像 ----------
VERSION ?= 1.1.0
# 推送到远端仓库时设置，例如 REGISTRY=192.168.22.16:5050/zhanghe/ai-knowledge-os/
REGISTRY ?=
IMAGE_PREFIX ?= $(REGISTRY)knowledge-os
# 打包时是否一并导出中间件镜像（0=只打应用镜像，目标机需能联网拉中间件）
INCLUDE_MIDDLEWARE ?= 1

# 本地裸跑 ai-service 时使用的模型服务
OLLAMA_URL ?= http://192.168.22.6:11434
LLM_MODEL ?= qwen2.5:7b

# 检索回归验证打到哪个 backend（默认本机 dev）
BASE ?= http://127.0.0.1:8080

# ---------- Python 运行环境（conda）----------
# 开发统一使用 conda 环境 llmkb，等价于手工执行：conda activate llmkb
# Makefile 每条配方是独立的 /bin/sh，无法沿用 shell 里的 conda 函数，
# 因此一律通过该环境的绝对路径解释器运行，效果与激活后一致且更可靠。
CONDA_BASE ?= $(shell command -v conda >/dev/null 2>&1 && conda info --base 2>/dev/null || echo /root/miniconda3)
CONDA_ENV ?= llmkb
CONDA_PY := $(CONDA_BASE)/envs/$(CONDA_ENV)/bin/python
# 通用脚本（e2e 等）用 conda 环境，未安装则回退系统 python3
PY ?= $(shell test -x "$(CONDA_PY)" && echo "$(CONDA_PY)" || command -v python3)

# ---------- Maven 代理 ----------
# JVM 不读 http_proxy/https_proxy 环境变量，需显式转成 -D 参数，
# 否则 Maven 会直连中央仓库并报 "Unknown host ... Temporary failure in name resolution"。
# 未设置代理变量时自动留空，不影响直连环境。
PROXY_URL ?= $(or $(https_proxy),$(http_proxy))
PROXY_ADDR := $(if $(PROXY_URL),$(lastword $(subst //, ,$(PROXY_URL))),)
MVN_PROXY_OPTS := $(if $(PROXY_ADDR),\
	-Dhttp.proxyHost=$(firstword $(subst :, ,$(PROXY_ADDR))) \
	-Dhttp.proxyPort=$(lastword $(subst :, ,$(PROXY_ADDR))) \
	-Dhttps.proxyHost=$(firstword $(subst :, ,$(PROXY_ADDR))) \
	-Dhttps.proxyPort=$(lastword $(subst :, ,$(PROXY_ADDR))) \
	-Dhttp.nonProxyHosts="localhost|127.0.0.1",)
MVN ?= mvn $(MVN_PROXY_OPTS)

.PHONY: check up down logs ps clean \
	dev-env dev-up dev-app-up dev-ai-up dev-backend-up dev-frontend-up dev-down dev-ps dev-logs dev-clean \
	dev-milvus-up dev-milvus-down dev-milvus-status \
	dev-install dev-pip dev-ai dev-backend dev-frontend dev-all \
	prod-env prod-up prod-down prod-ps prod-logs \
	release-build release-push push-images package validate-kb
check:
	./scripts/check-v11.sh
up:
	docker compose up -d --build
down:
	docker compose down
logs:
	docker compose logs -f --tail=200
ps:
	docker compose ps
clean:
	docker compose down -v --remove-orphans

rc-check:
	./scripts/check-v11.sh

e2e:
	$(PY) scripts/e2e-test.py

diagnose:
	./scripts/diagnose.sh

# ---------- v1.1 开发环境：容器 ----------
DEV = docker compose --env-file .env.dev -f docker-compose.dev.yml
DEV_APP = $(DEV) --profile app
PROD = docker compose --env-file .env.production -f deploy/docker-compose.prod.yml

dev-env:
	@test -f .env.dev || cp .env.dev.example .env.dev

dev-up: dev-env
	$(DEV) up -d

dev-app-up: dev-env
	$(DEV_APP) up -d --build

dev-ai-up: dev-env
	$(DEV_APP) up -d --build ai-service

dev-backend-up: dev-env
	$(DEV_APP) up -d --build backend

dev-frontend-up: dev-env
	$(DEV_APP) up -d --build frontend

dev-down:
	$(DEV) down

dev-ps:
	$(DEV) ps

dev-logs:
	$(DEV) logs -f --tail=200

dev-clean:
	$(DEV) down -v --remove-orphans

# ---------- v1.1 开发环境：本地裸跑（conda 环境 llmkb）----------

# 创建 llmkb 环境并安装 ai-service 依赖 + 前端依赖
dev-install:
	@command -v conda >/dev/null 2>&1 || { echo "未检测到 conda，请先安装 Miniconda，或用 CONDA_BASE=<路径> 指定"; exit 1; }
	@test -x "$(CONDA_PY)" || conda create -y -n $(CONDA_ENV) python=3.10
	$(CONDA_PY) -m pip install -q --disable-pip-version-check --root-user-action=ignore -r ai-service/requirements.txt
	cd frontend && npm install
	@command -v mvn >/dev/null || echo "[warn] 未检测到 mvn；make dev-backend 不可用，可改用 make dev-backend-up 以容器方式启动后端"
	@echo "[dev-install] 完成。手工开发时请先执行：conda activate $(CONDA_ENV)"

# 仅重装 Python 依赖（改了 requirements.txt 后使用）
dev-pip:
	$(CONDA_PY) -m pip install -q --disable-pip-version-check --root-user-action=ignore -r ai-service/requirements.txt

# ai-service：默认连本机 7687（容器已映射）。
# 注意：下面这几个 *_ENABLED 只是「首次种子值」——一旦 ai-service/data/retrieval_settings.json
# 存在（控制台检索设置页保存过），它就优先于环境变量，改这里不会生效；要真正开关请用
# 控制台检索设置页，或 ./scripts/dev-milvus.sh enable|disable。
# NO_PROXY 必须带上 127.0.0.1：本机设了 http_proxy，httpx 否则会把 127.0.0.1:19530/9200
# 交给代理，表现为 "502 Bad Gateway" 而不是连接失败。
dev-ai:
	@test -x "$(CONDA_PY)" || { echo "未找到 conda 环境 $(CONDA_ENV)：$(CONDA_PY)，请先执行 make dev-install"; exit 1; }
	cd ai-service && KNOWLEDGE_DATA_DIR=$(CURDIR)/ai-service/data \
		NO_PROXY=127.0.0.1,localhost no_proxy=127.0.0.1,localhost \
		NEO4J_URI=bolt://127.0.0.1:7687 NEO4J_USER=neo4j NEO4J_PASSWORD=knowledge123 \
		LLM_PROVIDER=ollama LLM_BASE_URL=$(OLLAMA_URL) LLM_MODEL=$(LLM_MODEL) \
		EMBEDDING_PROVIDER=ollama EMBEDDING_MODEL=nomic-embed-text EMBEDDING_DIM=768 \
		RETRIEVAL_MODE=auto ELASTICSEARCH_ENABLED=true MILVUS_ENABLED=true RERANKER_ENABLED=false \
		$(CONDA_PY) -m uvicorn app.main:app --reload --host 0.0.0.0 --port 12000

# Milvus（dual-retrieval profile）：etcd + minio + standalone，19530 映射到宿主机
dev-milvus-up: dev-env
	./scripts/dev-milvus.sh up

dev-milvus-down:
	./scripts/dev-milvus.sh down

dev-milvus-status:
	./scripts/dev-milvus.sh status

# backend：application.yml 中 DB/Redis/AI 默认即指向 127.0.0.1，无需额外覆盖
dev-backend:
	@command -v mvn >/dev/null || { echo "未检测到 mvn，请先安装 Maven 3.8+，或改用 make dev-backend-up"; exit 1; }
	@command -v javac >/dev/null || { echo "检测到 java 但无 javac：当前是 JRE 不是 JDK。请安装 JDK 8，例如 sudo apt-get install -y openjdk-8-jdk-headless"; exit 1; }
	cd backend && DB_URL='jdbc:mysql://127.0.0.1:3306/knowledge_os?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true' \
		REDIS_HOST=127.0.0.1 AI_SERVICE_BASE_URL=http://127.0.0.1:12000 \
		$(MVN) spring-boot:run

# frontend：vite dev server 5173，已配置 /api 代理到 127.0.0.1:8080
dev-frontend:
	cd frontend && npm run dev

dev-all:
	@echo "并行启动 ai-service(12000) / backend(8080) / frontend(5173)，Ctrl-C 结束"
	$(MAKE) -j3 dev-ai dev-backend dev-frontend

# ---------- 生产环境 ----------
prod-env:
	@test -f .env.production || cp .env.production.example .env.production

prod-up: prod-env
	$(PROD) up -d --build

prod-down:
	$(PROD) down

prod-ps:
	$(PROD) ps

prod-logs:
	$(PROD) logs -f --tail=200

# ---------- 发布与产品包 ----------
# 构建并打标签：knowledge-os/{ai-service,backend,frontend}:$(VERSION)
# ---------- 静态检查 / 编码规范 ----------
# 规则集与取舍说明见 docs/CODE-STYLE.md
CHECKSTYLE_JAR ?= $(HOME)/.local/lib/checkstyle/checkstyle.jar
CHECKSTYLE_CONFIG ?= config/checkstyle/checkstyle.xml
RUFF ?= ruff

.PHONY: lint lint-python lint-java lint-frontend format-python format-frontend

lint: lint-python lint-java lint-frontend

lint-python:
	@command -v $(RUFF) >/dev/null 2>&1 || { echo "缺少 ruff，请先执行：pip install ruff"; exit 1; }
	$(RUFF) check .
	$(RUFF) format --check .

lint-java:
	@test -f "$(CHECKSTYLE_JAR)" || { echo "缺少 checkstyle jar，请执行 make tools-install"; exit 1; }
	java -Dfile.encoding=UTF-8 -jar "$(CHECKSTYLE_JAR)" -c "$(CHECKSTYLE_CONFIG)" backend/src/main/java backend/src/test/java

lint-frontend:
	cd frontend && npm run lint && npm run typecheck

# 自动修复：安全的 fix 由工具完成，剩余问题需人工判断
format-python:
	$(RUFF) check --fix .
	$(RUFF) format .

format-frontend:
	cd frontend && npm run lint:fix

tools-install:
	command -v $(RUFF) >/dev/null 2>&1 || pip install ruff
	mkdir -p "$(HOME)/.local/lib/checkstyle"
	test -f "$(CHECKSTYLE_JAR)" || curl -sL -o "$(CHECKSTYLE_JAR)" \
		https://github.com/checkstyle/checkstyle/releases/download/checkstyle-8.45.1/checkstyle-8.45.1-all.jar

release-build:
	VERSION=$(VERSION) IMAGE_PREFIX=$(IMAGE_PREFIX) docker compose -f docker-compose.yml build ai-service backend frontend

release-push:
	@for s in ai-service backend frontend; do \
		echo "push $(IMAGE_PREFIX)/$$s:$(VERSION)"; \
		docker push $(IMAGE_PREFIX)/$$s:$(VERSION); \
	done

# 重新做应用镜像并推到 22.6（版本不变，覆盖式发布）；只做前端：make push-images s=frontend
# 可选：PUSH=0 只构建不推送，BACKUP=0 不备份旧 tag，DRY_RUN=1 只打印
push-images:
	VERSION=$(VERSION) REGISTRY=$(TARGET_REGISTRY) SERVICES="$(s)" ./scripts/release-push.sh

# 默认五库检索回归验证：跑 dataset/enterprise_kb_validation_5sets 的 5 条用例
# 只跑一个库：make validate-kb only=漏洞库；打目标机：make validate-kb BASE=http://192.168.22.6:8080
validate-kb:
	BASE=$(BASE) python3 dataset/enterprise_kb_validation_5sets/validate.py $(only)

# 生成离线安装产品包：dist/ai-knowledge-$(VERSION).tar.gz
package:
	VERSION=$(VERSION) IMAGE_PREFIX=$(IMAGE_PREFIX) INCLUDE_MIDDLEWARE=$(INCLUDE_MIDDLEWARE) ./scripts/package-release.sh
