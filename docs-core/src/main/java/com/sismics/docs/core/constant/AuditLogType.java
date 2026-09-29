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
    DOWNLOAD
}
