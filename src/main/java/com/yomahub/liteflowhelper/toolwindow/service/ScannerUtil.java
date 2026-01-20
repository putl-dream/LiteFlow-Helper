package com.yomahub.liteflowhelper.toolwindow.service;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * 扫描器工具类，提供通用的扫描操作封装。
 */
public class ScannerUtil {

    /**
     * 在读操作中执行扫描任务，自动处理 Dumb Mode 和项目状态检查。
     *
     * @param project 当前项目
     * @param taskName 任务名称（用于日志）
     * @param scanner 扫描逻辑
     * @param <T> 返回类型
     * @return 扫描结果，如果项目处于 Dumb Mode 或已释放则返回空列表
     */
    public static <T> List<T> runInReadAction(
            @NotNull Project project,
            @NotNull String taskName,
            @NotNull Supplier<List<T>> scanner) {
        Logger log = Logger.getInstance(ScannerUtil.class);

        if (DumbService.getInstance(project).isDumb()) {
            log.info("项目正处于 dumb mode。" + taskName + "扫描已推迟。");
            return Collections.emptyList();
        }

        log.info("========== 开始扫描 " + taskName + " ==========");

        return ApplicationManager.getApplication().runReadAction((Computable<List<T>>) () -> {
            if (project.isDisposed()) {
                return Collections.emptyList();
            }
            if (DumbService.getInstance(project).isDumb()) {
                log.info("在为" + taskName + "调度读操作期间，项目进入了 dumb mode。正在跳过。");
                return Collections.emptyList();
            }

            List<T> result = scanner.get();
            log.info("========== 扫描完成，共找到 " + result.size() + " 个 " + taskName + " ==========");
            return result;
        });
    }

    private ScannerUtil() {
        // 工具类，禁止实例化
    }
}
