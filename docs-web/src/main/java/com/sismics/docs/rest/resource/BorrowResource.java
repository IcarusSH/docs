package com.sismics.docs.rest.resource;

import com.sismics.docs.core.constant.AclType;
import com.sismics.docs.core.constant.AuditLogType;
import com.sismics.docs.core.constant.BorrowStatus;
import com.sismics.docs.core.constant.PermType;
import com.sismics.docs.core.dao.AclDao;
import com.sismics.docs.core.dao.BorrowRequestDao;
import com.sismics.docs.core.dao.DocumentDao;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.model.jpa.Acl;
import com.sismics.docs.core.model.jpa.BorrowRequest;
import com.sismics.docs.core.model.jpa.Document;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.docs.core.util.AuditLogUtil;
import com.sismics.docs.rest.constant.BaseFunction;
import com.sismics.rest.exception.ClientException;
import com.sismics.rest.exception.ForbiddenClientException;
import com.sismics.rest.util.ValidationUtil;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Calendar;
import java.util.Date;
import java.util.List;

/**
 * Borrow request REST resources.
 *
 * Lets users with only SEARCHABLE permission on a document ask an approver for temporary
 * READ or DOWNLOADABLE access. The approver can approve (grants a T_ACL row of
 * AclType.BORROW) or reject. The borrow expires automatically via BorrowExpiryService.
 *
 * Approver eligibility (see BorrowRequestDao#isEligibleApprover):
 *   - holds the ADMIN base function, OR
 *   - holds {@link PermType#WRITE} on the target document.
 *
 * @author Claude
 */
@Path("/borrow")
public class BorrowResource extends BaseResource {

    /** Maximum allowed borrow duration in days. Prevents indefinite grants. */
    private static final int MAX_DURATION_DAYS = 30;

    /**
     * Submit a new borrow request.
     *
     * @api {post} /borrow/request Submit a borrow request
     * @apiName PostBorrowRequest
     * @apiGroup Borrow
     * @apiParam {String} document_id Document id
     * @apiParam {String} approver_username Username of the approver (must be ADMIN or have WRITE on the doc)
     * @apiParam {String="READ","DOWNLOADABLE"} perm Permission requested
     * @apiParam {Integer} duration_days Duration in days (1..30)
     * @apiParam {String} [note] Optional reason / note (max 2000 chars)
     */
    @POST
    @Path("request")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    public Response create(@FormParam("document_id") String documentId,
                           @FormParam("approver_username") String approverUsername,
                           @FormParam("perm") String permStr,
                           @FormParam("duration_days") Integer durationDays,
                           @FormParam("note") String note) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        AclDao aclDao = new AclDao();
        BorrowRequestDao borrowDao = new BorrowRequestDao();
        UserDao userDao = new UserDao();

        // Validate required inputs
        ValidationUtil.validateLength(documentId, "document_id", 1, 36, true);
        ValidationUtil.validateLength(approverUsername, "approver_username", 1, 50, true);
        ValidationUtil.validateLength(permStr, "perm", 1, 30, true);

        if (durationDays == null || durationDays < 1 || durationDays > MAX_DURATION_DAYS) {
            throw new ClientException("ValidationError", "duration_days must be between 1 and " + MAX_DURATION_DAYS);
        }
        if (note != null) {
            ValidationUtil.validateLength(note, "note", 0, 2000, false);
        }

        // Perm must be READ or DOWNLOADABLE
        PermType perm;
        try {
            perm = PermType.valueOf(permStr);
        } catch (IllegalArgumentException e) {
            throw new ClientException("ValidationError", "perm must be READ or DOWNLOADABLE");
        }
        if (perm != PermType.READ && perm != PermType.DOWNLOADABLE) {
            throw new ClientException("ValidationError", "perm must be READ or DOWNLOADABLE");
        }

        // Caller must be able to see the document (at least SEARCHABLE)
        if (!aclDao.checkPermission(documentId, PermType.SEARCHABLE, getTargetIdList(null))) {
            throw new ForbiddenClientException();
        }

        // Friendly nudge: if the caller already has READ or above, borrowing is meaningless
        if (aclDao.checkPermission(documentId, PermType.READ, getTargetIdList(null))) {
            throw new ClientException("AlreadyHaveAccess", "You already have read access to this document");
        }

        // Look up approver by username, ensure active
        User approver = userDao.getActiveByUsername(approverUsername);
        if (approver == null) {
            throw new ClientException("ValidationError", "Approver user not found");
        }
        if (approver.getId().equals(principal.getId())) {
            throw new ClientException("ValidationError", "You cannot be your own approver");
        }

        // Approver eligibility: ADMIN role OR WRITE on this doc
        if (!borrowDao.isEligibleApprover(approver.getId(), documentId)) {
            throw new ClientException("ApproverNotEligible",
                    "Approver must be an administrator or have write access to this document");
        }

        // Reject duplicate active requests
        if (borrowDao.hasActiveRequest(documentId, principal.getId())) {
            throw new ClientException("AlreadyActiveRequest",
                    "An active borrow request already exists for you on this document");
        }

        // Persist the request
        BorrowRequest req = new BorrowRequest();
        req.setDocumentId(documentId);
        req.setRequesterId(principal.getId());
        req.setApproverId(approver.getId());
        req.setStatus(BorrowStatus.PENDING);
        req.setPerm(perm);
        req.setDurationDays(durationDays);
        req.setNote(note);
        String id = borrowDao.create(req);

        AuditLogUtil.create(req, AuditLogType.BORROW_REQUEST, principal.getId());

        return Response.ok().entity(Json.createObjectBuilder().add("id", id).add("status", "ok").build()).build();
    }

    /**
     * Approve a pending request. Caller must be the named approver.
     */
    @POST
    @Path("{id: [a-z0-9\\-]+}/approve")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    public Response approve(@PathParam("id") String id,
                            @FormParam("comment") String comment) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        BorrowRequestDao borrowDao = new BorrowRequestDao();
        AclDao aclDao = new AclDao();

        BorrowRequest req = borrowDao.getById(id);
        if (req == null || req.getDeleteDate() != null) {
            throw new ForbiddenClientException();
        }
        if (!req.getApproverId().equals(principal.getId())) {
            throw new ForbiddenClientException();
        }
        if (req.getStatus() != BorrowStatus.PENDING) {
            throw new ClientException("ValidationError", "Request is not pending");
        }
        if (comment != null) {
            ValidationUtil.validateLength(comment, "comment", 0, 2000, false);
        }

        // Grant the temporary ACL
        Acl acl = new Acl();
        acl.setPerm(req.getPerm());
        acl.setType(AclType.BORROW);
        acl.setSourceId(req.getDocumentId());
        acl.setTargetId(req.getRequesterId());
        String aclId = aclDao.create(acl, principal.getId());

        // Update request state
        Date now = new Date();
        req.setAclId(aclId);
        req.setStatus(BorrowStatus.APPROVED);
        req.setDecisionDate(now);
        Calendar cal = Calendar.getInstance();
        cal.setTime(now);
        cal.add(Calendar.DATE, req.getDurationDays());
        req.setExpiryDate(cal.getTime());
        borrowDao.update(req);

        AuditLogUtil.create(req, AuditLogType.BORROW_APPROVED, principal.getId());

        return Response.ok().entity(Json.createObjectBuilder().add("status", "ok").build()).build();
    }

    /**
     * Reject a pending request.
     */
    @POST
    @Path("{id: [a-z0-9\\-]+}/reject")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    public Response reject(@PathParam("id") String id,
                           @FormParam("comment") String comment) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        BorrowRequestDao borrowDao = new BorrowRequestDao();
        BorrowRequest req = borrowDao.getById(id);
        if (req == null || req.getDeleteDate() != null) {
            throw new ForbiddenClientException();
        }
        if (!req.getApproverId().equals(principal.getId())) {
            throw new ForbiddenClientException();
        }
        if (req.getStatus() != BorrowStatus.PENDING) {
            throw new ClientException("ValidationError", "Request is not pending");
        }
        if (comment != null) {
            ValidationUtil.validateLength(comment, "comment", 0, 2000, false);
        }

        req.setStatus(BorrowStatus.REJECTED);
        req.setDecisionDate(new Date());
        borrowDao.update(req);

        AuditLogUtil.create(req, AuditLogType.BORROW_REJECTED, principal.getId());

        return Response.ok().entity(Json.createObjectBuilder().add("status", "ok").build()).build();
    }

    /**
     * Withdraw a pending request. Caller must be the requester.
     */
    @POST
    @Path("{id: [a-z0-9\\-]+}/cancel")
    @Produces(MediaType.APPLICATION_JSON)
    public Response cancel(@PathParam("id") String id) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        BorrowRequestDao borrowDao = new BorrowRequestDao();
        BorrowRequest req = borrowDao.getById(id);
        if (req == null || req.getDeleteDate() != null) {
            throw new ForbiddenClientException();
        }
        if (!req.getRequesterId().equals(principal.getId())) {
            throw new ForbiddenClientException();
        }
        if (req.getStatus() != BorrowStatus.PENDING) {
            throw new ClientException("ValidationError", "Only pending requests can be cancelled");
        }

        req.setStatus(BorrowStatus.CANCELLED);
        req.setDecisionDate(new Date());
        borrowDao.update(req);

        AuditLogUtil.create(req, AuditLogType.BORROW_CANCELLED, principal.getId());

        return Response.ok().entity(Json.createObjectBuilder().add("status", "ok").build()).build();
    }

    /**
     * List pending requests where the caller is the named approver. Drives the bell-icon dropdown.
     */
    @GET
    @Path("pending")
    @Produces(MediaType.APPLICATION_JSON)
    public Response pending() {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        List<BorrowRequest> list = new BorrowRequestDao().findPendingForApprover(principal.getId());
        return Response.ok().entity(toJsonArray(list).build()).build();
    }

    /**
     * List all requests initiated by the caller.
     */
    @GET
    @Path("mine")
    @Produces(MediaType.APPLICATION_JSON)
    public Response mine() {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        List<BorrowRequest> list = new BorrowRequestDao().findByRequester(principal.getId());
        return Response.ok().entity(toJsonArray(list).build()).build();
    }

    /**
     * Fetch a single request by id. Visible to its requester, its approver, or ADMIN.
     */
    @GET
    @Path("{id: [a-z0-9\\-]+}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response get(@PathParam("id") String id) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        BorrowRequest req = new BorrowRequestDao().getById(id);
        if (req == null || req.getDeleteDate() != null) {
            throw new ForbiddenClientException();
        }
        if (!req.getRequesterId().equals(principal.getId())
                && !req.getApproverId().equals(principal.getId())
                && !(principal instanceof com.sismics.security.UserPrincipal
                        && ((com.sismics.security.UserPrincipal) principal).isImpersonating())
                && !"admin".equals(new UserDao().getById(principal.getId()).getRoleId())) {
            throw new ForbiddenClientException();
        }
        return Response.ok().entity(toJson(req).build()).build();
    }

    /**
     * Admin borrow center: list every borrow request (optionally filtered by status).
     * Only ADMIN users may call this.
     *
     * @api {get} /borrow/admin/list List every borrow request
     * @apiName GetBorrowAdminList
     * @apiGroup Borrow
     * @apiParam {String="PENDING","APPROVED","REJECTED","EXPIRED","CANCELLED","REVOKED"} [status] Filter
     */
    @GET
    @Path("admin/list")
    @Produces(MediaType.APPLICATION_JSON)
    public Response adminList(@QueryParam("status") String statusStr) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        checkBaseFunction(BaseFunction.ADMIN);

        BorrowStatus filter = null;
        if (statusStr != null && !statusStr.isEmpty()) {
            try {
                filter = BorrowStatus.valueOf(statusStr);
            } catch (IllegalArgumentException e) {
                throw new ClientException("ValidationError", "Unknown status: " + statusStr);
            }
        }
        List<BorrowRequest> list = new BorrowRequestDao().findAll(filter);
        return Response.ok().entity(toJsonArray(list).build()).build();
    }

    /**
     * Admin-only: revoke an APPROVED borrow. Soft-deletes the BORROW ACL immediately and
     * marks the request REVOKED. Differs from /cancel (requester-side, PENDING only) and
     * /expire (system-side, time-based).
     *
     * @api {post} /borrow/{id}/revoke Admin-revoke an APPROVED borrow
     * @apiName PostBorrowRevoke
     * @apiGroup Borrow
     */
    @POST
    @Path("{id: [a-z0-9\\-]+}/revoke")
    @Produces(MediaType.APPLICATION_JSON)
    public Response revoke(@PathParam("id") String id) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        checkBaseFunction(BaseFunction.ADMIN);

        BorrowRequestDao borrowDao = new BorrowRequestDao();
        AclDao aclDao = new AclDao();

        BorrowRequest req = borrowDao.getById(id);
        if (req == null || req.getDeleteDate() != null) {
            throw new ForbiddenClientException();
        }
        if (req.getStatus() != BorrowStatus.APPROVED) {
            throw new ClientException("ValidationError",
                    "Only APPROVED borrows can be revoked (current status: " + req.getStatus() + ")");
        }

        // Soft-delete the BORROW ACL so the requester loses access immediately. The delete
        // method itself emits an AuditLogType.DELETE entry; we additionally emit a
        // BORROW_REVOKED entry on the request itself for clearer admin-facing reporting.
        aclDao.delete(req.getDocumentId(), req.getPerm(), req.getRequesterId(),
                principal.getId(), AclType.BORROW);

        req.setStatus(BorrowStatus.REVOKED);
        req.setDecisionDate(new Date());
        borrowDao.update(req);

        AuditLogUtil.create(req, AuditLogType.BORROW_REVOKED, principal.getId());

        return Response.ok().entity(Json.createObjectBuilder().add("status", "ok").build()).build();
    }

    /**
     * Convert a list of borrow requests to a JSON array with embedded document title and requester name.
     */
    private JsonArrayBuilder toJsonArray(List<BorrowRequest> list) {
        JsonArrayBuilder arr = Json.createArrayBuilder();
        for (BorrowRequest r : list) {
            arr.add(toJson(r));
        }
        return arr;
    }

    private JsonObjectBuilder toJson(BorrowRequest r) {
        // Embed requester/approver usernames and document title so the notification dropdown
        // and detail page can show human-readable context without further round-trips.
        String requesterUsername = lookupUsername(r.getRequesterId());
        String approverUsername = lookupUsername(r.getApproverId());
        String documentTitle = lookupDocumentTitle(r.getDocumentId());

        JsonObjectBuilder b = Json.createObjectBuilder()
                .add("id", r.getId())
                .add("document_id", r.getDocumentId())
                .add("document_title", documentTitle == null ? "" : documentTitle)
                .add("requester_id", r.getRequesterId())
                .add("requester_username", requesterUsername == null ? "" : requesterUsername)
                .add("approver_id", r.getApproverId())
                .add("approver_username", approverUsername == null ? "" : approverUsername)
                .add("status", r.getStatus().name())
                .add("perm", r.getPerm().name())
                .add("duration_days", r.getDurationDays())
                .add("create_date", r.getCreateDate() == null ? 0L : r.getCreateDate().getTime())
                .add("expiry_date", r.getExpiryDate() == null ? 0L : r.getExpiryDate().getTime());
        if (r.getNote() != null) {
            b.add("note", r.getNote());
        }
        return b;
    }

    /**
     * Best-effort username lookup; returns null if the user has been deleted. We don't surface
     * the failure to the caller — a deleted user should not break the borrow workflow UI.
     */
    private String lookupUsername(String userId) {
        if (userId == null) return null;
        User u = new UserDao().getById(userId);
        return u == null ? null : u.getUsername();
    }

    /**
     * Fetch the document title without permission checks. Both the requester and the approver
     * are entitled to see the title (the requester just submitted the form, the approver
     * already has WRITE on the doc). We avoid DocumentDao.getDocument() because it enforces
     * READ and would return null for a requester who only has SEARCHABLE.
     */
    private String lookupDocumentTitle(String documentId) {
        if (documentId == null) return null;
        try {
            Document doc = new DocumentDao().getById(documentId);
            return doc == null ? null : doc.getTitle();
        } catch (Exception e) {
            return null;
        }
    }
}