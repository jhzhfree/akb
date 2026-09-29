"""Trace payload helpers for query-pipeline observability."""

def stage(key, label, latency_ms=None, input_count=None, output_count=None, detail=None, errors=None):
    """Build one stable trace-stage envelope."""
    return {
        "key": key, "label": label, "latencyMs": latency_ms,
        "inputCount": input_count, "outputCount": output_count,
        "detail": detail or {}, "errors": errors or [],
    }
