package com.sismics.docs.core.util;

import com.sismics.docs.core.constant.AuditLogType;
import com.sismics.docs.core.dao.AuditLogDao;
import com.sismics.docs.core.model.jpa.AuditLog;
import com.sismics.docs.core.model.jpa.Loggable;
import com.sismics.util.context.ThreadLocalContext;

import jakarta.persistence.EntityManager;

/**
 * Audit log utilities.
 *
 * @author bgamard
 */
public class AuditLogUtil {
    /**
     * Create an audit log.
     *
     * @param loggable Loggable
     * @param type Audit log type
     * @param userId User ID
     */
    public static void create(Loggable loggable, AuditLogType type, String userId) {
        if (userId == null) {
            userId = "admin";
        }

        // Get the entity ID
        EntityManager em = ThreadLocalContext.get().getEntityManager();
        String entityId = (String) em.getEntityManagerFactory().getPersistenceUnitUtil().getIdentifier(loggable);

        // Create the audit log
        AuditLogDao auditLogDao = new AuditLogDao();
        AuditLog auditLog = new AuditLog();
        auditLog.setUserId(userId);
        auditLog.setEntityId(entityId);
        auditLog.setEntityClass(loggable.getClass().getSimpleName());
        auditLog.setType(type);
        auditLog.setMessage(loggable.toMessage());
        auditLogDao.create(auditLog);
    }

    /**
     * Log a document view event. We can't reuse {@link #create} because we don't have a
     * managed Document entity here (the resource returned a DTO), and the query that lists
     * per-document activity matches by entity id alone, so this is enough to surface in the
     * Activity tab.
     */
    public static void createViewLog(String documentId, String userId) {
        if (userId == null) {
            userId = "admin";
        }
        AuditLog auditLog = new AuditLog();
        auditLog.setUserId(userId);
        auditLog.setEntityId(documentId);
        auditLog.setEntityClass("Document");
        auditLog.setType(AuditLogType.VIEW);
        auditLog.setMessage("viewed document");
        new AuditLogDao().create(auditLog);
    }

    /**
     * Log a document / file download event.
     */
    public static void createDownloadLog(String entityId, String entityClass, String userId) {
        if (userId == null) {
            userId = "admin";
        }
        AuditLog auditLog = new AuditLog();
        auditLog.setUserId(userId);
        auditLog.setEntityId(entityId);
        auditLog.setEntityClass(entityClass);
        auditLog.setType(AuditLogType.DOWNLOAD);
        auditLog.setMessage("downloaded " + entityClass.toLowerCase());
        new AuditLogDao().create(auditLog);
    }
}
