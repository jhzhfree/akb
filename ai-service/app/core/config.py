"""Runtime configuration loaded from environment variables.

Keep this module free of application logic. Settings are read once at process
startup and exposed through the singleton ``settings`` object. Production
secrets must come from the deployment secret mechanism, never from source code.
"""

import os
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parents[2]
DATA_DIR = Path(os.getenv("KNOWLEDGE_DATA_DIR", str(BASE_DIR / "data")))
INDEX_DIR = DATA_DIR / "indexes"
UPLOAD_DIR = DATA_DIR / "uploads"
INDEX_DIR.mkdir(parents=True, exist_ok=True)
UPLOAD_DIR.mkdir(parents=True, exist_ok=True)


def _env_bool(name: str, default: bool = False) -> bool:
    """Read a conventional true/false environment flag."""
    return os.getenv(name, str(default)).lower() == "true"


class Settings:
    """Environment-backed configuration contract for the AI service."""

    # Generation / embedding
    llm_provider = os.getenv("LLM_PROVIDER", "mock")
    llm_base_url = os.getenv("LLM_BASE_URL", "http://127.0.0.1:11434")
    llm_model = os.getenv("LLM_MODEL", "qwen2.5:7b")
    embedding_provider = os.getenv("EMBEDDING_PROVIDER", "hash")
    embedding_model = os.getenv("EMBEDDING_MODEL", "nomic-embed-text")
    embedding_dim = int(os.getenv("EMBEDDING_DIM", "384"))

    # Parsing / context assembly
    chunk_size = int(os.getenv("CHUNK_SIZE", "900"))
    chunk_overlap = int(os.getenv("CHUNK_OVERLAP", "120"))
    rrf_k = int(os.getenv("RRF_K", "60"))
    context_max_chars = int(os.getenv("CONTEXT_MAX_CHARS", "8000"))

    # Graph
    graph_provider = os.getenv("GRAPH_PROVIDER", "neo4j")
    neo4j_uri = os.getenv("NEO4J_URI", "bolt://127.0.0.1:7687")
    neo4j_user = os.getenv("NEO4J_USER", "neo4j")
    neo4j_password = os.getenv("NEO4J_PASSWORD", "knowledge123")
    neo4j_database = os.getenv("NEO4J_DATABASE", "neo4j")

    # Reranking. Concurrency defaults to one to protect small GPUs from OOM.
    reranker_enabled = _env_bool("RERANKER_ENABLED")
    reranker_provider = os.getenv("RERANKER_PROVIDER", "bge")
    reranker_model = os.getenv("RERANKER_MODEL", "BAAI/bge-reranker-v2-m3")
    reranker_device = os.getenv("RERANKER_DEVICE", "auto")
    reranker_batch_size = int(os.getenv("RERANKER_BATCH_SIZE", "4"))
    reranker_concurrency = int(os.getenv("RERANKER_CONCURRENCY", "1"))
    reranker_max_length = int(os.getenv("RERANKER_MAX_LENGTH", "1024"))
    reranker_max_chars = int(os.getenv("RERANKER_MAX_CHARS", "6000"))

    # Retrieval mode: local, auto, external, or dual.
    retrieval_mode = os.getenv("RETRIEVAL_MODE", "auto").lower()
    retrieval_external_required = _env_bool("RETRIEVAL_EXTERNAL_REQUIRED")
    retrieval_timeout_seconds = float(os.getenv("RETRIEVAL_TIMEOUT_SECONDS", "5"))
    retrieval_batch_size = int(os.getenv("RETRIEVAL_BATCH_SIZE", "200"))

    # Elasticsearch full-text retrieval
    elasticsearch_enabled = _env_bool("ELASTICSEARCH_ENABLED", True)
    elasticsearch_url = os.getenv(
        "ELASTICSEARCH_URL",
        "http://127.0.0.1:9200",
    ).rstrip("/")
    elasticsearch_index = os.getenv(
        "ELASTICSEARCH_INDEX",
        "llmkb_knowledge_v1",
    ).lower()
    elasticsearch_read_alias = os.getenv(
        "ELASTICSEARCH_READ_ALIAS",
        "llmkb_knowledge_read",
    ).lower()
    elasticsearch_username = os.getenv("ELASTICSEARCH_USERNAME", "")
    elasticsearch_password = os.getenv("ELASTICSEARCH_PASSWORD", "")
    elasticsearch_verify_ssl = _env_bool("ELASTICSEARCH_VERIFY_SSL")
    elasticsearch_analyzer = os.getenv("ELASTICSEARCH_ANALYZER", "standard")

    # Milvus vector retrieval
    milvus_enabled = _env_bool("MILVUS_ENABLED", True)
    milvus_url = os.getenv("MILVUS_URL", "http://127.0.0.1:19530").rstrip("/")
    milvus_collection = os.getenv(
        "MILVUS_COLLECTION",
        "llmkb_knowledge_v1",
    ).lower()
    milvus_read_alias = os.getenv(
        "MILVUS_READ_ALIAS",
        "llmkb_knowledge_read",
    ).lower()
    milvus_token = os.getenv("MILVUS_TOKEN", "")


settings = Settings()
