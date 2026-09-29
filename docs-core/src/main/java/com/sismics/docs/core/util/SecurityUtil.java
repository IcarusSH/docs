package com.sismics.docs.core.util;

import com.sismics.docs.core.constant.AclTargetType;
import com.sismics.docs.core.dao.GroupDao;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.model.jpa.Group;
import com.sismics.docs.core.model.jpa.User;

import java.util.List;

/**
 * Security utilities.
 *
 * @author bgamard
 */
public class SecurityUtil {
    /**
     * Sentinel value added to a target-id list when the caller is known to be an
     * administrator (i.e. has the ADMIN base function). Recognised by
     * {@link #skipAclCheck(List)} so any admin user, not only the seeded one
     * (whose USE_ID_C happens to be the literal string "admin"), bypasses ACL.
     */
    public static final String ADMIN_BYPASS_SENTINEL = "__TEEDY_ADMIN_BYPASS__";

    /**
     * Get an ACL target ID from an object name and type.
     *
     * @param name Object name
     * @param type Object type
     * @return Target ID
     */
    public static String getTargetIdFromName(String name, AclTargetType type) {
        switch (type) {
            case USER:
                UserDao userDao = new UserDao();
                User user = userDao.getActiveByUsername(name);
                if (user != null) {
                    return user.getId();
                }
            case GROUP:
                GroupDao groupDao = new GroupDao();
                Group group = groupDao.getActiveByName(name);
                if (group != null) {
                    return group.getId();
                }
        }

        return null;
    }

    /**
     * Return true if the ACL targets provided don't need security checks (administrator users).
     *
     * This honours three signals so that EVERY user with the ADMIN base function bypasses ACL,
     * not just the seeded admin user:
     *   - the literal "admin" user id (legacy: the original admin installation hard-codes
     *     USE_ID_C = "admin")
     *   - the literal "administrators" group id (legacy: same — dbupdate-008-0.sql)
     *   - the {@link ADMIN_BYPASS_SENTINEL} which is added to the target list by the
     *     BaseResource layer when the principal has the ADMIN base function
     *
     * @param targetIdList Target ID list
     * @return True if skip ACL checks
     */
    public static boolean skipAclCheck(List<String> targetIdList) {
        if (targetIdList == null || targetIdList.isEmpty()) {
            return false;
        }
        return targetIdList.contains("admin")
                || targetIdList.contains("administrators")
                || targetIdList.contains(ADMIN_BYPASS_SENTINEL);
    }
}
