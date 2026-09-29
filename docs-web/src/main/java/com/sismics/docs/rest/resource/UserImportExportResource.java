package com.sismics.docs.rest.resource;

import com.google.common.base.Strings;
import com.opencsv.CSVReader;
import com.opencsv.CSVWriter;
import com.opencsv.exceptions.CsvValidationException;
import com.sismics.docs.core.constant.Constants;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.dao.criteria.UserCriteria;
import com.sismics.docs.core.dao.dto.UserDto;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.docs.core.util.jpa.SortCriteria;
import com.sismics.rest.exception.ClientException;
import com.sismics.rest.exception.ForbiddenClientException;
import com.sismics.rest.exception.ServerException;
import com.sismics.rest.util.ValidationUtil;
import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.glassfish.jersey.media.multipart.FormDataParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bulk user import/export via CSV.
 *
 * Endpoints (admin only):
 *   GET  /user/export           -> download CSV of all users
 *   POST /user/import (file=..) -> upload CSV, return per-row outcome summary
 *
 * CSV format (header row required):
 *   username,email,role,storage_quota_mb,update
 *
 *   username          (required) 3-50 chars, alphanum + _ @ . -
 *   email              (required) valid email
 *   role              (required) "admin" or "user"
 *   storage_quota_mb  (required) positive integer
 *   update            (optional) skip | overwrite | patch  (default: skip)
 *
 * Update strategy:
 *   skip      - if username already exists, leave the user untouched
 *   overwrite - if username already exists, replace email/role/quota
 *   patch     - if username already exists, fill in only empty fields
 *
 * Per-row outcome:
 *   created  - new user created, password is auto-generated
 *   updated  - existing user modified (overwrite or patch)
 *   skipped  - existing user left untouched
 *   error    - row rejected; details in response
 */
@Path("/user")
public class UserImportExportResource extends BaseResource {
    private static final Logger log = LoggerFactory.getLogger(UserImportExportResource.class);

    private static final List<String> EXPECTED_COLUMNS = Arrays.asList(
            "username", "email", "role", "storage_quota_mb", "update");

    /**
     * Export all active users to a CSV file.
     *
     * @api {get} /user/export Export users as CSV
     * @apiName GetUserExport
     * @apiGroup User
     * @apiPermission admin
     *
     * @return text/csv stream
     */
    @GET
    @Path("export")
    public Response export() {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        checkBaseFunction(com.sismics.docs.rest.constant.BaseFunction.ADMIN);

        // Stream the response so we don't buffer large user lists in memory
        List<UserDto> users = new UserDao().findByCriteria(
                new UserCriteria(), new SortCriteria(1, true));

        StreamingOutput stream = os -> {
            Writer w = new OutputStreamWriter(os, StandardCharsets.UTF_8);
            CSVWriter csv = new CSVWriter(w);
            try {
                csv.writeNext(EXPECTED_COLUMNS.toArray(new String[0]));
                for (UserDto u : users) {
                    long quotaMb = (u.getStorageQuota() == null ? 0L : u.getStorageQuota()) / 1000000L;
                    csv.writeNext(new String[]{
                            Strings.nullToEmpty(u.getUsername()),
                            Strings.nullToEmpty(u.getEmail()),
                            Strings.nullToEmpty(u.getRoleId()),
                            String.valueOf(quotaMb),
                            ""
                    });
                }
                csv.flush();
            } finally {
                try { csv.close(); } catch (IOException ignored) { }
            }
        };
        return Response.ok(stream, "text/csv; charset=UTF-8")
                .header("Content-Disposition", "attachment; filename=\"users.csv\"")
                .build();
    }

    /**
     * Import users from an uploaded CSV file.
     *
     * @api {post} /user/import Import users from CSV
     * @apiName PostUserImport
     * @apiGroup User
     * @apiPermission admin
     *
     * @param file Multipart file (text/csv)
     * @return JSON summary { created, updated, skipped, errors }
     */
    @POST
    @Path("import")
    @jakarta.ws.rs.Consumes(jakarta.ws.rs.core.MediaType.MULTIPART_FORM_DATA)
    public Response importCsv(@FormDataParam("file") InputStream file) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        checkBaseFunction(com.sismics.docs.rest.constant.BaseFunction.ADMIN);
        if (file == null) {
            throw new ClientException("ValidationError", "Missing 'file' part");
        }

        UserDao userDao = new UserDao();
        JsonArrayBuilder created = Json.createArrayBuilder();
        JsonArrayBuilder updated = Json.createArrayBuilder();
        JsonArrayBuilder skipped = Json.createArrayBuilder();
        JsonArrayBuilder errors = Json.createArrayBuilder();
        int[] lineNo = {0};

        try (Reader r = new InputStreamReader(file, StandardCharsets.UTF_8);
             CSVReader csv = new CSVReader(r)) {
            String[] header = csv.readNext();
            lineNo[0] = 1;
            if (header == null) {
                throw new ClientException("ValidationError", "Empty CSV file");
            }
            // Map column index by header
            Map<String, Integer> idx = new HashMap<>();
            for (int i = 0; i < header.length; i++) {
                idx.put(header[i].trim().toLowerCase(), i);
            }
            for (String required : new String[]{"username", "email", "role", "storage_quota_mb"}) {
                if (!idx.containsKey(required)) {
                    throw new ClientException("ValidationError",
                            MessageFormat.format("Missing column: {0}", required));
                }
            }
            Integer updateIdx = idx.get("update");

            String[] row;
            while ((row = csv.readNext()) != null) {
                lineNo[0]++;
                if (row.length == 1 && Strings.nullToEmpty(row[0]).trim().isEmpty()) {
                    continue; // skip blank lines
                }
                processRow(userDao, row, idx, updateIdx, lineNo[0], created, updated, skipped, errors);
            }
        } catch (IOException | CsvValidationException e) {
            log.error("User import failed at line " + lineNo[0], e);
            throw new ServerException("ImportError", "Error reading CSV at line " + lineNo[0], e);
        }

        JsonObjectBuilder responseBuilder = Json.createObjectBuilder()
                .add("created", created)
                .add("updated", updated)
                .add("skipped", skipped)
                .add("errors", errors);
        return Response.ok().entity(responseBuilder.build()).build();
    }

    /**
     * Process a single CSV row. Mutates the result builders.
     */
    private void processRow(UserDao userDao, String[] row,
                            Map<String, Integer> idx, Integer updateIdx,
                            int lineNo,
                            JsonArrayBuilder created, JsonArrayBuilder updated,
                            JsonArrayBuilder skipped, JsonArrayBuilder errors) {
        try {
            String username = field(row, idx, "username");
            String email = field(row, idx, "email");
            String role = field(row, idx, "role");
            String quotaMbStr = field(row, idx, "storage_quota_mb");
            String updateStr = updateIdx == null ? "" : fieldOrEmpty(row, updateIdx);

            // Validate
            username = ValidationUtil.validateLength(username.trim(), "username", 3, 50);
            ValidationUtil.validateUsername(username, "username");
            email = ValidationUtil.validateLength(email.trim(), "email", 1, 100);
            ValidationUtil.validateEmail(email, "email");
            role = role.trim().toLowerCase();
            if (!"admin".equals(role) && !"user".equals(role)) {
                throw new ClientException("ValidationError", "role must be 'admin' or 'user'");
            }
            long quotaMb = ValidationUtil.validateLong(quotaMbStr, "storage_quota_mb");
            if (quotaMb <= 0) {
                throw new ClientException("ValidationError", "storage_quota_mb must be > 0");
            }
            long quotaBytes = quotaMb * 1_000_000L;

            String updateMode = Strings.nullToEmpty(updateStr).trim().toLowerCase();
            if (updateMode.isEmpty()) {
                updateMode = "skip";
            }
            if (!Arrays.asList("skip", "overwrite", "patch").contains(updateMode)) {
                throw new ClientException("ValidationError",
                        "update must be 'skip', 'overwrite' or 'patch'");
            }

            // Forbid renaming the admin or guest via CSV
            if (Constants.GUEST_USER_ID.equals(username)) {
                throw new ClientException("ValidationError", "Cannot import the guest user");
            }

            User existing = userDao.getActiveByUsername(username);
            if (existing == null) {
                // Create new
                User u = new User();
                u.setRoleId(role);
                u.setUsername(username);
                String randomPassword = UUID.randomUUID().toString();
                u.setPassword(randomPassword);
                u.setEmail(email);
                u.setStorageQuota(quotaBytes);
                u.setOnboarding(false);
                userDao.create(u, principal.getId());
                created.add(Json.createObjectBuilder()
                        .add("line", lineNo)
                        .add("username", username)
                        .add("random_password", randomPassword)
                        .build());
                return;
            }

            // Existing user: apply update policy
            if ("skip".equals(updateMode)) {
                skipped.add(Json.createObjectBuilder()
                        .add("line", lineNo)
                        .add("username", username)
                        .add("reason", "exists")
                        .build());
                return;
            }

            boolean modified = false;
            if ("overwrite".equals(updateMode)) {
                if (!email.equals(existing.getEmail())) {
                    existing.setEmail(email);
                    modified = true;
                }
                if (!role.equals(existing.getRoleId())) {
                    existing.setRoleId(role);
                    userDao.updateRole(existing, principal.getId());
                    modified = true;
                }
                if (quotaBytes != existing.getStorageQuota()) {
                    existing.setStorageQuota(quotaBytes);
                    modified = true;
                }
                if (modified) {
                    userDao.update(existing, principal.getId());
                }
                updated.add(Json.createObjectBuilder()
                        .add("line", lineNo)
                        .add("username", username)
                        .add("mode", "overwrite")
                        .add("changed", modified)
                        .build());
                return;
            }

            // patch: only fill empty fields
            if (Strings.isNullOrEmpty(existing.getEmail()) && !email.isEmpty()) {
                existing.setEmail(email);
                modified = true;
            }
            if (!role.equals(existing.getRoleId()) && "user".equals(existing.getRoleId())) {
                // Only promote/demote if existing role is the default; admins stay admins.
                existing.setRoleId(role);
                userDao.updateRole(existing, principal.getId());
                modified = true;
            }
            if (existing.getStorageQuota() == null || existing.getStorageQuota() == 0) {
                existing.setStorageQuota(quotaBytes);
                modified = true;
            }
            if (modified) {
                userDao.update(existing, principal.getId());
            }
            updated.add(Json.createObjectBuilder()
                    .add("line", lineNo)
                    .add("username", username)
                    .add("mode", "patch")
                    .add("changed", modified)
                    .build());

        } catch (Exception e) {
            errors.add(Json.createObjectBuilder()
                    .add("line", lineNo)
                    .add("username", safeField(row, idx, "username"))
                    .add("message", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())
                    .build());
        }
    }

    private static String field(String[] row, Map<String, Integer> idx, String name) {
        Integer i = idx.get(name);
        if (i == null || i >= row.length) return "";
        return Strings.nullToEmpty(row[i]).trim();
    }

    private static String fieldOrEmpty(String[] row, int i) {
        if (i >= row.length) return "";
        return Strings.nullToEmpty(row[i]).trim();
    }

    private static String safeField(String[] row, Map<String, Integer> idx, String name) {
        try {
            return field(row, idx, name);
        } catch (Exception e) {
            return "";
        }
    }
}