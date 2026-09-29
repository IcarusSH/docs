package com.sismics.security;

import java.util.Set;

import com.sismics.docs.core.constant.Constants;
import org.joda.time.DateTimeZone;

/**
 * Authenticated users principal.
 *
 * @author jtremeaux
 */
public class UserPrincipal implements IPrincipal {
    /**
     * ID of the user.
     */
    private String id;

    /**
     * Username of the user.
     */
    private String name;

    /**
     * Timezone of the principal.
     */
    private DateTimeZone dateTimeZone;

    /**
     * Email of the principal.
     */
    private String email;

    /**
     * User base functions.
     */
    private Set<String> baseFunctionSet;

    /**
     * User groups.
     */
    private Set<String> groupIdSet;

    /**
     * When this principal is impersonating another user (admin "log in as" feature), this is
     * the id of the real signed-in user. Null when not impersonating.
     */
    private String realUserId;

    /**
     * When this principal is impersonating another user, this is the username of the real
     * signed-in user. Null when not impersonating.
     */
    private String realUserName;
    
    /**
     * Constructor of UserPrincipal.
     * 
     * @param id ID of the user
     * @param name Usrename of the user
     */
    public UserPrincipal(String id, String name) {
        this.id = id;
        this.name = name;
    }
    
    @Override
    public boolean isAnonymous() {
        return false;
    }

    @Override
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    @Override
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Override
    public DateTimeZone getDateTimeZone() {
        return dateTimeZone;
    }

    public void setDateTimeZone(DateTimeZone dateTimeZone) {
        this.dateTimeZone = dateTimeZone;
    }

    @Override
    public String getEmail() {
        return email;
    }
    
    public void setEmail(String email) {
        this.email = email;
    }

    public Set<String> getBaseFunctionSet() {
        return baseFunctionSet;
    }

    public void setBaseFunctionSet(Set<String> baseFunctionSet) {
        this.baseFunctionSet = baseFunctionSet;
    }

    @Override
    public Set<String> getGroupIdSet() {
        return groupIdSet;
    }
    
    public void setGroupIdSet(Set<String> groupIdSet) {
        this.groupIdSet = groupIdSet;
    }

    @Override
    public boolean isGuest() {
        return Constants.GUEST_USER_ID.equals(id);
    }

    /**
     * Returns the id of the real signed-in user when impersonating, or null otherwise.
     */
    public String getRealUserId() {
        return realUserId;
    }

    public void setRealUserId(String realUserId) {
        this.realUserId = realUserId;
    }

    /**
     * Returns the username of the real signed-in user when impersonating, or null otherwise.
     */
    public String getRealUserName() {
        return realUserName;
    }

    public void setRealUserName(String realUserName) {
        this.realUserName = realUserName;
    }

    /**
     * True if this principal is currently impersonating another user.
     */
    public boolean isImpersonating() {
        return realUserId != null;
    }
}
