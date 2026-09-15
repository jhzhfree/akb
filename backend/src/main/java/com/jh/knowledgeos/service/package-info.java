/**
 * Application services and infrastructure adapters.
 *
 * <p>Business-state transitions belong in focused services. Slow AI, graph, and search
 * calls must not run while holding database transactions. Index writes are delivered
 * through the transactional Outbox unless a method is explicitly a rebuild/diagnostic path.</p>
 */
package com.jh.knowledgeos.service;
