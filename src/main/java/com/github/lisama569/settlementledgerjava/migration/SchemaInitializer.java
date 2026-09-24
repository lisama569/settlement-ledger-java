package com.github.lisama569.settlementledgerjava.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.annotation.PostConstruct;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 嵌入式数据库的结构自举：维护 schema_version 元数据表，
 * 按版本号顺序执行 db/migration/V*.sql，保证历史库只增量演进、新库一次到位。
 * 每个迁移与其版本登记在同一事务内提交，避免半条迁移。
 */
@Component
public class SchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(SchemaInitializer.class);
    private static final Pattern VERSION_PATTERN = Pattern.compile("V(\\d+)__.*\\.sql");

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public SchemaInitializer(DataSource dataSource,
                             JdbcTemplate jdbcTemplate,
                             PlatformTransactionManager transactionManager) {
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @PostConstruct
    public void initialize() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS schema_version (
                    version      INT PRIMARY KEY,
                    script_name  VARCHAR(256) NOT NULL,
                    applied_at   TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);

        Set<Integer> applied = new TreeSet<>(jdbcTemplate.query(
                "SELECT version FROM schema_version",
                (rs, rowNum) -> rs.getInt("version")));

        List<Resource> migrations = discoverMigrations();
        for (Resource migration : migrations) {
            String fileName = migration.getFilename();
            Matcher matcher = VERSION_PATTERN.matcher(fileName == null ? "" : fileName);
            if (!matcher.matches()) {
                continue;
            }
            int version = Integer.parseInt(matcher.group(1));
            if (applied.contains(version)) {
                continue;
            }
            transactionTemplate.executeWithoutResult(status -> {
                new ResourceDatabasePopulator(false, false, StandardCharsets.UTF_8.name(), migration)
                        .execute(dataSource);
                jdbcTemplate.update("INSERT INTO schema_version(version, script_name) VALUES (?, ?)",
                        version, fileName);
            });
            log.info("schema migration applied: V{} ({})", version, fileName);
        }
    }

    private List<Resource> discoverMigrations() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath:db/migration/V*.sql");
            return Arrays.stream(resources)
                    .filter(r -> r.getFilename() != null && VERSION_PATTERN.matcher(r.getFilename()).matches())
                    .sorted(Comparator.comparingInt(this::versionOf))
                    .toList();
        } catch (Exception e) {
            throw new IllegalStateException("failed to discover schema migrations", e);
        }
    }

    private int versionOf(Resource resource) {
        Matcher matcher = VERSION_PATTERN.matcher(resource.getFilename());
        matcher.matches();
        return Integer.parseInt(matcher.group(1));
    }
}
