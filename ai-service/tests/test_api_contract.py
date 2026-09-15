import json
from pathlib import Path
from fastapi.testclient import TestClient
from app.main import app

ROOT = Path(__file__).resolve().parents[2]
CONTRACT = json.loads((ROOT / "contracts" / "ai-api-v1.1.json").read_text(encoding="utf-8"))
client = TestClient(app)


def require_fields(payload, fields):
    missing = [name for name in fields if name not in payload]
    assert not missing, "missing contract fields: %s" % missing


def test_contract_version_and_query_response():
    assert CONTRACT["version"] == "1.1"
    spec = CONTRACT["endpoints"]["query"]
    response = client.post(spec["path"], json={"queryId": "contract-test", "knowledgeBaseId": 1, "query": "不存在的测试问题", "topK": 3}, headers={"X-Trace-Id": "contract-trace"})
    assert response.status_code == 200, response.text
    assert response.headers.get("X-Trace-Id") == "contract-trace"
    payload = response.json()
    require_fields(payload, spec["responseRequired"])
    assert payload["queryId"] == "contract-test"
    assert "totalLatencyMs" in payload["trace"]


def test_extract_contract():
    spec = CONTRACT["endpoints"]["extract"]
    response = client.post(spec["path"], json={"knowledgeBaseId": 1, "text": "Tomcat 支持 PKCS12 证书。"})
    assert response.status_code == 200, response.text
    require_fields(response.json(), spec["responseRequired"])


def test_query_validation_contract():
    response = client.post("/ai/query", json={"knowledgeBaseId": 0, "query": "", "topK": 1000})
    assert response.status_code == 422


if __name__ == "__main__":
    test_contract_version_and_query_response()
    test_extract_contract()
    test_query_validation_contract()
    print("api contract passed")
