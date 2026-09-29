package com.sismics.docs.core.util;

import com.sismics.docs.core.constant.AclType;
import com.sismics.docs.core.constant.PermType;
import com.sismics.docs.core.dao.AclDao;
import com.sismics.docs.core.dao.DocumentDao;
import com.sismics.docs.core.model.jpa.Acl;
import com.sismics.docs.core.model.jpa.Document;

/**
 * Document utilities.
 *
 * @author bgamard
 */
public class DocumentUtil {
    /**
     * Create a document and add the base ACLs.
     *
     * The creator, the seeded admin user and the seeded administrators group all
     * receive a WRITE permission by default — this matches the requirement that
     * administrators should have all permissions open on every resource.
     *
     * @param document Document
     * @param userId User creating the document
     * @return Created document
     */
    public static Document createDocument(Document document, String userId) {
        DocumentDao documentDao = new DocumentDao();
        String documentId = documentDao.create(document, userId);

        AclDao aclDao = new AclDao();

        // Grant WRITE to the creator (WRITE implicitly includes the lower permission levels)
        grantDefaultWrite(aclDao, documentId, userId, userId);

        // Always grant WRITE to the seeded admin user and administrators group
        grantDefaultWrite(aclDao, documentId, "admin", userId);
        grantDefaultWrite(aclDao, documentId, "administrators", userId);

        return document;
    }

    private static void grantDefaultWrite(AclDao aclDao, String sourceId, String targetId, String userId) {
        Acl acl = new Acl();
        acl.setPerm(PermType.WRITE);
        // ACL_TYPE_C is the same for users and groups; AclDao.getBySourceId resolves the
        // actual target type at read time by JOIN-ing T_USER / T_GROUP / T_SHARE on
        // ACL_TARGETID_C. There is no separate "GROUP" enum value today.
        acl.setType(AclType.USER);
        acl.setSourceId(sourceId);
        acl.setTargetId(targetId);
        aclDao.create(acl, userId);
    }
}
