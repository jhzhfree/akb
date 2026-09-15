# V1.1 Release Candidate Checklist

## Automated local gate

```bash
./scripts/check-v11.sh
```

The gate validates Python syntax, AI pipeline, governance, adjudication, API contract,
Flyway/Mapper consistency, migration ordering, Java 8 compatibility and RC file completeness.

## Backend unit tests

```bash
cd backend
mvn -B test
```

## MySQL/Flyway integration test

Requires Docker because Testcontainers starts MySQL 8.0.36.

```bash
cd backend
mvn -B -Pintegration-test verify
```

This test verifies that V1/V2 Flyway migrations execute on real MySQL and that the
query trace columns required by the V1.1 API contract exist.

## Frontend build

```bash
cd frontend
npm install
npm run build
```

## Compose validation and full-stack boot

```bash
cp .env.example .env
docker compose config --quiet
docker compose up -d --build
./scripts/wait-ready.sh
```

## Mandatory end-to-end RC scenario

1. Upload a real PDF/DOCX/Markdown document.
2. Verify Source -> SourceVersion -> Document -> ContentUnit -> Evidence records.
3. Verify Claim/Card extraction and evidence links.
4. Run intelligent adjudication and verify assessment record.
5. Query the knowledge base and verify citation/source/page.
6. Fetch `/api/knowledge/trace/{queryId}` and verify rewrite/router/retrieval/fusion/rerank/context/model/latency fields.
7. Verify the same `X-Trace-Id` appears at HTTP response, backend logs and AI service logs.
8. Run a conflict case and verify it cannot auto-pass human-review policy.

## RC exit criteria

- `./scripts/check-v11.sh` passes.
- `mvn test` passes on Java 8.
- `mvn -Pintegration-test verify` passes with Docker/Testcontainers.
- Frontend production build passes.
- Docker Compose full stack becomes healthy.
- Real Ollama/BGE/Neo4j integration has been exercised at least once.
- No P0/P1 contract, migration, traceability or adjudication defects remain.
