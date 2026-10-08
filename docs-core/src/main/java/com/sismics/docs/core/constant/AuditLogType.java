package com.sismics.docs.core.constant;

/**
 * Audit log types. 
 *
 * @author bgamard 
 */
public enum AuditLogType {
    /**
     * Create.
     */
    CREATE,

    /**
     * Update.
     */
    UPDATE,

    /**
     * Delete.
     */
    DELETE,

    /**
     * View (a user opened a document).
     */
    VIEW,

    /**
     * Download (a user downloaded a file / file-zip / original).
     */
    DOWNLOAD,

    /**
     * A user requested temporary access (borrow) to a document they only have SEARCHABLE on.
     */
    BORROW_REQUEST,

    /**
     * An approver granted a borrow request, granting a temporary ACL.
     */
    BORROW_APPROVED,

    /**
     * An approver rejected a borrow request.
     */
    BORROW_REJECTED,

    /**
     * A borrow expired; the temporary ACL was revoked by the BorrowExpiryService.
     */
    BORROW_EXPIRED,

    /**
     * The requester withdrew a pending request before it was decided.
     */
    BORROW_CANCELLED
}
