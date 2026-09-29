package com.sismics.util.filter;

import com.google.common.collect.Sets;
import com.sismics.docs.core.constant.Constants;
import com.sismics.docs.core.dao.GroupDao;
import com.sismics.docs.core.dao.RoleBaseFunctionDao;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.dao.criteria.GroupCriteria;
import com.sismics.docs.core.dao.dto.GroupDto;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.security.AnonymousPrincipal;
import com.sismics.security.UserPrincipal;
import org.joda.time.DateTimeZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An abstract security filter for user authentication, that injects corresponding users into the request.
 * Successfully authenticated users are injected as UserPrincipal, or as AnonymousPrincipal otherwise.
 * If an user has already been authenticated for the request, no further authentication attempt is made.
 *
 * @author pacien
 * @author jtremeaux
 */
public abstract class SecurityFilter implements Filter {
    /**
     * Name of the attribute containing the principal.
     */
    public static final String PRINCIPAL_ATTRIBUTE = "principal";

    /**
     * Request attribute key carrying the resolved authentication token ID. Subclasses that need
     * to know the current token (e.g. to look up impersonation state) can read this.
     */
    public static final String AUTH_TOKEN_ID_ATTRIBUTE = "authTokenId";

    /**
     * Logger.
     */
    static final Logger LOG = LoggerFactory.getLogger(SecurityFilter.class);

    /**
     * In-memory map of impersonation state, keyed by the signed-in admin's authentication token
     * ID. Value is the user ID being impersonated. Cleared when the admin logs out or stops
     * impersonating. Survives only as long as the JVM is up.
     */
    private static final Map<String, String> IMPERSONATION_BY_TOKEN = new ConcurrentHashMap<>();

    /**
     * Start impersonating: subsequent requests authenticated with this token will see
     * the impersonated user's data instead of the real admin's.
     */
    public static void startImpersonating(String authTokenId, String impersonatedUserId) {
        if (authTokenId != null && impersonatedUserId != null) {
            IMPERSONATION_BY_TOKEN.put(authTokenId, impersonatedUserId);
        }
    }

    /**
     * Stop impersonating for the given token. Idempotent.
     */
    public static void stopImpersonating(String authTokenId) {
        if (authTokenId != null) {
            IMPERSONATION_BY_TOKEN.remove(authTokenId);
        }
    }

    /**
     * Returns the impersonated user ID for the given token, or null if not impersonating.
     */
    public static String getImpersonatedUserId(String authTokenId) {
        return authTokenId == null ? null : IMPERSONATION_BY_TOKEN.get(authTokenId);
    }

    /**
     * Returns true if the supplied request has an UserPrincipal.
     *
     * @param request HTTP request
     * @return True if the supplied request has an UserPrincipal
     */
    private boolean hasIdentifiedUser(HttpServletRequest request) {
        return request.getAttribute(PRINCIPAL_ATTRIBUTE) instanceof UserPrincipal;
    }

    /**
     * Injects the given user into the request, with the appropriate authentication state.
     *
     * @param request HTTP request
     * @param user nullable User to inject
     * @param authTokenId authentication token id (used for impersonation lookup)
     */
    private void injectUser(HttpServletRequest request, User user, String authTokenId) {
        // Check if the user is still valid
        if (user != null && user.getDeleteDate() == null && user.getDisableDate() == null) {
            // If the admin started impersonating another user, load that user as the principal
            // and remember the real admin in the principal so audit + stop-impersonation work.
            String impersonatedUserId = getImpersonatedUserId(authTokenId);
            if (impersonatedUserId != null && !impersonatedUserId.equals(user.getId())) {
                User impersonated = new UserDao().getById(impersonatedUserId);
                if (impersonated != null && impersonated.getDeleteDate() == null && impersonated.getDisableDate() == null) {
                    UserPrincipal real = new UserPrincipal(user.getId(), user.getUsername());
                    UserPrincipal imp = buildUserPrincipal(impersonated);
                    imp.setRealUserId(real.getId());
                    imp.setRealUserName(real.getName());
                    request.setAttribute(PRINCIPAL_ATTRIBUTE, imp);
                    if (authTokenId != null) {
                        request.setAttribute(AUTH_TOKEN_ID_ATTRIBUTE, authTokenId);
                    }
                    return;
                }
                // The impersonated user no longer exists; clear stale state.
                stopImpersonating(authTokenId);
            }
            injectAuthenticatedUser(request, user);
        } else {
            injectAnonymousUser(request);
        }
        if (authTokenId != null) {
            request.setAttribute(AUTH_TOKEN_ID_ATTRIBUTE, authTokenId);
        }
    }

    /**
     * Inject an authenticated user into the request attributes.
     *
     * @param request HTTP request
     * @param user User to inject
     */
    private void injectAuthenticatedUser(HttpServletRequest request, User user) {
        request.setAttribute(PRINCIPAL_ATTRIBUTE, buildUserPrincipal(user));
    }

    /**
     * Build a UserPrincipal for the given user, with groups, base functions, and email loaded.
     */
    private UserPrincipal buildUserPrincipal(User user) {
        UserPrincipal userPrincipal = new UserPrincipal(user.getId(), user.getUsername());

        // Add groups
        GroupDao groupDao = new GroupDao();
        Set<String> groupRoleIdSet = new HashSet<>();
        List<GroupDto> groupDtoList = groupDao.findByCriteria(new GroupCriteria()
                .setUserId(user.getId())
                .setRecursive(true), null);
        Set<String> groupIdSet = Sets.newHashSet();
        for (GroupDto groupDto : groupDtoList) {
            groupIdSet.add(groupDto.getId());
            if (groupDto.getRoleId() != null) {
                groupRoleIdSet.add(groupDto.getRoleId());
            }
        }
        userPrincipal.setGroupIdSet(groupIdSet);

        // Add base functions
        groupRoleIdSet.add(user.getRoleId());
        RoleBaseFunctionDao userBaseFunction = new RoleBaseFunctionDao();
        Set<String> baseFunctionSet = userBaseFunction.findByRoleId(groupRoleIdSet);
        userPrincipal.setBaseFunctionSet(baseFunctionSet);

        // Add email
        userPrincipal.setEmail(user.getEmail());

        return userPrincipal;
    }

    /**
     * Inject an anonymous user into the request attributes.
     *
     * @param request HTTP request
     */
    private void injectAnonymousUser(HttpServletRequest request) {
        AnonymousPrincipal anonymousPrincipal = new AnonymousPrincipal();
        anonymousPrincipal.setDateTimeZone(DateTimeZone.forID(Constants.DEFAULT_TIMEZONE_ID));

        request.setAttribute(PRINCIPAL_ATTRIBUTE, anonymousPrincipal);
    }

    @Override
    public void init(FilterConfig filterConfig) {
        // NOP
    }

    @Override
    public void destroy() {
        // NOP
    }

    @Override
    public final void doFilter(ServletRequest req, ServletResponse response, FilterChain filterChain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;

        if (!hasIdentifiedUser(request)) {
            AuthenticationResult result = authenticateWithToken(request);
            injectUser(request, result.user, result.tokenId);
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Authenticates an user from the given request parameters.
     *
     * @param request HTTP request
     * @return nullable User
     */
    protected abstract User authenticate(HttpServletRequest request);

    /**
     * Authenticates the user and remembers the token ID so impersonation can be applied later.
     * Subclasses override this if they have a notion of token id.
     */
    protected AuthenticationResult authenticateWithToken(HttpServletRequest request) {
        return new AuthenticationResult(authenticate(request), null);
    }

    /**
     * Holder for the result of an authenticate call: the user, plus the token id if known.
     */
    protected static class AuthenticationResult {
        final User user;
        final String tokenId;
        AuthenticationResult(User user, String tokenId) {
            this.user = user;
            this.tokenId = tokenId;
        }
    }

}