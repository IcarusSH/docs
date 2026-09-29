package com.sismics.docs.rest.resource;

import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.docs.rest.constant.BaseFunction;
import com.sismics.rest.exception.ClientException;
import com.sismics.rest.exception.ForbiddenClientException;
import com.sismics.rest.exception.ServerException;
import com.sismics.rest.util.ValidationUtil;
import com.sismics.util.filter.SecurityFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.json.Json;
import jakarta.json.JsonObjectBuilder;

/**
 * Admin-only "log in as" feature.
 *
 * Lets an administrator temporarily act as another user so they can verify access control is
 * configured correctly. The session itself still belongs to the real admin (so they can always
 * flip back), but the principal injected into requests is the impersonated user — i.e. ACL
 * checks, role checks, etc. all run as that user.
 *
 * Endpoints:
 *   POST /admin/impersonate  { username }   -> start impersonating
 *   POST /admin/impersonate/stop           -> stop impersonating
 *   GET  /admin/impersonate                -> current status
 *
 * Only admins may impersonate; you cannot impersonate yourself.
 */
@Path("/admin/impersonate")
public class ImpersonateResource extends BaseResource {
    private static final Logger log = LoggerFactory.getLogger(ImpersonateResource.class);

    @Context
    private HttpServletRequest request;

    /**
     * Returns the current impersonation status.
     */
    @GET
    public Response status() {
        requireAdmin();
        String authTokenId = (String) request.getAttribute(SecurityFilter.AUTH_TOKEN_ID_ATTRIBUTE);
        String impersonatedId = authTokenId == null ? null : SecurityFilter.getImpersonatedUserId(authTokenId);
        String impersonatedName = null;
        if (impersonatedId != null) {
            User u = new UserDao().getById(impersonatedId);
            if (u != null) {
                impersonatedName = u.getUsername();
            }
        }
        JsonObjectBuilder body = Json.createObjectBuilder()
                .add("impersonating", impersonatedId != null)
                .add("user_id", impersonatedId == null ? "" : impersonatedId)
                .add("username", impersonatedName == null ? "" : impersonatedName);
        return Response.ok().entity(body.build()).build();
    }

    /**
     * Start impersonating another user.
     */
    @POST
    public Response start(@FormParam("username") String username) {
        requireAdmin();
        username = ValidationUtil.validateLength(username, "username", 3, 50, false);

        UserDao userDao = new UserDao();
        User target = userDao.getActiveByUsername(username);
        if (target == null) {
            throw new ClientException("NotFound", "User not found: " + username);
        }
        if (target.getId().equals(principal.getId())) {
            throw new ClientException("ValidationError", "Cannot impersonate yourself");
        }

        String authTokenId = (String) request.getAttribute(SecurityFilter.AUTH_TOKEN_ID_ATTRIBUTE);
        if (authTokenId == null) {
            throw new ServerException("ImpersonateError", "Cannot determine the auth token id");
        }

        SecurityFilter.startImpersonating(authTokenId, target.getId());
        log.info("Admin \"{}\" started impersonating user \"{}\"", principal.getName(), target.getUsername());
        JsonObjectBuilder body = Json.createObjectBuilder()
                .add("username", target.getUsername())
                .add("user_id", target.getId());
        return Response.ok().entity(body.build()).build();
    }

    /**
     * Stop impersonating.
     */
    @POST
    @Path("/stop")
    public Response stop() {
        requireAdmin();
        String authTokenId = (String) request.getAttribute(SecurityFilter.AUTH_TOKEN_ID_ATTRIBUTE);
        if (authTokenId != null) {
            SecurityFilter.stopImpersonating(authTokenId);
        }
        log.info("Admin \"{}\" stopped impersonating", principal.getName());
        JsonObjectBuilder body = Json.createObjectBuilder().add("impersonating", false);
        return Response.ok().entity(body.build()).build();
    }

    /**
     * Ensures the caller is authenticated and has the ADMIN base function. Because the real
     * principal here is the signed-in admin (we set impersonation in injectUser), this check
     * enforces the rule "only admins can impersonate".
     */
    private void requireAdmin() {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        checkBaseFunction(BaseFunction.ADMIN);
    }
}