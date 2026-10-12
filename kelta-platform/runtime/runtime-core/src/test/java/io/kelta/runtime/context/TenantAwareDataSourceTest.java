package io.kelta.runtime.context;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the tenant-aware DataSource scopes {@code app.current_tenant_id} correctly.
 *
 * <p>The load-bearing assertion for PgBouncer transaction-pool safety is that a
 * <b>tenant-scoped</b> connection sets the variable with transaction-local {@code SET LOCAL}
 * inside an explicit transaction (autocommit off, commit on close) — never a connection-session
 * {@code SET}, which a transaction pooler would silently drop and leak rows across tenants via
 * the {@code admin_bypass} policy.
 */
@DisplayName("TenantAwareDataSource — transaction-scoped tenant variable")
class TenantAwareDataSourceTest {

    private DataSource delegate;
    private Connection conn;
    private Statement stmt;
    private TenantAwareDataSource tenantDataSource;

    @BeforeEach
    void setUp() throws Exception {
        delegate = mock(DataSource.class);
        conn = mock(Connection.class);
        stmt = mock(Statement.class);
        when(delegate.getConnection()).thenReturn(conn);
        when(conn.createStatement()).thenReturn(stmt);
        when(conn.isClosed()).thenReturn(false);
        tenantDataSource = new TenantAwareDataSource(delegate);
    }

    @Test
    @DisplayName("tenant-scoped connection uses SET LOCAL inside a transaction and commits on close")
    void tenantScopedUsesSetLocalAndCommitsOnClose() throws Exception {
        // Autocommit on borrow, then off once we begin the transaction (read twice).
        when(conn.getAutoCommit()).thenReturn(true, false);

        Connection borrowed = borrowAs("tenant-abc");

        // Began a transaction and issued the transaction-local set — NOT a session SET.
        verify(conn).setAutoCommit(false);
        verify(stmt).execute("SET LOCAL app.current_tenant_id = 'tenant-abc'");
        // The caller gets a proxy, not the raw connection.
        assertNotSame(conn, borrowed);

        // Releasing the connection commits the operation and restores autocommit.
        borrowed.close();
        verify(conn).commit();
        verify(conn).setAutoCommit(true);
        verify(conn).close();
    }

    @Test
    @DisplayName("connection already inside a transaction only issues SET LOCAL")
    void existingTransactionOnlySetsLocal() throws Exception {
        when(conn.getAutoCommit()).thenReturn(false); // already in a Spring-managed transaction

        Connection borrowed = borrowAs("tenant-xyz");

        verify(stmt).execute("SET LOCAL app.current_tenant_id = 'tenant-xyz'");
        // We must not toggle autocommit or take over commit/close from the owning transaction.
        verify(conn, never()).setAutoCommit(anyBoolean());
        verify(conn, never()).commit();
        assertSame(conn, borrowed);
    }

    @Test
    @DisplayName("no tenant in context keeps the legacy session SET '' (admin/bypass path unchanged)")
    void noTenantUsesSessionSet() throws Exception {
        when(conn.getAutoCommit()).thenReturn(true);

        Connection borrowed = tenantDataSource.getConnection();

        verify(stmt).execute("SET app.current_tenant_id = ''");
        verify(conn, never()).setAutoCommit(anyBoolean());
        verify(conn, never()).commit();
        assertSame(conn, borrowed);
    }

    @Test
    @DisplayName("when the owning transaction commits, close does not commit again")
    void ownerCommitPreventsDoubleCommit() throws Exception {
        when(conn.getAutoCommit()).thenReturn(true, false);

        Connection borrowed = borrowAs("tenant-abc");
        borrowed.commit(); // owner (e.g. Spring) commits the transaction
        borrowed.close();

        verify(conn).commit(); // exactly once — the owner's, not a second from close()
        verify(conn).setAutoCommit(true);
        verify(conn).close();
    }

    @Test
    @DisplayName("when the owning transaction rolls back, close restores autocommit without committing")
    void ownerRollbackPreventsCommit() throws Exception {
        when(conn.getAutoCommit()).thenReturn(true, false);

        Connection borrowed = borrowAs("tenant-abc");
        borrowed.rollback();
        borrowed.close();

        verify(conn).rollback();
        verify(conn, never()).commit();
        verify(conn).setAutoCommit(true);
        verify(conn).close();
    }

    @Test
    @DisplayName("commit failure on close rolls back and still restores autocommit")
    void commitFailureRollsBack() throws Exception {
        when(conn.getAutoCommit()).thenReturn(true, false);
        org.mockito.Mockito.doThrow(new java.sql.SQLException("commit boom")).when(conn).commit();

        Connection borrowed = borrowAs("tenant-abc");
        borrowed.close();

        verify(conn).rollback();
        verify(conn).setAutoCommit(true);
        verify(conn).close();
    }

    @Test
    @DisplayName("tenant id with a quote is escaped in the SET LOCAL statement")
    void tenantIdIsEscaped() throws Exception {
        when(conn.getAutoCommit()).thenReturn(true, false);

        borrowAs("ten'ant");

        verify(stmt).execute("SET LOCAL app.current_tenant_id = 'ten''ant'");
    }

    /** Borrows a connection the way a caller inside a tenant scope would. */
    private Connection borrowAs(String tenantId) throws SQLException {
        return ScopedValue.where(TenantContext.CURRENT_TENANT, tenantId)
                .call(() -> tenantDataSource.getConnection());
    }
}
