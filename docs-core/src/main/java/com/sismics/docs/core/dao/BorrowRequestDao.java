package com.sismics.docs.core.dao;

import com.sismics.docs.core.constant.BorrowStatus;
import com.sismics.docs.core.constant.PermType;
import com.sismics.docs.core.model.jpa.BorrowRequest;
import com.sismics.util.context.ThreadLocalContext;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * DAO for {@link BorrowRequest}.
 *
 * Operations are intended to run inside an existing transaction (ThreadLocalContext).
 *
 * @author Claude
 */
public class BorrowRequestDao {

    /**
     * Persist a new borrow request and return its generated id.
     *
     * @param req the request (id and createDate are set by this method)
     * @return generated id
     */
    public String create(BorrowRequest req) {
        req.setId(UUID.randomUUID().toString());
        req.setCreateDate(new Date());

        EntityManager em = ThreadLocalContext.get().getEntityManager();
        em.persist(req);
        return req.getId();
    }

    /**
     * Fetch a single borrow request by id.
     *
     * @param id request id
     * @return BorrowRequest or null
     */
    public BorrowRequest getById(String id) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        return em.find(BorrowRequest.class, id);
    }

    /**
     * Update an existing request (must have an id).
     */
    public void update(BorrowRequest req) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        em.merge(req);
    }

    /**
     * Requests where the supplied approver is the target, status = PENDING, and not soft-deleted.
     * Used to drive the bell-icon dropdown in the navbar.
     *
     * @param approverId approver user id
     * @return list of pending requests, oldest first
     */
    @SuppressWarnings("unchecked")
    public List<BorrowRequest> findPendingForApprover(String approverId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createQuery(
                "select b from BorrowRequest b " +
                "where b.approverId = :approverId and b.status = :status and b.deleteDate is null " +
                "order by b.createDate asc");
        q.setParameter("approverId", approverId);
        q.setParameter("status", BorrowStatus.PENDING);
        return q.getResultList();
    }

    /**
     * Requests initiated by the supplied user, all statuses, most recent first.
     */
    @SuppressWarnings("unchecked")
    public List<BorrowRequest> findByRequester(String requesterId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createQuery(
                "select b from BorrowRequest b " +
                "where b.requesterId = :requesterId and b.deleteDate is null " +
                "order by b.createDate desc");
        q.setParameter("requesterId", requesterId);
        return q.getResultList();
    }

    /**
     * Approved borrows whose expiryDate is in the past. The BorrowExpiryService consumes this.
     *
     * @param now timestamp to compare against
     * @return list of expired but not-yet-swept requests
     */
    @SuppressWarnings("unchecked")
    public List<BorrowRequest> findExpired(Date now) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createQuery(
                "select b from BorrowRequest b " +
                "where b.status = :status and b.expiryDate is not null and b.expiryDate < :now " +
                "and b.deleteDate is null");
        q.setParameter("status", BorrowStatus.APPROVED);
        q.setParameter("now", now);
        return q.getResultList();
    }

    /**
     * Latest future expiry among active APPROVED borrows this requester holds on this document.
     * Returns null when the user has no active borrow — i.e. they have permanent (non-borrow)
     * access or no access at all. Drives the document detail page's "last access time" hint.
     *
     * @param documentId  target document
     * @param requesterId user being granted temporary access
     * @return latest expiry, or null if no active borrow exists
     */
    public Date findLatestActiveExpiry(String documentId, String requesterId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createQuery(
                "select max(b.expiryDate) from BorrowRequest b " +
                "where b.documentId = :docId and b.requesterId = :reqId " +
                "and b.status = :approved and b.deleteDate is null " +
                "and b.expiryDate is not null and b.expiryDate > :now");
        q.setParameter("docId", documentId);
        q.setParameter("reqId", requesterId);
        q.setParameter("approved", BorrowStatus.APPROVED);
        q.setParameter("now", new Date());
        try {
            return (Date) q.getSingleResult();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Admin-only: list every borrow request, optionally filtered by status. Drives the borrow
     * center page. Order is most recent first.
     *
     * @param statusFilter null = all statuses; otherwise filter to one status
     * @return list of borrow requests, newest first
     */
    @SuppressWarnings("unchecked")
    public List<BorrowRequest> findAll(BorrowStatus statusFilter) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        StringBuilder jpql = new StringBuilder("select b from BorrowRequest b where b.deleteDate is null");
        if (statusFilter != null) {
            jpql.append(" and b.status = :status");
        }
        jpql.append(" order by b.createDate desc");
        Query q = em.createQuery(jpql.toString());
        if (statusFilter != null) {
            q.setParameter("status", statusFilter);
        }
        return q.getResultList();
    }

    /**
     * True if the requester already has a still-active (PENDING or APPROVED-not-yet-expired)
     * borrow request for this document. Used to reject duplicate submissions.
     */
    @SuppressWarnings("unchecked")
    public boolean hasActiveRequest(String documentId, String requesterId) {
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        Query q = em.createQuery(
                "select count(b) from BorrowRequest b " +
                "where b.documentId = :docId and b.requesterId = :reqId and b.deleteDate is null " +
                "and (b.status = :pending " +
                "or (b.status = :approved and (b.expiryDate is null or b.expiryDate > :now)))");
        q.setParameter("docId", documentId);
        q.setParameter("reqId", requesterId);
        q.setParameter("pending", BorrowStatus.PENDING);
        q.setParameter("approved", BorrowStatus.APPROVED);
        q.setParameter("now", new Date());
        Long count = (Long) q.getSingleResult();
        return count > 0;
    }

    /**
     * Decide whether the supplied approverId is allowed to be named on a borrow request for
     * the given document. Two paths are accepted:
     *   - the user holds the ADMIN base function, or
     *   - the user holds {@link PermType#WRITE} on the document.
     *
     * @return true if eligible
     */
    public boolean isEligibleApprover(String approverId, String documentId) {
        if (approverId == null || documentId == null) {
            return false;
        }
        // ADMIN is identified by USE_IDROLE_C = 'admin' (seeded at dbupdate-008).
        UserDao userDao = new UserDao();
        com.sismics.docs.core.model.jpa.User u = userDao.getById(approverId);
        if (u == null || u.getDeleteDate() != null) {
            return false;
        }
        if ("admin".equals(u.getRoleId())) {
            return true;
        }
        // Otherwise the approver must currently hold WRITE on the document.
        return new AclDao().checkPermission(documentId, PermType.WRITE, java.util.Collections.singletonList(approverId));
    }
}