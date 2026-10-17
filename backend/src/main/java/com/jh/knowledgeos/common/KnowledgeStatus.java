package com.jh.knowledgeos.common;

/**
 * Canonical lifecycle states for governed knowledge objects.
 */
public enum KnowledgeStatus {

    DRAFT,
    UNCERTAIN,
    PENDING_REVIEW,
    APPROVED,
    EFFECTIVE,
    CONFLICTED,
    REJECTED,
    SUPERSEDED,
    EXPIRED,
    INVALID;

    /**
     * Whether an object may be recalled by retrieval.
     *
     * DRAFT and UNCERTAIN are included on purpose: they are what freshly generated knowledge
     * actually carries — a card is created as DRAFT and a claim that could not be tied to a
     * chunk deterministically is UNCERTAIN. Treating them as unretrievable makes a library
     * look empty right after ingestion and leaves the card/claim channels with nothing to
     * recall until every object is reviewed by hand. Governance still decides what an answer
     * may quote: rejected, superseded, expired and invalid stay out.
     */
    public static boolean isRetrievable(String status) {
        return DRAFT.name().equals(status) || UNCERTAIN.name().equals(status)
                || APPROVED.name().equals(status) || EFFECTIVE.name().equals(status);
    }
}
