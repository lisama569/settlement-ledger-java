package com.github.lisama569.settlementledgerjava.tests;

import com.github.lisama569.settlementledgerjava.Application;
import com.github.lisama569.settlementledgerjava.domain.SettlementStatus;
import com.github.lisama569.settlementledgerjava.service.SettlementService;
import com.github.lisama569.settlementledgerjava.service.SnapshotService;
import com.github.lisama569.settlementledgerjava.service.dto.SettlementSnapshot;
import com.github.lisama569.settlementledgerjava.service.dto.SubmitCommand;
import com.github.lisama569.settlementledgerjava.service.dto.SubmitResult;
import com.github.lisama569.settlementledgerjava.tests.support.RestartProcessDriver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 非正常退出持久性：独立 OS 子进程用文件型 H2 受理并推进一条指令后
 * {@link Runtime#halt}（等价 kill -9，不走任何关闭钩子），随后：
 * 1) 重新拉起第二个子进程（模拟用同样的方式再启动）做只读校验；
 * 2) 在当前 JVM 再起一个 Spring 上下文继续推进，验证可接着往下走且幂等仍生效。
 */
class HardKillRestartTest {

    @TempDir
    Path tempDir;

    @Test
    void committedDataSurvivesHardKill() throws Exception {
        String dbFile = tempDir.resolve("hardkill").toAbsolutePath().toString();
        String key = "hardkill-" + UUID.randomUUID();
        // surefire 已配置 useSystemClassLoader=false，java.class.path 即完整测试 classpath，
        // 可直接用它拉起独立子进程
        String classpath = System.getProperty("java.class.path");

        // ---- 进程 1：受理 + 推进，然后 halt 硬终止 ----
        ProcessResult first = runDriver(classpath, dbFile, key);
        assertEquals(0, first.exitCode(), "driver process failed:\n" + first.output());
        Optional<String> halted = first.output().lines().filter(l -> l.startsWith("HALTED ")).findFirst();
        assertTrue(halted.isPresent(), "missing HALTED marker:\n" + first.output());
        long instructionId = Long.parseLong(extract(halted.get(), "instructionId"));
        assertEquals("VALIDATED", extract(halted.get(), "status"));
        assertEquals("1", extract(halted.get(), "version"));

        // ---- 进程 2：同样硬起一个新进程只读校验，验证与启动者是否同 JVM 无关 ----
        ProcessResult second = runDriver(classpath, dbFile, key);
        assertEquals(0, second.exitCode(), "second driver process failed:\n" + second.output());
        Optional<String> secondHalted = second.output().lines().filter(l -> l.startsWith("HALTED ")).findFirst();
        assertTrue(secondHalted.isPresent(), "missing HALTED marker on second run:\n" + second.output());
        assertEquals(instructionId, Long.parseLong(extract(secondHalted.get(), "instructionId")),
                "second process must see the same instruction id (idempotent replay)");
        assertTrue(second.output().contains("idempotent replay"),
                "second process submit must be recognized as duplicate after hard kill:\n" + second.output());

        // ---- 当前 JVM 内再开上下文：继续推进，seq 接下去、快照与实时状态一致 ----
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(Application.class)
                .web(org.springframework.boot.WebApplicationType.NONE)
                .run("--spring.datasource.url=jdbc:h2:file:" + dbFile + ";WRITE_DELAY=0;FILE_LOCK=NO",
                        "--spring.jpa.hibernate.ddl-auto=update")) {
            SettlementService settlementService = ctx.getBean(SettlementService.class);
            SnapshotService snapshotService = ctx.getBean(SnapshotService.class);

            SettlementSnapshot snapshot = snapshotService.rebuild(instructionId);
            assertEquals(SettlementStatus.VALIDATED, snapshot.status());
            assertEquals(1, snapshot.version());
            assertEquals(0, new BigDecimal("88.88").compareTo(snapshot.amount()));
            assertEquals("hard-a", snapshot.payer());

            SubmitResult dup = settlementService.submit(
                    new SubmitCommand(key, new BigDecimal("88.88"), "CNY", "hard-a", "hard-b"));
            assertTrue(dup.duplicate());
            assertEquals(instructionId, dup.instructionId());

            settlementService.advance(instructionId, snapshot.version(), SettlementStatus.SETTLED);
            assertEquals(SettlementStatus.SETTLED,
                    snapshotService.rebuild(instructionId).status());
        }
    }

    private ProcessResult runDriver(String classpath, String dbFile, String key)
            throws IOException, InterruptedException {
        // 显式指定独立 user.dir 不影响文件绝对路径；文件锁关闭，避免硬终止遗留锁
        ProcessBuilder pb = new ProcessBuilder(
                javaExecutable(),
                "-cp", classpath,
                RestartProcessDriver.class.getName(), dbFile, key);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output;
        try (InputStream in = process.getInputStream()) {
            output = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .lines().collect(Collectors.joining("\n"));
        }
        int exit = process.waitFor();
        return new ProcessResult(exit, output);
    }

    private String javaExecutable() {
        String javaHome = System.getProperty("java.home");
        Path java = Path.of(javaHome, "bin", isWindows() ? "java.exe" : "java");
        return Files.isExecutable(java) ? java.toString() : "java";
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private String extract(String line, String field) {
        String marker = field + "=";
        int start = line.indexOf(marker) + marker.length();
        int end = start;
        while (end < line.length() && line.charAt(end) != ' ') {
            end++;
        }
        return line.substring(start, end);
    }

    private record ProcessResult(int exitCode, String output) {
    }
}
