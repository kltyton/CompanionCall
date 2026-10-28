package com.kltyton.companioncall.navigation;

/**
 * Lifecycle of one custom navigation session.
 *
 * <p>Terminal states are ARRIVED, CANCELLED, LIMIT_REACHED and NO_ROUTE_IN_REGION. ARRIVED is
 * re-checked by {@code drive} and re-armed if the entity leaves the arrival radius. NO_ROUTE and
 * LIMIT stay terminal for an unchanged goal until {@code restart()} or a materially different
 * goal is requested. IDLE is the state before the first request.</p>
 */
public enum NavigationStatus {
    /** No request has been made yet. */
    IDLE,
    SEARCHING,
    MOVING,
    ARRIVED,
    /** A required chunk is not loaded; planning resumes without forcing a load. */
    WAITING_REGION,
    LIMIT_REACHED,
    NO_ROUTE_IN_REGION,
    CANCELLED
}
