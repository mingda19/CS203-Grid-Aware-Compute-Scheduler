/**
 * One-off seeding script: reads ERCOT historical Day-Ahead Market (DAM)
 * settlement point price reports out of data/HistoricDAM/*.xlsx and loads
 * the LZ_NORTH rows into a Postgres "dam_prices" table (created if it
 * doesn't already exist).
 *
 * Only settlement_point = LZ_NORTH is loaded for now; the table itself is
 * generic (keyed by settlement_point) so other zones/hubs can be loaded
 * into the same table later without a schema change.
 *
 * This is a standalone script, not part of the backend Maven build. It is
 * not wired to a database yet — fill in DB_URL / DB_USER / DB_PASSWORD
 * below once Postgres is actually provisioned.
 *
 * Expected input: data/HistoricDAM/rpt.00013060.0000000000000000.DAMLZHBSPP_<year>.xlsx
 * one workbook per year, 12 monthly sheets each, columns:
 *   Delivery Date | Hour Ending | Repeated Hour Flag | Settlement Point | Settlement Point Price
 *
 * Setup (run once from data_scripts/):
 *   mvn -f pom.xml dependency:copy-dependencies -DoutputDirectory=lib
 *
 * Run (from data_scripts/, after filling in the DB constants below):
 *   java -cp "lib/*" ExcelLoader.java
 *
 * (Java 25 can compile-and-run a single source file directly with `java Foo.java`;
 * the -cp entry just needs to include the POI and Postgres JDBC jars fetched above.)
 */

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

public class ExcelLoader {

    // --- Fill these in once Postgres is actually provisioned. ---
    private static final String DB_URL = "postgresql://postgres.uohlmgqkfxulvamevpoi:rt3i%3HqLK#G@3/@aws-0-ap-southeast-1.pooler.supabase.com:6543/postgres"; // e.g. "jdbc:postgresql://localhost:5432/gacs"
    private static final String DB_USER = "";
    private static final String DB_PASSWORD = "";

    private static final String SETTLEMENT_POINT = "LZ_NORTH";
    private static final int BATCH_SIZE = 500;

    private static final Pattern DAM_FILE_PATTERN =
            Pattern.compile("rpt\\.\\d+\\.\\d+\\.DAMLZHBSPP_(\\d{4})\\.xlsx");
    private static final DateTimeFormatter DELIVERY_DATE_FORMAT = DateTimeFormatter.ofPattern("MM/dd/yyyy");

    private static final String CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS dam_prices (
                id BIGSERIAL PRIMARY KEY,
                settlement_point VARCHAR(50) NOT NULL,
                delivery_date DATE NOT NULL,
                hour_ending SMALLINT NOT NULL CHECK (hour_ending BETWEEN 1 AND 24),
                repeated_hour_flag BOOLEAN NOT NULL DEFAULT FALSE,
                delivery_timestamp TIMESTAMP NOT NULL,
                price_usd_per_mwh NUMERIC(10, 2) NOT NULL,
                CONSTRAINT uq_dam_prices_point_date_hour
                    UNIQUE (settlement_point, delivery_date, hour_ending, repeated_hour_flag)
            )
            """;

    private static final String CREATE_INDEX_SQL = """
            CREATE INDEX IF NOT EXISTS idx_dam_prices_point_date
                ON dam_prices (settlement_point, delivery_date)
            """;

    private static final String UPSERT_SQL = """
            INSERT INTO dam_prices
                (settlement_point, delivery_date, hour_ending, repeated_hour_flag, delivery_timestamp, price_usd_per_mwh)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (settlement_point, delivery_date, hour_ending, repeated_hour_flag)
            DO UPDATE SET
                price_usd_per_mwh = EXCLUDED.price_usd_per_mwh,
                delivery_timestamp = EXCLUDED.delivery_timestamp
            """;

    public static void main(String[] args) throws Exception {
        if (DB_URL.isBlank()) {
            System.err.println(
                    "DB_URL is empty - fill in DB_URL / DB_USER / DB_PASSWORD at the top of ExcelLoader.java "
                            + "once Postgres is set up, then re-run.");
            System.exit(1);
        }

        List<Path> workbooks = findDamWorkbooks();
        if (workbooks.isEmpty()) {
            System.err.println("No DAM workbooks found under data/HistoricDAM/ (looked from " + System.getProperty("user.dir") + ")");
            System.exit(1);
        }
        System.out.println("Found " + workbooks.size() + " DAM workbook(s): " + workbooks);

        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(CREATE_TABLE_SQL);
                stmt.execute(CREATE_INDEX_SQL);
            }
            conn.commit();

            long totalInserted = 0;
            try (PreparedStatement upsert = conn.prepareStatement(UPSERT_SQL)) {
                int pending = 0;
                for (Path workbookPath : workbooks) {
                    pending = loadWorkbook(workbookPath, upsert, pending, conn);
                }
                if (pending > 0) {
                    upsert.executeBatch();
                    conn.commit();
                }
            }
            System.out.println("Done. Seeded " + SETTLEMENT_POINT + " DAM prices from " + workbooks.size() + " workbook(s).");
        }
    }

    /** Locate data/HistoricDAM either relative to the repo root or to this script's directory. */
    private static List<Path> findDamWorkbooks() throws IOException {
        Path[] candidates = {
                Path.of("data", "HistoricDAM"),
                Path.of("..", "data", "HistoricDAM"),
        };

        for (Path dir : candidates) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> files = Files.list(dir)) {
                List<Path> matches = files
                        .filter(p -> DAM_FILE_PATTERN.matcher(p.getFileName().toString()).matches())
                        .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                        .toList();
                if (!matches.isEmpty()) {
                    return matches;
                }
            }
        }
        return List.of();
    }

    private static int loadWorkbook(Path workbookPath, PreparedStatement upsert, int pending, Connection conn)
            throws SQLException, IOException {
        Matcher m = DAM_FILE_PATTERN.matcher(workbookPath.getFileName().toString());
        String year = m.matches() ? m.group(1) : "?";

        try (FileInputStream fis = new FileInputStream(workbookPath.toFile());
                XSSFWorkbook workbook = new XSSFWorkbook(fis)) {

            int rowsForYear = 0;
            for (Sheet sheet : workbook) {
                for (Row row : sheet) {
                    if (row.getRowNum() == 0) {
                        continue; // header row
                    }
                    String settlementPoint = readString(row.getCell(3));
                    if (!SETTLEMENT_POINT.equals(settlementPoint)) {
                        continue;
                    }

                    String deliveryDateStr = readString(row.getCell(0));
                    String hourEndingStr = readString(row.getCell(1));
                    if (deliveryDateStr == null || hourEndingStr == null) {
                        continue; // blank/empty trailing row
                    }

                    LocalDate deliveryDate = LocalDate.parse(deliveryDateStr, DELIVERY_DATE_FORMAT);
                    int hourEnding = Integer.parseInt(hourEndingStr.substring(0, 2));
                    boolean repeatedHour = "Y".equalsIgnoreCase(readString(row.getCell(2)));
                    double price = readNumeric(row.getCell(4));

                    // "Hour Ending 24:00" is the last hour of deliveryDate; LocalDateTime.plusHours
                    // rolls this (and any other value) over to the correct calendar day.
                    LocalDateTime deliveryTimestamp = deliveryDate.atStartOfDay().plusHours(hourEnding);

                    upsert.setString(1, SETTLEMENT_POINT);
                    upsert.setObject(2, deliveryDate);
                    upsert.setInt(3, hourEnding);
                    upsert.setBoolean(4, repeatedHour);
                    upsert.setTimestamp(5, Timestamp.valueOf(deliveryTimestamp));
                    if (Double.isNaN(price)) {
                        upsert.setNull(6, Types.NUMERIC);
                    } else {
                        upsert.setDouble(6, price);
                    }
                    upsert.addBatch();
                    pending++;
                    rowsForYear++;

                    if (pending >= BATCH_SIZE) {
                        upsert.executeBatch();
                        conn.commit();
                        pending = 0;
                    }
                }
            }
            System.out.println("  " + year + ": " + rowsForYear + " " + SETTLEMENT_POINT + " rows queued");
        }
        return pending;
    }

    private static String readString(Cell cell) {
        if (cell == null) {
            return null;
        }
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> String.valueOf(cell.getNumericCellValue());
            default -> null;
        };
    }

    private static double readNumeric(Cell cell) {
        if (cell == null) {
            return Double.NaN;
        }
        return switch (cell.getCellType()) {
            case NUMERIC -> cell.getNumericCellValue();
            case STRING -> {
                try {
                    yield Double.parseDouble(cell.getStringCellValue().trim());
                } catch (NumberFormatException e) {
                    yield Double.NaN;
                }
            }
            default -> Double.NaN;
        };
    }
}
