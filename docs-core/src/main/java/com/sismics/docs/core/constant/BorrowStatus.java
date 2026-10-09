package com.sismics.docs.core.constant;

/**
 * Lifecycle states of a {@link com.sismics.docs.core.model.jpa.BorrowRequest}.
 *
 * State machine:
 *   PENDING    → APPROVED  (approver grants it)
 *   PENDING    → REJECTED  (approver refuses it)
 *   PENDING    → CANCELLED (requester withdraws it)
 *   APPROVED   → EXPIRED   (BorrowExpiryService sweeps past BOR_EXPIRYDATE_D)
 *   APPROVED   → REVOKED   (admin manually revokes a live grant)
 *
 * Terminal states: REJECTED, CANCELLED, EXPIRED, REVOKED.
 *
 * @author Claude
 */
public enum BorrowStatus {
    /**
     * Awaiting the approver's decision.
     */
    PENDING,

    /**
     * Approver granted the request; a temporary T_ACL row of AclType.BORROW exists.
     */
    APPROVED,

    /**
     * Approver refused the request.
     */
    REJECTED,

    /**
     * BorrowExpiryService revoked the temporary ACL after BOR_EXPIRYDATE_D passed.
     */
    EXPIRED,

    /**
     * Requester withdrew the request before it was decided.
     */
    CANCELLED,

    /**
     * Admin manually revoked a previously APPROVED grant. The BORROW ACL is soft-deleted
     * and the requester loses access immediately. Distinct from CANCELLED (requester-side
     * action) and EXPIRED (system-side timed action).
     */
    REVOKED
}