import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;

/** One-time, fail-closed copy of the five legacy business tables. */
public final class MigrateMySqlToPostgres {

    private record Table(String source, String destination, List<String> columns,
                         List<String> timestampColumns) {
    }

    private static final List<Table> TABLES = List.of(
            new Table("`user`", "app_user",
                    List.of("id", "username", "password", "email", "create_time", "update_time"),
                    List.of("create_time", "update_time")),
            new Table("question", "question",
                    List.of("id", "category", "title", "content", "difficulty", "answer", "create_time"),
                    List.of("create_time")),
            new Table("interview_session", "interview_session",
                    List.of("id", "user_id", "category", "difficulty", "status", "start_time", "end_time", "create_time"),
                    List.of("start_time", "end_time", "create_time")),
            new Table("interview_message", "interview_message",
                    List.of("id", "session_id", "role", "content", "create_time"),
                    List.of("create_time")),
            new Table("interview_evaluation", "interview_evaluation",
                    List.of("id", "session_id", "score", "strengths", "weaknesses", "suggestions", "create_time"),
                    List.of("create_time"))
    );

    public static void main(String[] args) throws Exception {
        try (Connection mysql = DriverManager.getConnection(
                required("MYSQL_URL"), required("MYSQL_USER"), required("MYSQL_PASSWORD"));
             Connection postgres = DriverManager.getConnection(
                     required("POSTGRES_URL"), required("POSTGRES_USER"), required("POSTGRES_PASSWORD"))) {

            mysql.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            mysql.setReadOnly(true);
            mysql.setAutoCommit(false);
            postgres.setAutoCommit(false);

            try {
                for (Table table : TABLES) {
                    if (count(postgres, table.destination()) != 0) {
                        throw new IllegalStateException("Destination table is not empty: " + table.destination());
                    }
                }

                for (Table table : TABLES) {
                    long copied = copy(mysql, postgres, table);
                    long sourceCount = count(mysql, table.source());
                    long destinationCount = count(postgres, table.destination());
                    if (copied != sourceCount || copied != destinationCount) {
                        throw new IllegalStateException("Row count mismatch in " + table.destination());
                    }
                    resetIdentity(postgres, table.destination());
                    System.out.println(table.destination() + ": " + copied + " rows verified");
                }
                postgres.commit();
                System.out.println("Migration committed. Existing vector_store was untouched.");
            } catch (Exception ex) {
                postgres.rollback();
                throw ex;
            }
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing environment variable: " + name);
        }
        return value;
    }

    private static long copy(Connection mysql, Connection postgres, Table table) throws SQLException {
        String columns = String.join(", ", table.columns());
        String placeholders = String.join(", ", table.columns().stream().map(ignored -> "?").toList());
        String select = "SELECT " + columns + " FROM " + table.source() + " ORDER BY id";
        String insert = "INSERT INTO " + table.destination() + " (" + columns + ") VALUES (" + placeholders + ")";

        long copied = 0;
        try (Statement query = mysql.createStatement();
             ResultSet rows = query.executeQuery(select);
             PreparedStatement write = postgres.prepareStatement(insert)) {
            while (rows.next()) {
                for (int i = 0; i < table.columns().size(); i++) {
                    String column = table.columns().get(i);
                    Object value = table.timestampColumns().contains(column)
                            ? rows.getObject(column, LocalDateTime.class)
                            : rows.getObject(column);
                    write.setObject(i + 1, value);
                }
                write.addBatch();
                copied++;
            }
            if (copied > 0) {
                write.executeBatch();
            }
        }
        return copied;
    }

    private static long count(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static void resetIdentity(Connection postgres, String table) throws SQLException {
        String sql = "SELECT setval(pg_get_serial_sequence('" + table + "', 'id'), "
                + "COALESCE((SELECT MAX(id) FROM " + table + "), 1), "
                + "EXISTS (SELECT 1 FROM " + table + "))";
        try (Statement statement = postgres.createStatement()) {
            statement.execute(sql);
        }
    }
}
