package com.sismics.docs.core.constant;

/**
 * ACL type.
 *
 * @author bgamard 
 */
public enum AclType {
    /**
     * User created ACL.
     */
    USER,

    /**
     * ACL created by the routing module.
     */
    ROUTING,

    /**
     * ACL granted by an approved borrow request. Same shape as USER but tagged so the
     * BorrowExpiryService can find and soft-delete it when the borrow expires.
     */
    BORROW
}
