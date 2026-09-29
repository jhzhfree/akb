"""Stable response-envelope helpers for knowledge queries."""

def build_query_response(payload):
    """Return a response copy so pipeline assembly never leaks a mutable internal mapping."""
    return dict(payload)
