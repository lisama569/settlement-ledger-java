package com.github.lisama569.settlementledgerjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lisama569.settlementledgerjava.Application;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实“进程被直接杀掉（SIGKILL，无优雅退出）-> 重新起一个进程”持久性用例。
 *
 * <p>与 {@link RestartPersistenceTest} 关闭 Spring 上下文不同，本用例 fork 独立 JVM 跑真实
 * Web 服务，再用 {@link Process#destroyForcibly()}（POSIX 下即 SIGKILL）终止，
 * JVM 与 H2 都没有机会执行任何优雅关闭/fsync 之外的收尾。重启的是另一个全新 JVM。
 */
class HardKillRestartTest {

    private static final Logger log = LoggerFactory.getLogger(HardKillRestartTest.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    /**
     * 独立 JVM 驱动入口：以文件型 H2 启动真实 Web 服务，就绪后写标记文件并阻塞。
     * 父测试用 {@link Process#destroyForcibly()} 直接 SIGKILL，绝不走优雅退出。
     *
     * <p>参数：&lt;dbFile&gt; &lt;port&gt; &lt;readyMarker&gt;
     */
    public static void main(String[] args) throws Exception {
        String dbFile = args[0];
        String port = args[1];
        Path readyMarker = Path.of(args[2]);

        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(Application.class).run(
                "--spring.datasource.url=jdbc:h2:file:" + dbFile + ";AUTO_SERVER=TRUE;WRITE_DELAY=0",
                "--spring.jpa.hibernate.ddl-auto=update",
                "--server.port=" + port);

        Files.writeString(readyMarker, "ready", StandardCharsets.UTF_8);
        // 阻塞直到被 SIGKILL。
        new CountDownLatch(1).await();
    }

    @Test
    void acknowledgedWritesSurviveSigkillAndRestart() throws Exception {
        String dbFile = tempDir.resolve("hardkill").toAbsolutePath().toString();
        String key = "hardkill-" + UUID.randomUUID();
        String runId = "run-hardkill-" + UUID.randomUUID();

        int port1 = freePort();
        int port2 = freePort();
        String base1 = "http://localhost:" + port1;
        String base2 = "http://localhost:" + port2;
        Path marker = tempDir.resolve("ready-" + UUID.randomUUID() + ".txt");

        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        long instructionId;

        // ---- 进程 1：受理 + 推进 + 归档，全部收到成功响应后直接 SIGKILL ----
        Process p1 = startServer(dbFile, port1, marker);
        try {
            awaitReady(p1, http, base1, marker);

            JsonNode submit = post(http, base1 + "/api/settlements",
                    """
                    {"idempotencyKey":"%s","amount":100.00,"currency":"CNY","payer":"alice","payee":"bob"}
                    """.formatted(key));
            instructionId = submit.get("instructionId").asLong();
            assertFalse(submit.get("duplicate").asBoolean());
            assertEquals(0L, submit.get("version").asLong());

            post(http, base1 + "/api/settlements/" + instructionId
                    + "/advance?expectedVersion=0&target=VALIDATED", null);

            // 归档全部历史（cutoff 放到未来），确认已提交
            JsonNode archived = post(http,
                    base1 + "/api/settlements/archives?runId=" + runId
                            + "&cutoff=2030-01-01T00:00:00Z", null);
            assertTrue(archived.get("completed").asBoolean());
            assertEquals(2, archived.get("archivedCount").asInt());

            String snapshotBefore = get(http,
                    base1 + "/api/settlements/" + instructionId + "/snapshot");
            Files.writeString(tempDir.resolve("snapshot-before.json"), snapshotBefore);
            // 导出基线也在硬杀前固定下来，供重启后逐字段比对
            Files.writeString(tempDir.resolve("export-before.json"),
                    get(http, base1 + "/api/settlements/snapshots?size=100"));
            log.info("hard-kill phase1 ok: key={} instructionId={} archived=2", key, instructionId);

            // 直接 SIGKILL：不等任何优雅退出
            p1.destroyForcibly();
            assertTrue(p1.waitFor(20, java.util.concurrent.TimeUnit.SECONDS), "process must die");
            log.info("process SIGKILLed: exitValue={}", p1.exitValue());
        } finally {
            if (p1.isAlive()) {
                p1.destroyForcibly();
            }
        }

        // ---- 进程 2：全新 JVM 起同一份库文件，验证全部持久化保证 ----
        Path marker2 = tempDir.resolve("ready2-" + UUID.randomUUID() + ".txt");
        Process p2 = startServer(dbFile, port2, marker2);
        try {
            awaitReady(p2, http, base2, marker2);

            // 快照逐字段一致（含已归档历史的合并重建）
            String snapshotAfter = get(http,
                    base2 + "/api/settlements/" + instructionId + "/snapshot");
            String snapshotBefore = Files.readString(tempDir.resolve("snapshot-before.json"));
            assertEquals(JSON.readTree(snapshotBefore), JSON.readTree(snapshotAfter),
                    "snapshot rebuilt after hard kill must be field-by-field identical");

            // 重放与实时状态一致
            JsonNode replayed = JSON.readTree(get(http, base2 + "/api/settlements/" + instructionId + "/replay"));
            assertEquals("VALIDATED", replayed.get("status").asText());

            // 重复受理仍然幂等：返回原来那条，不新建
            JsonNode dup = post(http, base2 + "/api/settlements",
                    """
                    {"idempotencyKey":"%s","amount":999.00,"currency":"EUR","payer":"x","payee":"y"}
                    """.formatted(key));
            assertTrue(dup.get("duplicate").asBoolean());
            assertEquals(1L, dup.get("instructionId").asLong());

            // 已归档历史不重复归档：同一 runId 重放原结果
            JsonNode archivedAgain = post(http,
                    base2 + "/api/settlements/archives?runId=" + runId
                            + "&cutoff=2030-01-01T00:00:00Z", null);
            assertTrue(archivedAgain.get("alreadyApplied").asBoolean());
            assertEquals(2, archivedAgain.get("archivedCount").asInt());

            // 导出跨硬重启一致 + 同段数据重复导出稳定可复现（此时尚未继续推进，状态与硬杀前相同）
            String exportBeforeKill = Files.readString(tempDir.resolve("export-before.json"));
            String ex1 = get(http, base2 + "/api/settlements/snapshots?size=100");
            String ex2 = get(http, base2 + "/api/settlements/snapshots?size=100");
            assertEquals(JSON.readTree(exportBeforeKill), JSON.readTree(ex1),
                    "export must be identical across a hard kill/restart");
            assertEquals(JSON.readTree(ex1), JSON.readTree(ex2),
                    "repeated export after hard restart must be reproducible");
            JsonNode export = JSON.readTree(ex1);
            assertEquals(1, export.get("items").size());
            assertTrue(export.get("items").get(0).get("ok").asBoolean());

            // 重启后继续推进，seq 接历史往下走
            JsonNode settled = post(http, base2 + "/api/settlements/" + instructionId
                    + "/advance?expectedVersion=1&target=SETTLED", null);
            assertEquals(2L, settled.get("version").asLong());
            JsonNode finalSnap = JSON.readTree(
                    get(http, base2 + "/api/settlements/" + instructionId + "/snapshot"));
            assertEquals("SETTLED", finalSnap.get("status").asText());
            assertEquals(2L, finalSnap.get("version").asLong());
            log.info("hard-kill restart persistence verified: key={}", key);
        } finally {
            p2.destroyForcibly();
            p2.waitFor(20, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    // ---- 子进程装配与 HTTP 辅助 ----

    private Process startServer(String dbFile, int port, Path readyMarker) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(javaExecutable());
        cmd.add("-cp");
        cmd.add(runtimeClasspath());
        cmd.add(HardKillRestartTest.class.getName());
        cmd.add(dbFile);
        cmd.add(String.valueOf(port));
        cmd.add(readyMarker.toAbsolutePath().toString());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Path logFile = tempDir.resolve("server-" + port + ".log");
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        return pb.start();
    }

    private void awaitReady(Process process, HttpClient http, String base, Path marker) throws Exception {
        long deadline = System.currentTimeMillis() + 90_000;
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive()) {
                throw new IllegalStateException("server process exited early, exitValue=" + process.exitValue());
            }
            if (Files.exists(marker)) {
                // 再探活一次 HTTP，确保 Tomcat 已可接请求
                try {
                    get(http, base + "/api/settlements/audit?size=1");
                    return;
                } catch (Exception ignored) {
                    // marker 已写但端口尚未就绪，稍等
                }
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException("server did not become ready in time");
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private String get(HttpClient http, String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15)).GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("GET " + url + " -> " + resp.statusCode() + ": " + resp.body());
        }
        return resp.body();
    }

    private JsonNode post(HttpClient http, String url, String jsonBody) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15));
        if (jsonBody == null) {
            b.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            b.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        }
        HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("POST " + url + " -> " + resp.statusCode() + ": " + resp.body());
        }
        return JSON.readTree(resp.body());
    }

    private String javaExecutable() {
        String javaHome = System.getProperty("java.home");
        Path exe = Path.of(javaHome, "bin", "java");
        return Files.isExecutable(exe) ? exe.toString() : "java";
    }

    private String runtimeClasspath() {
        StringBuilder cp = new StringBuilder();
        cp.append(System.getProperty("java.class.path"));
        return cp.toString();
    }
}
