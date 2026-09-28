package com.sismics.docs.rest.resource;

import com.google.common.base.Strings;
import com.sismics.docs.core.dao.AuthenticationTokenDao;
import com.sismics.docs.core.model.jpa.AuthenticationToken;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.util.filter.TokenBasedSecurityFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.core.NewCookie;
import org.apache.commons.lang3.StringUtils;

/**
 * Shared helper for issuing Teedy session cookies.
 *
 * Both username/password login (UserResource#login) and SSO callbacks
 * (CasdoorResource#callback) need to create an AuthenticationToken row and
 * produce an auth_token cookie. This util centralizes that logic.
 */
public final class AuthTokenUtil {

    private AuthTokenUtil() {
        // utility class
    }

    /**
     * Build the absolute callback URL for the current request, used as
     * redirect_uri by Casdoor (and other OAuth2 providers in the future).
     *
     * @param request Current HTTP request
     * @return Absolute URL like "http://host:port/context/api/casdoor/callback"
     */
    public static String callbackUri(HttpServletRequest request) {
        String scheme = request.getScheme();
        String host = request.getServerName();
        int port = request.getServerPort();
        String ctx = request.getContextPath();
        return scheme + "://" + host + (port == 80 || port == 443 ? "" : ":" + port)
                + ctx + "/api/casdoor/callback";
    }

    /**
     * Build the URL of Teedy's post-login home page (root context path).
     *
     * @param request Current HTTP request
     * @return Absolute path like "/docs-web/src/"
     */
    public static String homeUri(HttpServletRequest request) {
        return request.getContextPath() + "/";
    }

    /**
     * Issue a session token row and produce the matching auth_token cookie.
     *
     * @param request    Current HTTP request (used for IP / User-Agent capture)
     * @param user       The authenticated user
     * @param longLasted True for "remember me" cookie (20-year expiry);
     *                   false for session cookie (dies on browser close)
     * @return NewCookie to attach to the login response
     */
    public static NewCookie issueCookie(HttpServletRequest request, User user, boolean longLasted) {
        String ip = request.getHeader("x-forwarded-for");
        if (Strings.isNullOrEmpty(ip)) {
            ip = request.getRemoteAddr();
        }
        String userAgent = request.getHeader("user-agent");

        AuthenticationTokenDao authenticationTokenDao = new AuthenticationTokenDao();
        AuthenticationToken authenticationToken = new AuthenticationToken()
                .setUserId(user.getId())
                .setLongLasted(longLasted)
                .setIp(StringUtils.abbreviate(ip, 45))
                .setUserAgent(StringUtils.abbreviate(userAgent, 1000));
        String token = authenticationTokenDao.create(authenticationToken);
        authenticationTokenDao.deleteOldSessionToken(user.getId());

        int maxAge = longLasted ? TokenBasedSecurityFilter.TOKEN_LONG_LIFETIME : -1;
        return new NewCookie(TokenBasedSecurityFilter.COOKIE_NAME, token, "/", null, null, maxAge, false);
    }
}