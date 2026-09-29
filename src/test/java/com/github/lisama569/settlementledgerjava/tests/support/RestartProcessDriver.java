package com.github.lisama569.settlementledgerjava.tests.support;

import com.github.lisama569.settlementledgerjava.Application;
import com.github.lisama569.settlementledgerjava.domain.SettlementStatus;
import com.github.lisama569.settlementledgerjava.service.SettlementService;
import com.github.lisama569.settlementledgerjava.service.SnapshotService;
import com.github.lisama569.settlementledgerjava.service.dto.SettlementSnapshot;
import com.github.lisama569.settlementledgerjava.service.dto.SubmitCommand;
import com.github.lisama569.settlementledgerjava.service.dto.SubmitResult;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.math.BigDecimal;

/**
 * 重启持久性测试用的独立进程入口（由 {@code HardKillRestartTest} 以 OS 子进程方式启动）。
 *
 * <p>动作：用文件型 H2 启动应用 -> 受理 -> 推进 -> 重建快照，
 * 然后调用 {@link Runtime#halt} 直接终止 JVM，<b>不执行任何 shutdown hook</b>，
 * 等价于线上进程被 kill -9 / 掉电。
 *
 * <p>参数：dbFile idempotencyKey
 * 标准输出最后一行打印 {@code HALTED instructionId=.. status=.. version=..}，供父进程核对。
 */
public final class RestartProcessDriver {

    private RestartProcessDriver() {
    }

    public static void main(String[] args) {
        String dbFile = args[0];
        String key = args[1];
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(Application.class)
                .web(org.springframework.boot.WebApplicationType.NONE)
                .run(
                        "--spring.datasource.url=jdbc:h2:file:" + dbFile
                                + ";WRITE_DELAY=0;FILE_LOCK=NO",
                        "--spring.jpa.hibernate.ddl-auto=update");
        try {
            SettlementService settlementService = ctx.getBean(SettlementService.class);
            SnapshotService snapshotService = ctx.getBean(SnapshotService.class);

            SubmitResult submitted = settlementService.submit(
                    new SubmitCommand(key, new BigDecimal("88.88"), "CNY", "hard-a", "hard-b"));
            if (!submitted.duplicate()) {
                settlementService.advance(submitted.instructionId(), submitted.version(),
                        SettlementStatus.VALIDATED);
            }
            SettlementSnapshot snapshot = snapshotService.rebuild(submitted.instructionId());

            // 强制立即刷出 stdout 后硬终止：不触发 Spring/H2 的任何关闭逻辑
            System.out.println("HALTED instructionId=" + snapshot.instructionId()
                    + " status=" + snapshot.status() + " version=" + snapshot.version());
            System.out.flush();
            Runtime.getRuntime().halt(0);
        } finally {
            // halt 之前不调用 ctx.close()：故意模拟非正常退出
        }
    }
}
