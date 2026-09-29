"""Query-pipeline boundary shared by retrieval and generation orchestration."""

class QueryPipeline:
    """Small boundary object used to keep orchestration dependencies explicit."""
    def __init__(self, retriever, router, rewriter):
        self.retriever = retriever
        self.router = router
        self.rewriter = rewriter
