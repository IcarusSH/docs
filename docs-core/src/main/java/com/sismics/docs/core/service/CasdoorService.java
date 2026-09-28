package com.sismics.docs.core.service;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Strings;
import com.sismics.docs.core.constant.ConfigType;
import com.sismics.docs.core.constant.Constants;
import com.sismics.docs.core.dao.ConfigDao;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.model.jpa.Config;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.docs.core.util.ConfigUtil;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Casdoor SSO service.
 *
 * Handles the OAuth2/OIDC authorization-code flow with a Casdoor server:
 * authorization URL, code-to-token exchange, userinfo fetch, and
 * automatic provisioning of the local Teedy user.
 *
 * Casdoor-specific endpoints used:
 *   GET  {server_url}/login/oauth/authorize       (browser redirect)
 *   POST {server_url}/api/login/oauth/access_token  (code -> access_token + id_token)
 *   GET  {server_url}/api/userinfo                 (Bearer access_token -> user JSON)
 *
 * @author claude
 */
public class CasdoorService {
    /**
     * Logger.
     */
    private static final Logger log = LoggerFactory.getLogger(CasdoorService.class);

    /**
     * Shared HTTP client.
     */
    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient();

    /**
     * Returns true if Casdoor SSO is enabled in the database configuration.
     * @return True if enabled and the minimum required settings are present
     */
    public static boolean isEnabled() {
        ConfigDao configDao = new ConfigDao();
        Config enabled = configDao.getById(ConfigType.CASDOOR_ENABLED);
        if (enabled == null || !Boolean.parseBoolean(enabled.getValue())) {
            return false;
        }
        return getServerUrl() != null
                && !Strings.isNullOrEmpty(getConfig(ConfigType.CASDOOR_CLIENT_ID))
                && !Strings.isNullOrEmpty(getConfig(ConfigType.CASDOOR_CLIENT_SECRET))
                && !Strings.isNullOrEmpty(getConfig(ConfigType.CASDOOR_ORGANIZATION))
                && !Strings.isNullOrEmpty(getConfig(ConfigType.CASDOOR_APPLICATION));
    }

    /**
     * Build the URL to redirect the browser to Casdoor's authorization endpoint.
     * @param state Anti-CSRF opaque value (caller stores it and verifies on callback)
     * @param redirectUri Absolute URL Casdoor should redirect back to
     * @return Authorization URL
     */
    public String getAuthorizationUrl(String state, String redirectUri) {
        String url = getServerUrl() + "/login/oauth/authorize"
                + "?client_id=" + urlEncode(getConfig(ConfigType.CASDOOR_CLIENT_ID))
                + "&response_type=code"
                + "&redirect_uri=" + urlEncode(redirectUri)
                + "&scope=" + urlEncode("openid profile email")
                + "&state=" + urlEncode(state);
        return url;
    }

    /**
     * Exchange an authorization code for an access_token (and id_token).
     * @param code The authorization code from Casdoor callback
     * @param redirectUri Must match the redirect_uri used in authorization
     * @return Parsed JSON token response containing access_token / id_token
     * @throws IOException If the token endpoint fails
     */
    public JsonObject exchangeCodeForToken(String code, String redirectUri) throws IOException {
        RequestBody form = new FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("client_id", getConfig(ConfigType.CASDOOR_CLIENT_ID))
                .add("client_secret", getConfig(ConfigType.CASDOOR_CLIENT_SECRET))
                .add("redirect_uri", redirectUri)
                .build();
        Request request = new Request.Builder()
                .url(getServerUrl() + "/api/login/oauth/access_token")
                .post(form)
                .build();
        try (Response response = HTTP_CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                int code1 = response.code();
                throw new IOException("Casdoor token endpoint returned " + code1);
            }
            try (JsonReader reader = Json.createReader(response.body().charStream())) {
                JsonObject obj = reader.readObject();
                if (obj.containsKey("error")) {
                    throw new IOException("Casdoor token endpoint error: " + obj.getString("error"));
                }
                return obj;
            }
        }
    }

    /**
     * Fetch user info from Casdoor using the access_token.
     * @param accessToken The OAuth2 access token from the token exchange
     * @return Parsed user JSON (typically contains id, name, email, ...)
     * @throws IOException If the userinfo endpoint fails
     */
    public JsonObject fetchUserInfo(String accessToken) throws IOException {
        Request request = new Request.Builder()
                .url(getServerUrl() + "/api/userinfo")
                .header("Authorization", "Bearer " + accessToken)
                .get()
                .build();
        try (Response response = HTTP_CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("Casdoor userinfo endpoint returned " + response.code());
            }
            try (JsonReader reader = Json.createReader(response.body().charStream())) {
                return reader.readObject();
            }
        }
    }

    /**
     * Find an existing Teedy user by Casdoor username (preferred_username / sub / name),
     * or create a new one with the default "user" role.
     *
     * The new user gets a random local password (local login disabled) and the
     * default storage quota from CASDOOR_DEFAULT_STORAGE config (or 1 GiB).
     *
     * @param userInfo The Casdoor userinfo JSON object
     * @return The matched or newly-created user
     * @throws Exception If user creation fails (e.g. duplicate username)
     */
    public User findOrCreateUser(JsonObject userInfo) throws Exception {
        // Casdoor userinfo typically contains: id (or sub), name, email, displayName, ...
        // We prefer "name" as the local username; fall back to "id" / "sub".
        String username = pickString(userInfo, "name", "preferred_username", "sub", "id");
        if (Strings.isNullOrEmpty(username)) {
            throw new IllegalArgumentException("Casdoor userinfo missing username field");
        }
        String email = pickString(userInfo, "email");

        UserDao userDao = new UserDao();
        User user = userDao.getActiveByUsername(username);
        if (user != null) {
            // Update email if changed in Casdoor
            if (!Strings.isNullOrEmpty(email) && !email.equals(user.getEmail())) {
                user.setEmail(email);
                userDao.update(user, user.getId());
            }
            return user;
        }

        user = new User();
        user.setRoleId(Constants.DEFAULT_USER_ROLE);
        user.setUsername(username);
        user.setPassword(UUID.randomUUID().toString()); // disable local login
        user.setEmail(email != null ? email : "");
        try {
            user.setStorageQuota(ConfigUtil.getConfigLongValue(ConfigType.CASDOOR_DEFAULT_STORAGE));
        } catch (IllegalStateException e) {
            user.setStorageQuota(1073741824L); // 1 GiB default
        }
        userDao.create(user, "admin");
        log.info("Created new Teedy user \"" + username + "\" from Casdoor login");
        return user;
    }

    /**
     * Read a config value as String, returning null if absent.
     */
    private static String getConfig(ConfigType type) {
        ConfigDao configDao = new ConfigDao();
        Config c = configDao.getById(type);
        return c == null ? null : c.getValue();
    }

    /**
     * Read CASDOOR_SERVER_URL with trailing-slash normalization.
     */
    private static String getServerUrl() {
        String url = getConfig(ConfigType.CASDOOR_SERVER_URL);
        if (url == null) return null;
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * Pick the first non-empty string from a JSON object's keys.
     */
    private static String pickString(JsonObject obj, String... keys) {
        for (String key : keys) {
            if (obj.containsKey(key) && !obj.isNull(key)) {
                String v = obj.getString(key);
                if (!Strings.isNullOrEmpty(v)) return v;
            }
        }
        return null;
    }

    /**
     * URL-encode a path component using standard Java URLEncoder (form-encoding).
     */
    private static String urlEncode(String s) {
        if (s == null) return "";
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            // UTF-8 is always supported
            return s;
        }
    }
}