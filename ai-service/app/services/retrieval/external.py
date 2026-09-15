"""Compatibility exports for external retrieval providers.

New code should import the vendor-specific provider module directly.
"""
from app.services.retrieval.elasticsearch_provider import ElasticsearchProvider, get_elasticsearch_provider
from app.services.retrieval.milvus_provider import MilvusProvider, get_milvus_provider

__all__ = ["ElasticsearchProvider", "MilvusProvider", "get_elasticsearch_provider", "get_milvus_provider"]
