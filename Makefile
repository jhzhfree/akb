.PHONY: check up down logs ps clean dev-env dev-up dev-down dev-ps dev-logs dev-app-up dev-clean
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
	python scripts/e2e-test.py

diagnose:
	./scripts/diagnose.sh

# ---------- v1.1 开发环境 ----------
dev-env:
	@test -f .env.dev || cp .env.dev.example .env.dev

dev-up: dev-env
	docker compose --env-file .env.dev -f docker-compose.dev.yml up -d

dev-app-up: dev-env
	docker compose --env-file .env.dev -f docker-compose.dev.yml --profile app up -d --build

dev-down:
	docker compose --env-file .env.dev -f docker-compose.dev.yml down

dev-ps:
	docker compose --env-file .env.dev -f docker-compose.dev.yml ps

dev-logs:
	docker compose --env-file .env.dev -f docker-compose.dev.yml logs -f --tail=200

dev-clean:
	docker compose --env-file .env.dev -f docker-compose.dev.yml down -v --remove-orphans
