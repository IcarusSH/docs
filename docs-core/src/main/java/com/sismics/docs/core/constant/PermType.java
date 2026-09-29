package com.sismics.docs.core.constant;

/**
 * Permissions, ordered hierarchically from least to most powerful.
 *
 * A user holding a given level implicitly holds every lower level:
 *   WRITE          implies DOWNLOADABLE, READ, SEARCHABLE
 *   DOWNLOADABLE   implies READ, SEARCHABLE
 *   READ           implies SEARCHABLE
 *   SEARCHABLE     implies nothing else (just findable in lists / search)
 *
 * Each ACL row stores exactly one of these values in T_ACL.ACL_PERM_C.
 *
 * @author bgamard
 */
public enum PermType {
    /**
     * User can find the document in lists and search results, but cannot open it.
     */
    SEARCHABLE(1),

    /**
     * Read document.
     */
    READ(2),

    /**
     * Download the original file in addition to read.
     */
    DOWNLOADABLE(3),

    /**
     * Write document.
     */
    WRITE(4);

    private final int level;

    PermType(int level) {
        this.level = level;
    }

    /**
     * Numeric level for ordering. Higher means more permission.
     */
    public int getLevel() {
        return level;
    }

    /**
     * Returns true if this permission is at least as strong as the required one.
     * Example: WRITE.implies(READ) == true; SEARCHABLE.implies(WRITE) == false.
     */
    public boolean implies(PermType required) {
        return this.level >= required.level;
    }
}
