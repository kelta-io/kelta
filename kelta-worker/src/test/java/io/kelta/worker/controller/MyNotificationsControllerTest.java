package io.kelta.worker.controller;

import io.kelta.runtime.context.CallerContext;
import io.kelta.runtime.context.CallerContext.UserType;
import io.kelta.runtime.module.integration.spi.NotificationStore.NewNotification;
import io.kelta.worker.filter.TenantContextFilter;
import io.kelta.worker.repository.NotificationRepository;
import io.kelta.worker.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /api/me/notifications} over the real {@link NotificationService} and
 * {@link NotificationRepository}, on H2 — so the owner filter under test is the SQL that ships.
 *
 * <p>The router stand-in is registered deliberately: {@code DynamicCollectionRouter} maps
 * {@code GET /api/{collection}/{id}} and {@code POST /api/{parent}/{parentId}/{child}}, which
 * {@code GET /api/me/notifications} and {@code POST /api/me/notifications/read-all} also match —
 * the literal mappings must win (see {@code concerns.md} → Fragile Areas).
 */
@DisplayName("MyNotificationsController")
class MyNotificationsControllerTest {

    private static final String TENANT = "aaaaaaaa-0000-0000-0000-000000000664";
    private static final String OTHER_TENANT = "bbbbbbbb-0000-0000-0000-000000000664";
    private static final String USER_A = "aaaaaaaa-0000-0000-0000-00000000a664";
    private static final String USER_B = "bbbbbbbb-0000-0000-0000-00000000b664";
    private static final CallerContext CALLER_A = new CallerContext(USER_A, UserType.PORTAL, false, false);
    private static final CallerContext CALLER_B = new CallerContext(USER_B, UserType.INTERNAL, true, true);

    /** Stands in for {@code DynamicCollectionRouter}'s two- and three-segment mappings. */
    @RestController
    @RequestMapping("/api")
    static class RouterStub {
        @GetMapping("/{collectionName}/{id}")
        String read() {
            return "router";
        }

        @PostMapping("/{parentName}/{parentId}/{childName}")
        String createChild() {
            return "router";
        }
    }

    private JdbcTemplate jdbc;
    private NotificationService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL"));
        jdbc.execute("""
                CREATE TABLE platform_user (
                    id VARCHAR(36) PRIMARY KEY,
                    tenant_id VARCHAR(36) NOT NULL,
                    email VARCHAR(320) NOT NULL
                )""");
        jdbc.execute("""
                CREATE TABLE notification (
                    id VARCHAR(36) PRIMARY KEY,
                    tenant_id VARCHAR(36) NOT NULL,
                    recipient_id VARCHAR(36) NOT NULL,
                    title VARCHAR(255) NOT NULL,
                    body TEXT NOT NULL,
                    link VARCHAR(2048),
                    level VARCHAR(20) DEFAULT 'INFO' NOT NULL,
                    read_at TIMESTAMP WITH TIME ZONE,
                    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
                )""");
        jdbc.update("INSERT INTO platform_user (id, tenant_id, email) VALUES (?, ?, ?)", USER_A, TENANT, "a@example.com");
        jdbc.update("INSERT INTO platform_user (id, tenant_id, email) VALUES (?, ?, ?)", USER_B, TENANT, "b@example.com");

        service = new NotificationService(new NotificationRepository(jdbc));
        mvc = MockMvcBuilders
                .standaloneSetup(new MyNotificationsController(service), new RouterStub())
                .addFilters(new TenantContextFilter())
                .build();
    }

    private String send(String recipient, String title, Instant createdAt) {
        String id = service.create(TENANT, new NewNotification(recipient, title, title + " body", null, "INFO"));
        jdbc.update("UPDATE notification SET created_at = ? WHERE id = ?", Timestamp.from(createdAt), id);
        return id;
    }

    private Timestamp readAt(String id) {
        return jdbc.queryForObject("SELECT read_at FROM notification WHERE id = ?", Timestamp.class, id);
    }

    private ResultActions perform(CallerContext caller, MockHttpServletRequestBuilder request) throws Exception {
        MockHttpServletRequestBuilder withTenant = request.header("X-Tenant-ID", TENANT);
        if (caller == null) {
            return mvc.perform(withTenant);
        }
        return ScopedValue.where(CallerContext.CURRENT, caller).call(() -> mvc.perform(withTenant));
    }

    @Nested
    @DisplayName("GET /api/me/notifications")
    class Listing {

        @Test
        @DisplayName("returns the caller's notifications newest first with the unread count")
        void newestFirstWithUnreadCount() throws Exception {
            Instant now = Instant.now();
            String oldest = send(USER_A, "first", now.minusSeconds(300));
            String middle = send(USER_A, "second", now.minusSeconds(200));
            String newest = send(USER_A, "third", now.minusSeconds(100));
            service.markRead(TENANT, USER_A, middle);

            perform(CALLER_A, get("/api/me/notifications"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data", hasSize(3)))
                    .andExpect(jsonPath("$.data[0].type").value("notifications"))
                    .andExpect(jsonPath("$.data[0].id").value(newest))
                    .andExpect(jsonPath("$.data[0].attributes.title").value("third"))
                    .andExpect(jsonPath("$.data[0].attributes.body").value("third body"))
                    .andExpect(jsonPath("$.data[0].attributes.level").value("INFO"))
                    .andExpect(jsonPath("$.data[0].attributes.readAt").value(nullValue()))
                    .andExpect(jsonPath("$.data[1].id").value(middle))
                    .andExpect(jsonPath("$.data[1].attributes.readAt").isNotEmpty())
                    .andExpect(jsonPath("$.data[2].id").value(oldest))
                    .andExpect(jsonPath("$.meta.totalCount").value(3))
                    .andExpect(jsonPath("$.meta.unreadCount").value(2));
        }

        @Test
        @DisplayName("paginates with page[number] / page[size]")
        void paginates() throws Exception {
            Instant now = Instant.now();
            send(USER_A, "first", now.minusSeconds(300));
            String second = send(USER_A, "second", now.minusSeconds(200));
            send(USER_A, "third", now.minusSeconds(100));

            perform(CALLER_A, get("/api/me/notifications")
                    .param("page[number]", "2").param("page[size]", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data", hasSize(1)))
                    .andExpect(jsonPath("$.data[0].id").value(second))
                    .andExpect(jsonPath("$.meta.currentPage").value(2))
                    .andExpect(jsonPath("$.meta.pageSize").value(1))
                    .andExpect(jsonPath("$.meta.totalPages").value(3))
                    .andExpect(jsonPath("$.meta.unreadCount").value(3))
                    .andExpect(jsonPath("$.links.next").isNotEmpty())
                    .andExpect(jsonPath("$.links.prev").isNotEmpty());
        }

        @Test
        @DisplayName("404 when no caller is bound")
        void noCallerIsNotFound() throws Exception {
            perform(null, get("/api/me/notifications")).andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("POST /api/me/notifications/{id}/read and /read-all")
    class MarkRead {

        @Test
        @DisplayName("marks the caller's notification read")
        void marksOwnRead() throws Exception {
            String id = send(USER_A, "hello", Instant.now());

            perform(CALLER_A, post("/api/me/notifications/" + id + "/read"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(id))
                    .andExpect(jsonPath("$.data.attributes.readAt").isNotEmpty())
                    .andExpect(jsonPath("$.meta.unreadCount").value(0));
            assertThat(readAt(id)).isNotNull();
        }

        @Test
        @DisplayName("read-all marks every unread notification of the caller, and only the caller's")
        void readAllIsOwnerScoped() throws Exception {
            String a1 = send(USER_A, "a1", Instant.now());
            String a2 = send(USER_A, "a2", Instant.now());
            String b1 = send(USER_B, "b1", Instant.now());

            perform(CALLER_A, post("/api/me/notifications/read-all"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.meta.markedRead").value(2))
                    .andExpect(jsonPath("$.meta.unreadCount").value(0));

            assertThat(readAt(a1)).isNotNull();
            assertThat(readAt(a2)).isNotNull();
            assertThat(readAt(b1)).as("user B's notification is untouched").isNull();
        }

        @Test
        @DisplayName("404 for an id that does not exist")
        void unknownIdIsNotFound() throws Exception {
            perform(CALLER_A, post("/api/me/notifications/" + UUID.randomUUID() + "/read"))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("two users")
    class TwoUsers {

        @Test
        @DisplayName("user B neither lists user A's notifications nor can mark one read (404, row unchanged)")
        void userBCannotSeeOrTouchUserAs() throws Exception {
            String forA = send(USER_A, "for A", Instant.now());
            String forB = send(USER_B, "for B", Instant.now());

            perform(CALLER_B, get("/api/me/notifications"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data", hasSize(1)))
                    .andExpect(jsonPath("$.data[0].id").value(forB))
                    .andExpect(jsonPath("$.meta.totalCount").value(1))
                    .andExpect(jsonPath("$.meta.unreadCount").value(1));

            perform(CALLER_B, post("/api/me/notifications/" + forA + "/read"))
                    .andExpect(status().isNotFound());
            assertThat(readAt(forA)).as("user A's notification stays unread").isNull();

            perform(CALLER_A, get("/api/me/notifications"))
                    .andExpect(jsonPath("$.data", hasSize(1)))
                    .andExpect(jsonPath("$.data[0].id").value(forA))
                    .andExpect(jsonPath("$.meta.unreadCount").value(1));
        }
    }

    @Nested
    @DisplayName("NotificationStore.create (the SEND_NOTIFICATION write)")
    class Create {

        @Test
        @DisplayName("resolves the recipient by email and stores every attribute")
        void resolvesEmailRecipient() {
            String id = service.create(TENANT,
                    new NewNotification("B@example.com", "Approved", "Your order is approved", "/app/o/1", "WARNING"));

            assertThat(jdbc.queryForMap(
                    "SELECT tenant_id, recipient_id, title, body, link, level, read_at FROM notification WHERE id = ?", id))
                    .containsEntry("TENANT_ID", TENANT)
                    .containsEntry("RECIPIENT_ID", USER_B)
                    .containsEntry("TITLE", "Approved")
                    .containsEntry("BODY", "Your order is approved")
                    .containsEntry("LINK", "/app/o/1")
                    .containsEntry("LEVEL", "WARNING")
                    .containsEntry("READ_AT", null);
        }

        @Test
        @DisplayName("refuses a recipient who is not a user of the tenant, writing nothing")
        void refusesUserOfAnotherTenant() {
            jdbc.update("INSERT INTO platform_user (id, tenant_id, email) VALUES (?, ?, ?)",
                    "cccccccc-0000-0000-0000-00000000c664", OTHER_TENANT, "c@example.com");

            assertThatThrownBy(() -> service.create(TENANT,
                    new NewNotification("cccccccc-0000-0000-0000-00000000c664", "t", "b", null, "INFO")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not a user of this tenant");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification", Integer.class)).isZero();
        }
    }
}
