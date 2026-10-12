package com.test365alm.server.platform.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;

import org.junit.jupiter.api.Test;

class DatabaseReadinessProbeTest {
    @Test
    void reportsDatabaseDownWhenConnectionCannotBeOpened() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("database unavailable"));

        ReadinessResult result = new DatabaseReadinessProbe(dataSource, 1000).check();

        assertThat(result).isEqualTo(ReadinessResult.databaseDown());
    }

    @Test
    void reportsDatabaseUpButMigrationNotAppliedWhenRequiredTableIsMissing() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(anyInt())).thenReturn(true);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getBoolean(1)).thenReturn(false);

        ReadinessResult result = new DatabaseReadinessProbe(dataSource, 1000).check();

        assertThat(result).isEqualTo(ReadinessResult.migrationNotApplied());
    }

    @Test
    void reportsDatabaseUpButMigrationUnknownWhenStructureCannotBeChecked() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(anyInt())).thenReturn(true);
        when(connection.prepareStatement(anyString())).thenThrow(new SQLException("metadata query failed"));

        ReadinessResult result = new DatabaseReadinessProbe(dataSource, 1000).check();

        assertThat(result).isEqualTo(ReadinessResult.migrationUnknown());
    }
}
