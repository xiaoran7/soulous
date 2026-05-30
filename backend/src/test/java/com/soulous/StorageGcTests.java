package com.soulous;

import com.soulous.appeal.AppealRepository;
import com.soulous.auth.UserRepository;
import com.soulous.pet.PetRepository;
import com.soulous.storage.LocalObjectStorage;
import com.soulous.storage.StorageGcTask;
import com.soulous.task.SubmissionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 【存储垃圾回收（GC）测试类：验证 StorageGcTask 对本地对象存储的孤儿文件清理逻辑，
 * 包括基于时间阈值的过期文件删除、被引用文件的保留、以及 dry-run 模式下仅日志不删除的行为。
 * 使用 @TempDir 隔离临时目录，通过 Mockito 模拟各 Repository 返回引用数据。】
 */
class StorageGcTests {

    /**
     * 【测试场景：超过时间阈值的孤儿文件应被删除，但被 Submission 引用的旧文件应保留，
     * 且未超过阈值的新文件也应保留。
     * 验证 GC 任务在 dry-run=false 时正确区分孤儿与被引用文件。】
     */
    @Test
    void deletesOrphansOlderThanThresholdButKeepsReferenced(@TempDir Path tmp) throws Exception {
        var storage = new LocalObjectStorage(tmp);
        storage.store("orphan-old.jpg", new byte[]{1, 2}, "image/jpeg");
        storage.store("kept-old.jpg", new byte[]{1, 2}, "image/jpeg");
        storage.store("fresh.jpg", new byte[]{1, 2}, "image/jpeg");

        // 将两个"旧"文件的修改时间回退到 48 小时前，使其超过 24 小时阈值
        // Push mtime back for the two "old" files
        var old = FileTime.from(Instant.now().minusSeconds(48 * 3600));
        Files.setLastModifiedTime(tmp.resolve("orphan-old.jpg"), old);
        Files.setLastModifiedTime(tmp.resolve("kept-old.jpg"), old);

        // 模拟各 Repository，仅 Submission 引用了 kept-old.jpg
        var subs = mock(SubmissionRepository.class);
        var appeals = mock(AppealRepository.class);
        var users = mock(UserRepository.class);
        var pets = mock(PetRepository.class);
        when(subs.findAll()).thenReturn(java.util.List.of(submissionWith("/uploads/kept-old.jpg")));
        when(appeals.findAll()).thenReturn(java.util.List.of());
        when(users.findAll()).thenReturn(java.util.List.of());
        when(pets.findAll()).thenReturn(java.util.List.of());

        // dry-run=false，实际执行删除操作
        // dry-run=false so the deletion actually happens
        var gc = new StorageGcTask(storage, subs, appeals, users, pets, true, 24, false);
        int gone = gc.runOnce();

        // 验证：仅孤儿文件被删除，被引用文件和新文件均保留
        assertThat(gone).isEqualTo(1);
        assertThat(Files.exists(tmp.resolve("orphan-old.jpg"))).isFalse();
        assertThat(Files.exists(tmp.resolve("kept-old.jpg"))).isTrue();
        assertThat(Files.exists(tmp.resolve("fresh.jpg"))).isTrue();
    }

    /**
     * 【测试场景：dry-run 模式下，GC 任务应仅记录日志而不实际删除任何文件，
     * 即使文件已超过时间阈值且无任何引用。】
     */
    @Test
    void dryRunLogsButDoesNotDelete(@TempDir Path tmp) throws Exception {
        var storage = new LocalObjectStorage(tmp);
        storage.store("orphan.jpg", new byte[]{1, 2}, "image/jpeg");
        Files.setLastModifiedTime(tmp.resolve("orphan.jpg"),
                FileTime.from(Instant.now().minusSeconds(48 * 3600)));

        // 所有 Repository 均无引用数据
        var subs = mock(SubmissionRepository.class);
        var appeals = mock(AppealRepository.class);
        var users = mock(UserRepository.class);
        var pets = mock(PetRepository.class);
        when(subs.findAll()).thenReturn(java.util.List.of());
        when(appeals.findAll()).thenReturn(java.util.List.of());
        when(users.findAll()).thenReturn(java.util.List.of());
        when(pets.findAll()).thenReturn(java.util.List.of());

        // dry-run=true，仅日志不删除
        var gc = new StorageGcTask(storage, subs, appeals, users, pets, true, 24, true);
        gc.runOnce();

        // 验证：文件在 dry-run 后仍然存在
        // file still present after dry-run
        assertThat(Files.exists(tmp.resolve("orphan.jpg"))).isTrue();
    }

    /**
     * 【辅助方法：创建一个带有指定 screenshotUrl 的 TaskSubmission 模拟对象，
     * 用于模拟 SubmissionRepository 中引用的上传文件路径。】
     */
    private static com.soulous.task.TaskSubmission submissionWith(String screenshotUrl) {
        var s = new com.soulous.task.TaskSubmission();
        s.screenshotUrl = screenshotUrl;
        return s;
    }
}
