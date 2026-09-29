"""Tests for the console-editable Elasticsearch / Milvus switches.

Turning an engine on or off changes what a query can recall, so the contract
pinned here is: a save is persisted, takes effect in the running process without
a restart, never echoes a credential back, and is re-applied on restart instead
of being silently overridden by the environment.
"""

import pytest
from app.core import retrieval_settings as rs
from app.core.config import settings as env_settings
from app.services.retrieval.elasticsearch_provider import get_elasticsearch_provider
from app.services.retrieval.milvus_provider import get_milvus_provider


@pytest.fixture(autouse=True)
def isolated_retrieval(tmp_path, monkeypatch):
    """Redirect the settings file and restore every patched setting afterwards."""
    monkeypatch.setattr(rs, "SETTINGS_FILE", tmp_path / "retrieval_settings.json")
    rs.reset_cache()
    snapshot = {
        key: getattr(env_settings, key)
        for key in (
            "retrieval_mode",
            "elasticsearch_enabled",
            "elasticsearch_url",
            "elasticsearch_index",
            "elasticsearch_username",
            "elasticsearch_password",
            "milvus_enabled",
            "milvus_url",
            "milvus_collection",
        )
    }
    yield
    for key, value in snapshot.items():
        setattr(env_settings, key, value)
    get_elasticsearch_provider.cache_clear()
    get_milvus_provider.cache_clear()
    rs.reset_cache()


def test_switch_off_disables_the_provider_without_a_restart():
    """A console save must reach the provider singleton used by the next query."""
    rs.save_retrieval_settings({"elasticsearch": {"enabled": False}, "milvus": {"enabled": False}})
    assert env_settings.elasticsearch_enabled is False
    assert env_settings.milvus_enabled is False
    assert get_elasticsearch_provider().enabled is False
    assert get_milvus_provider().enabled is False


def test_switch_on_applies_connection_details():
    saved = rs.save_retrieval_settings({
        "elasticsearch": {
            "enabled": True,
            "url": "http://es.internal:9200/",
            "index": "Knowledge_V2",
            "username": "elastic",
            "password": "secret-value",
        },
        "milvus": {"enabled": True, "url": "http://mv.internal:19530", "collection": "Vectors_V2"},
    })
    assert env_settings.elasticsearch_enabled is True
    # Trailing slashes and mixed case are normalised the same way the env loader does.
    assert env_settings.elasticsearch_url == "http://es.internal:9200"
    assert env_settings.elasticsearch_index == "knowledge_v2"
    assert env_settings.milvus_collection == "vectors_v2"
    assert rs.load_retrieval_settings().elasticsearch.password == "secret-value"
    # The response never hands a credential back to the browser.
    public = rs.public_retrieval_settings(saved)
    assert "secret-value" not in public["elasticsearch"]["password"]
    assert public["elasticsearch"]["passwordSet"] is True


def test_masked_secret_keeps_the_stored_value():
    rs.save_retrieval_settings({"elasticsearch": {"username": "elastic", "password": "first-secret"}})
    rs.save_retrieval_settings({"elasticsearch": {"password": rs.mask_secret("first-secret")}})
    assert rs.load_retrieval_settings().elasticsearch.password == "first-secret"
    assert env_settings.elasticsearch_password == "first-secret"


def test_persisted_file_survives_reload_and_is_reapplied():
    rs.save_retrieval_settings({"elasticsearch": {"enabled": False}})
    rs.reset_cache()
    reloaded = rs.load_retrieval_settings(force=True)
    assert reloaded.elasticsearch.enabled is False
    assert rs.public_retrieval_settings()["source"] == "file"
    # A restart replays the file: env-only seeding must not re-enable the engine.
    env_settings.elasticsearch_enabled = True
    rs.apply_retrieval_settings()
    assert env_settings.elasticsearch_enabled is False


def test_invalid_mode_is_rejected():
    with pytest.raises(Exception):
        rs.save_retrieval_settings({"mode": "sometimes"})
