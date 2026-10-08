package com.sismics.docs.core.constant;

/**
 * Lifecycle states of a {@link com.sismics.docs.core.model.jpa.BorrowRequest}.
 *
 * State machine:
 *   PENDING    → APPROVED  (approver grants it)
 *   PENDING    → REJECTED  (approver refuses it)
 *   PENDING    → CANCELLED (requester withdraws it)
 *   APPROVED   → EXPIRED   (BorrowExpiryService sweeps past BOR_EXPIRYDATE_D)
 *
 * Terminal states: REJECTED, CANCELLED, EXPIRED.
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
    CANCELLED
}