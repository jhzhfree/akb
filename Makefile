.PHONY: check up down logs ps clean
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
