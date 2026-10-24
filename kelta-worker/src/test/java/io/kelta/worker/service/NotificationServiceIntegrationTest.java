package io.kelta.worker.service;

import io.kelta.runtime.module.integration.spi.NotificationStore.NewNotification;
import io.kelta.runtime.query.Pagination;
import io.kelta.worker.repository.NotificationRepository;
import io.kelta.worker.repository.NotificationRepository.Notification;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Timestamp;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link NotificationService} against the real V214 {@code notification} table: the
 * SEND_NOTIFICATION write, the recipient's newest-first list with its unread count, and
 * owner-scoped read marking between two users of one tenant.
 *
 * <p>The connection is the container superuser, so RLS is not evaluated here; the
 * {@code recipient_id} filter is what is under test. {@code RowLevelSecurityIntegrationTest}
 * covers the table's tenant policies.
 *
 * <p>Runs only where Docker is available (Testcontainers).
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("NotificationService — real Postgres, two users of one tenant")
class NotificationServiceIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg15").asCompatibleSubstituteFor("postgres"));

    static final String TENANT = "aaaaaaaa-0000-0000-0000-000000000664";
    static final String OTHER_TENANT = "bbbbbbbb-0000-0000-0000-000000000664";
    static final String USER_A = "aaaaaaaa-0000-0000-0000-00000000a664";
    static final String USER_B = "aaaaaaaa-0000-0000-0000-00000000b664";
    static final String OTHER_TENANT_USER = "bbbbbbbb-0000-0000-0000-00000000c664";

    static JdbcTemplate jdbc;
    static NotificationService service;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        dataSource.setDriverClassName("org.postgresql.Driver");
        jdbc = new JdbcTemplate(dataSource);
        service = new NotificationService(new NotificationRepository(jdbc));

        jdbc.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .placeholderReplacement(false)
                .load()
                .migrate();

        jdbc.update("INSERT INTO tenant (id, slug, name) VALUES (?, ?, ?)", TENANT, "notify-a", "notify-a");
        jdbc.update("INSERT INTO tenant (id, slug, name) VALUES (?, ?, ?)", OTHER_TENANT, "notify-b", "notify-b");
        user(USER_A, TENANT, "a@notify.test");
        user(USER_B, TENANT, "b@notify.test");
        user(OTHER_TENANT_USER, OTHER_TENANT, "c@notify.test");
    }

    static void user(String id, String tenantId, String email) {
        jdbc.update("INSERT INTO platform_user (id, tenant_id, email) VALUES (?, ?, ?)", id, tenantId, email);
    }

    @BeforeEach
    void clean() {
        jdbc.execute("DELETE FROM notification");
    }

    private String send(String recipient, String title) {
        return service.create(TENANT, new NewNotification(recipient, title, title + " body", null, "INFO"));
    }

    private Timestamp readAt(String id) {
        return jdbc.queryForObject("SELECT read_at FROM notification WHERE id = ?", Timestamp.class, id);
    }

    @Test
    @DisplayName("create stores recipient/title/body/link/level, resolving an email recipient")
    void createStoresEveryColumn() {
        String id = service.create(TENANT,
                new NewNotification("B@notify.test", "Approved", "Order approved", "/app/o/1", "ERROR"));

        assertThat(jdbc.queryForMap(
                "SELECT tenant_id, recipient_id, title, body, link, level, read_at FROM notification WHERE id = ?", id))
                .containsEntry("tenant_id", TENANT)
                .containsEntry("recipient_id", USER_B)
                .containsEntry("title", "Approved")
                .containsEntry("body", "Order approved")
                .containsEntry("link", "/app/o/1")
                .containsEntry("level", "ERROR")
                .containsEntry("read_at", null);
    }

    @Test
    @DisplayName("create refuses a recipient from another tenant")
    void createRefusesForeignRecipient() {
        assertThatThrownBy(() -> send(OTHER_TENANT_USER, "nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a user of this tenant");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification", Integer.class)).isZero();
    }

    @Test
    @DisplayName("the level CHECK constraint rejects anything but INFO/WARNING/ERROR")
    void levelIsConstrained() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO notification (id, tenant_id, recipient_id, title, body, level) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), TENANT, USER_A, "t", "b", "CRITICAL"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("list is the recipient's own rows, newest first, with the unread count")
    void listIsOwnNewestFirst() {
        String first = send(USER_A, "first");
        String second = send(USER_A, "second");
        jdbc.update("UPDATE notification SET created_at = now() - interval '1 hour' WHERE id = ?", first);
        send(USER_B, "for B");
        service.markRead(TENANT, USER_A, first);

        NotificationService.Page page = service.list(TENANT, USER_A, new Pagination(1, 20));

        assertThat(page.notifications()).extracting(Notification::id).containsExactly(second, first);
        assertThat(page.totalCount()).isEqualTo(2);
        assertThat(page.unreadCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("user B neither lists user A's notification nor can mark it read")
    void userBCannotTouchUserA() {
        String forA = send(USER_A, "for A");

        assertThat(service.list(TENANT, USER_B, new Pagination(1, 20)).notifications()).isEmpty();
        assertThat(service.markRead(TENANT, USER_B, forA)).isEmpty();
        assertThat(service.markAllRead(TENANT, USER_B)).isZero();
        assertThat(readAt(forA)).isNull();

        assertThat(service.markRead(TENANT, USER_A, forA)).isPresent();
        assertThat(readAt(forA)).isNotNull();
    }

    @Test
    @DisplayName("read-all marks only the recipient's unread rows and keeps the first read time")
    void readAllIsOwnerScoped() {
        String a1 = send(USER_A, "a1");
        String a2 = send(USER_A, "a2");
        String b1 = send(USER_B, "b1");
        service.markRead(TENANT, USER_A, a1);
        Timestamp firstRead = readAt(a1);

        assertThat(service.markAllRead(TENANT, USER_A)).isEqualTo(1);
        assertThat(readAt(a1)).isEqualTo(firstRead);
        assertThat(readAt(a2)).isNotNull();
        assertThat(readAt(b1)).isNull();
    }
}
