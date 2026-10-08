package com.sismics.docs.core.service;

import com.google.common.util.concurrent.AbstractScheduledService;
import com.sismics.docs.core.constant.AclType;
import com.sismics.docs.core.constant.AuditLogType;
import com.sismics.docs.core.constant.BorrowStatus;
import com.sismics.docs.core.dao.AclDao;
import com.sismics.docs.core.dao.BorrowRequestDao;
import com.sismics.docs.core.model.jpa.BorrowRequest;
import com.sismics.docs.core.util.AuditLogUtil;
import com.sismics.docs.core.util.TransactionUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Sweeps approved borrows past their expiry timestamp.
 *
 * On every iteration:
 *   1. Find all APPROVED requests with BOR_EXPIRYDATE_D &lt; now()
 *   2. Soft-delete the associated T_ACL row (AclType.BORROW) — this revokes the temporary
 *      permission immediately
 *   3. Mark the request EXPIRED
 *   4. Write an audit log entry (AuditLogType.BORROW_EXPIRED)
 *
 * Frequency: 1 minute after the previous iteration completes (fixed delay, not fixed rate,
 * so a long iteration does not pile up overlapping runs).
 *
 * Registered as a managed service in AppContext.startUp() so its lifecycle matches the JVM.
 *
 * @author Claude
 */
public class BorrowExpiryService extends AbstractScheduledService {
    private static final Logger log = LoggerFactory.getLogger(BorrowExpiryService.class);

    @Override
    protected void startUp() {
        log.info("Borrow expiry service starting up");
    }

    @Override
    protected void shutDown() {
        log.info("Borrow expiry service shutting down");
    }

    @Override
    protected void runOneIteration() {
        try {
            TransactionUtil.handle(() -> {
                BorrowRequestDao borrowDao = new BorrowRequestDao();
                AclDao aclDao = new AclDao();
                List<BorrowRequest> expired = borrowDao.findExpired(new Date());
                for (BorrowRequest req : expired) {
                    expireOne(req, aclDao);
                }
                if (!expired.isEmpty()) {
                    log.info("Expired {} borrow request(s)", expired.size());
                }
            });
        } catch (Throwable e) {
            log.error("Exception during borrow expiry iteration", e);
        }
    }

    private void expireOne(BorrowRequest req, AclDao aclDao) {
        if (req.getAclId() != null) {
            // Soft-delete the temporary ACL row. AclDao.delete matches by (sourceId, perm,
            // targetId, type) so it only touches the borrow-granted row, never any USER-typed
            // ACL on the same document.
            aclDao.delete(req.getDocumentId(), req.getPerm(),
                    req.getRequesterId(), "system", AclType.BORROW);
        }
        req.setStatus(BorrowStatus.EXPIRED);
        new BorrowRequestDao().update(req);
        AuditLogUtil.create(req, AuditLogType.BORROW_EXPIRED, "system");
    }

    @Override
    protected Scheduler scheduler() {
        return Scheduler.newFixedDelaySchedule(0, 1, TimeUnit.MINUTES);
    }
}