package io.kelta.worker.scim.service;

import io.kelta.worker.scim.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class ScimUserService {

    private static final Logger log = LoggerFactory.getLogger(ScimUserService.class);

    private static final Map<String, String> USER_ATTR_MAP = Map.ofEntries(
            Map.entry("username", "pu.email"),
            Map.entry("name.familyname", "pu.last_name"),
            Map.entry("name_familyname", "pu.last_name"),
            Map.entry("name.givenname", "pu.first_name"),
            Map.entry("name_givenname", "pu.first_name"),
            Map.entry("displayname", "CONCAT(COALESCE(pu.first_name,''), ' ', COALESCE(pu.last_name,''))"),
            Map.entry("emails.value", "pu.email"),
            Map.entry("emails_value", "pu.email"),
            Map.entry("active", "CASE WHEN pu.status = 'ACTIVE' THEN 'true' ELSE 'false' END"),
            Map.entry("externalid", "pu.username"),
            Map.entry("locale", "pu.locale"),
            Map.entry("timezone", "pu.timezone")
    );

    private final JdbcTemplate jdbcTemplate;
    private final ScimFilterParser filterParser;
    private final io.kelta.worker.service.UserInviteService userInviteService;
    private final io.kelta.worker.service.AuthSessionRevocationClient authSessions;

    public ScimUserService(JdbcTemplate jdbcTemplate,
                            @org.springframework.beans.factory.annotation.Autowired(required = false)
                            io.kelta.worker.service.UserInviteService userInviteService,
                            @org.springframework.beans.factory.annotation.Autowired(required = false)
                            io.kelta.worker.service.AuthSessionRevocationClient authSessions) {
        this.jdbcTemplate = jdbcTemplate;
        this.userInviteService = userInviteService;
        this.authSessions = authSessions;
        this.filterParser = new ScimFilterParser(USER_ATTR_MAP);
    }

    public ScimListResponse<ScimUser> listUsers(String tenantId, String filter,
                                                 int startIndex, int count, String baseUrl) {
        ScimFilterParser.ParsedFilter parsed = filterParser.parse(filter);

        List<Object> countParams = new ArrayList<>();
        countParams.add(tenantId);
        countParams.addAll(parsed.params());

        String countSql = "SELECT COUNT(*) FROM platform_user pu WHERE pu.tenant_id = ? AND pu.status = 'ACTIVE' AND " + parsed.sql();
        int total = jdbcTemplate.queryForObject(countSql, Integer.class, countParams.toArray());

        List<Object> queryParams = new ArrayList<>();
        queryParams.add(tenantId);
        queryParams.addAll(parsed.params());

        int offset = Math.max(0, startIndex - 1);
        String sql = "SELECT pu.id, pu.email, pu.username, pu.first_name, pu.last_name, "
                + "pu.status, pu.locale, pu.timezone, pu.manager_id, pu.created_at, pu.updated_at "
                + "FROM platform_user pu WHERE pu.tenant_id = ? AND pu.status = 'ACTIVE' AND " + parsed.sql()
                + " ORDER BY pu.created_at ASC LIMIT ? OFFSET ?";
        queryParams.add(count);
        queryParams.add(offset);

        List<ScimUser> users = jdbcTemplate.query(sql, (rs, rowNum) -> mapUser(rs, baseUrl),
                queryParams.toArray());

        return new ScimListResponse<>(users, total, startIndex, users.size());
    }

    public ScimUser getUser(String tenantId, String userId, String baseUrl) {
        String sql = "SELECT pu.id, pu.email, pu.username, pu.first_name, pu.last_name, "
                + "pu.status, pu.locale, pu.timezone, pu.manager_id, pu.created_at, pu.updated_at "
                + "FROM platform_user pu WHERE pu.tenant_id = ? AND pu.id = ? AND pu.status <> 'ANONYMIZED'";
        List<ScimUser> users = jdbcTemplate.query(sql, (rs, rowNum) -> mapUser(rs, baseUrl),
                tenantId, userId);
        if (users.isEmpty()) {
            throw new ScimException(HttpStatus.NOT_FOUND, "User " + userId + " not found");
        }
        return users.get(0);
    }

    @Transactional
    public ScimUser createUser(String tenantId, ScimUser request, String baseUrl) {
        String email = extractEmail(request);
        if (email == null || email.isBlank()) {
            throw new ScimException(HttpStatus.BAD_REQUEST, "userName or emails[0].value is required");
        }
        email = email.toLowerCase(Locale.ROOT);

        // Check for existing user with same email
        Integer existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM platform_user WHERE tenant_id = ? AND email = ?",
                Integer.class, tenantId, email);
        if (existing != null && existing > 0) {
            throw new ScimException(HttpStatus.CONFLICT, "User with email " + email + " already exists",
                    "uniqueness");
        }

        String id = UUID.randomUUID().toString();
        String firstName = request.getName() != null ? request.getName().getGivenName() : null;
        String lastName = request.getName() != null ? request.getName().getFamilyName() : null;
        String status = request.isActive() ? "ACTIVE" : "INACTIVE";
        String locale = request.getLocale() != null ? request.getLocale() : "en_US";
        String timezone = request.getTimezone() != null ? request.getTimezone() : "UTC";
        String username = request.getExternalId() != null ? request.getExternalId() : email;

        jdbcTemplate.update(
                "INSERT INTO platform_user (id, tenant_id, email, username, first_name, last_name, "
                        + "status, locale, timezone, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())",
                id, tenantId, email, username, firstName, lastName, status, locale, timezone);

        log.info("SCIM: Created user {} ({}) in tenant {}", id, email, tenantId);

        // Send invite (tenant can opt out via tenant.auto_invite_on_create=false).
        if (userInviteService != null && "ACTIVE".equals(status)
                && userInviteService.isAutoInviteEnabled(tenantId)) {
            try {
                userInviteService.inviteUser(tenantId, id);
            } catch (Exception e) {
                log.warn("SCIM: invite for newly-created user {} failed: {}", id, e.getMessage());
            }
        }
        return getUser(tenantId, id, baseUrl);
    }

    @Transactional
    public ScimUser replaceUser(String tenantId, String userId, ScimUser request, String baseUrl) {
        // Verify user exists
        boolean wasActive = getUser(tenantId, userId, baseUrl).isActive();

        String email = extractEmail(request);
        if (email != null) {
            email = email.toLowerCase(Locale.ROOT);
        }

        String firstName = request.getName() != null ? request.getName().getGivenName() : null;
        String lastName = request.getName() != null ? request.getName().getFamilyName() : null;
        String status = request.isActive() ? "ACTIVE" : "INACTIVE";

        jdbcTemplate.update(
                "UPDATE platform_user SET email = COALESCE(?, email), "
                        + "first_name = ?, last_name = ?, status = ?, "
                        + "locale = COALESCE(?, locale), timezone = COALESCE(?, timezone), "
                        + "username = COALESCE(?, username), updated_at = NOW() "
                        + "WHERE tenant_id = ? AND id = ?",
                email, firstName, lastName, status,
                request.getLocale(), request.getTimezone(), request.getExternalId(),
                tenantId, userId);

        log.info("SCIM: Replaced user {} in tenant {}", userId, tenantId);
        if (wasActive && !request.isActive()) {
            signOut(tenantId, userId);
        }
        return getUser(tenantId, userId, baseUrl);
    }

    @Transactional
    public ScimUser patchUser(String tenantId, String userId, ScimPatchOp patchOp, String baseUrl) {
        // Verify user exists
        boolean wasActive = getUser(tenantId, userId, baseUrl).isActive();

        if (patchOp.getOperations() == null) {
            throw new ScimException(HttpStatus.BAD_REQUEST, "Operations list is required");
        }

        for (ScimPatchOp.Operation op : patchOp.getOperations()) {
            String operation = op.getOp().toLowerCase(Locale.ROOT);
            if (!"replace".equals(operation) && !"add".equals(operation) && !"remove".equals(operation)) {
                throw new ScimException(HttpStatus.BAD_REQUEST,
                        "Unsupported PATCH operation: " + op.getOp());
            }
            if ("remove".equals(operation)) {
                applyUserRemoveOp(tenantId, userId, op);
            } else {
                applyUserPatchOp(tenantId, userId, op);
            }
        }

        log.info("SCIM: Patched user {} in tenant {}", userId, tenantId);
        ScimUser patched = getUser(tenantId, userId, baseUrl);
        if (wasActive && !patched.isActive()) {
            signOut(tenantId, userId);
        }
        return patched;
    }

    @Transactional
    public void deleteUser(String tenantId, String userId) {
        // Soft delete: set status to INACTIVE. An anonymized user is gone for SCIM (getUser too):
        // ANONYMIZED is terminal, and these writes bypass the users hook that enforces it.
        int rows = jdbcTemplate.update(
                "UPDATE platform_user SET status = 'INACTIVE', updated_at = NOW() "
                        + "WHERE tenant_id = ? AND id = ? AND status <> 'ANONYMIZED'",
                tenantId, userId);
        if (rows == 0) {
            throw new ScimException(HttpStatus.NOT_FOUND, "User " + userId + " not found");
        }
        log.info("SCIM: Deactivated user {} in tenant {}", userId, tenantId);
        signOut(tenantId, userId);
    }

    /** A deactivated user is signed out of kelta-auth once this transaction commits. */
    private void signOut(String tenantId, String userId) {
        if (authSessions != null) {
            authSessions.revokeAfterCommit(tenantId, userId);
        }
    }

    private void applyUserPatchOp(String tenantId, String userId, ScimPatchOp.Operation op) {
        String path = op.getPath() != null ? op.getPath().toLowerCase(Locale.ROOT) : "";
        Object value = op.getValue();

        switch (path) {
            case "active" -> {
                boolean active = Boolean.parseBoolean(String.valueOf(value));
                jdbcTemplate.update(
                        "UPDATE platform_user SET status = ?, updated_at = NOW() WHERE tenant_id = ? AND id = ?",
                        active ? "ACTIVE" : "INACTIVE", tenantId, userId);
            }
            case "name.givenname" -> jdbcTemplate.update(
                    "UPDATE platform_user SET first_name = ?, updated_at = NOW() WHERE tenant_id = ? AND id = ?",
                    String.valueOf(value), tenantId, userId);
            case "name.familyname" -> jdbcTemplate.update(
                    "UPDATE platform_user SET last_name = ?, updated_at = NOW() WHERE tenant_id = ? AND id = ?",
                    String.valueOf(value), tenantId, userId);
            case "username" -> {
                String email = String.valueOf(value).toLowerCase(Locale.ROOT);
                jdbcTemplate.update(
                        "UPDATE platform_user SET email = ?, updated_at = NOW() WHERE tenant_id = ? AND id = ?",
                        email, tenantId, userId);
            }
            case "locale" -> jdbcTemplate.update(
                    "UPDATE platform_user SET locale = ?, updated_at = NOW() WHERE tenant_id = ? AND id = ?",
                    String.valueOf(value), tenantId, userId);
            case "timezone" -> jdbcTemplate.update(
                    "UPDATE platform_user SET timezone = ?, updated_at = NOW() WHERE tenant_id = ? AND id = ?",
                    String.valueOf(value), tenantId, userId);
            case "externalid" -> jdbcTemplate.update(
                    "UPDATE platform_user SET username = ?, updated_at = NOW() WHERE tenant_id = ? AND id = ?",
                    String.valueOf(value), tenantId, userId);
            case "" -> {
                // No path means value is a map of attributes
                if (value instanceof Map<?, ?> attrs) {
                    applyUserMapPatch(tenantId, userId, attrs);
                }
            }
            default -> log.warn("SCIM: Ignoring unsupported PATCH path: {}", path);
        }
    }

    private void applyUserRemoveOp(String tenantId, String userId, ScimPatchOp.Operation op) {
        String path = op.getPath() != null ? op.getPath().toLowerCase(Locale.ROOT) : "";
        switch (path) {
            case "locale" -> jdbcTemplate.update(
                    "UPDATE platform_user SET locale = NULL, updated_at = NOW() WHERE tenant_id = ? AND id = ?",
                    tenantId, userId);
            case "timezone" -> jdbcTemplate.update(
                    "UPDATE platform_user SET timezone = NULL, updated_at = NOW() WHERE tenant_id = ? AND id = ?",
                    tenantId, userId);
            case "name.givenname" -> jdbcTemplate.update(
                    "UPDATE platform_user SET first_name = NULL, updated_at = NOW() WHERE tenant_id = ? AND id = ?",
                    tenantId, userId);
            case "name.familyname" -> jdbcTemplate.update(
                    "UPDATE platform_user SET last_name = NULL, updated_at = NOW() WHERE tenant_id = ? AND id = ?",
                    tenantId, userId);
            default -> log.warn("SCIM: Ignoring unsupported PATCH remove path: {}", path);
        }
    }

    @SuppressWarnings("unchecked")
    private void applyUserMapPatch(String tenantId, String userId, Map<?, ?> attrs) {
        for (Map.Entry<?, ?> entry : attrs.entrySet()) {
            String key = String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT);
            ScimPatchOp.Operation subOp = new ScimPatchOp.Operation();
            subOp.setOp("replace");
            subOp.setPath(key);
            subOp.setValue(entry.getValue());
            applyUserPatchOp(tenantId, userId, subOp);
        }
    }

    private ScimUser mapUser(ResultSet rs, String baseUrl) throws SQLException {
        ScimUser user = new ScimUser();
        user.setId(rs.getString("id"));
        user.setUserName(rs.getString("email"));
        user.setExternalId(rs.getString("username"));
        user.setName(new ScimName(rs.getString("first_name"), rs.getString("last_name")));

        String firstName = rs.getString("first_name");
        String lastName = rs.getString("last_name");
        String displayName = ((firstName != null ? firstName : "") + " " + (lastName != null ? lastName : "")).trim();
        user.setDisplayName(displayName.isEmpty() ? rs.getString("email") : displayName);

        user.setActive("ACTIVE".equals(rs.getString("status")));
        user.setLocale(rs.getString("locale"));
        user.setTimezone(rs.getString("timezone"));

        user.setEmails(List.of(new ScimEmail(rs.getString("email"), "work", true)));

        String managerId = rs.getString("manager_id");
        if (managerId != null) {
            ScimUser.ScimEnterpriseUser enterprise = new ScimUser.ScimEnterpriseUser();
            ScimUser.ScimManager manager = new ScimUser.ScimManager();
            manager.setValue(managerId);
            enterprise.setManager(manager);
            user.setEnterpriseUser(enterprise);
        }

        user.setMeta(new ScimMeta("User",
                formatTimestamp(rs.getObject("created_at", OffsetDateTime.class)),
                formatTimestamp(rs.getObject("updated_at", OffsetDateTime.class)),
                baseUrl + "/scim/v2/Users/" + rs.getString("id")));

        return user;
    }

    private String extractEmail(ScimUser request) {
        if (request.getUserName() != null) {
            return request.getUserName();
        }
        if (request.getEmails() != null && !request.getEmails().isEmpty()) {
            return request.getEmails().stream()
                    .filter(ScimEmail::isPrimary)
                    .findFirst()
                    .or(() -> request.getEmails().stream().findFirst())
                    .map(ScimEmail::getValue)
                    .orElse(null);
        }
        return null;
    }

    private static String formatTimestamp(OffsetDateTime ts) {
        if (ts == null) return null;
        return ts.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
