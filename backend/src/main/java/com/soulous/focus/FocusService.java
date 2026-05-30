package com.soulous.focus;

import com.soulous.auth.UserAccount;
import com.soulous.common.exception.BadRequestException;
import com.soulous.common.exception.ForbiddenException;
import com.soulous.common.exception.NotFoundException;
import com.soulous.pet.PetService;
import com.soulous.task.StudyTask;
import com.soulous.task.TaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 【专注会话业务服务：管理用户的专注计时会话，支持开始、暂停、恢复、完成、中止等操作。
 *  专注完成后自动计算经验值奖励并通知宠物系统，同时如果关联了学习任务，
 *  会将专注时长累加到任务的实际用时字段。
 *  同一用户同一时间只能有一个进行中（RUNNING 或 PAUSED）的专注会话。】
 */
@Service
public class FocusService {
    /** 【专注会话数据仓库】 */
    private final FocusSessionRepository sessions;
    /** 【宠物服务，用于在专注完成后发放经验奖励】 */
    private final PetService pets;
    /** 【任务数据仓库，用于关联学习任务和更新实际用时】 */
    private final TaskRepository tasks;

    FocusService(FocusSessionRepository sessions, PetService pets, TaskRepository tasks) {
        this.sessions = sessions;
        this.pets = pets;
        this.tasks = tasks;
    }

    /**
     * 【获取用户的所有专注会话列表，按创建时间倒序排列。】
     *
     * @param user 【当前登录用户】
     * @return 【专注会话列表】
     */
    public List<FocusSession> list(UserAccount user) {
        return sessions.findByUserOrderByCreatedAtDesc(user);
    }

    /**
     * 【获取用户当前活跃的专注会话（状态为 RUNNING 或 PAUSED）。
     *  如果没有进行中的会话返回 null。】
     *
     * @param user 【当前登录用户】
     * @return 【当前活跃的专注会话，无活跃会话时返回 null】
     */
    public FocusSession active(UserAccount user) {
        return sessions.findFirstByUserAndStatusInOrderByCreatedAtDesc(
                user, List.of(FocusStatus.RUNNING, FocusStatus.PAUSED)).orElse(null);
    }

    /**
     * 【开始新的专注会话：创建一个新的 FocusSession 并保存。
     *  前置条件：用户没有其他进行中的会话，标题不能为空。
     *  可选关联一个学习任务，关联后专注完成时会更新任务的实际用时。
     *  默认计划时长为25分钟（番茄钟）。】
     *
     * @param user 【当前登录用户】
     * @param req 【专注会话请求，包含标题、计划时长、可选的任务 ID】
     * @return 【新创建的专注会话】
     * @throws BadRequestException 【已有进行中会话或标题为空时抛出】
     * @throws ForbiddenException 【关联的任务不属于当前用户时抛出】
     */
    @Transactional
    public FocusSession start(UserAccount user, FocusSessionRequest req) {
        // 【检查是否已有进行中的会话，同一时间只允许一个】
        sessions.findFirstByUserAndStatusInOrderByCreatedAtDesc(
                user, List.of(FocusStatus.RUNNING, FocusStatus.PAUSED))
                .ifPresent(s -> { throw new BadRequestException("已有进行中的专注，请先完成或中止当前会话"); });
        if (req.title() == null || req.title().isBlank()) throw new BadRequestException("标题不能为空");
        var session = new FocusSession();
        session.user = user;
        session.title = req.title().trim();
        // 【默认25分钟（番茄钟），如果指定了正数则使用指定值】
        session.plannedMinutes = req.plannedMinutes() != null && req.plannedMinutes() > 0 ? req.plannedMinutes() : 25;
        if (req.taskId() != null) {
            var task = tasks.findById(req.taskId())
                    .orElseThrow(() -> new BadRequestException("任务不存在"));
            if (!task.user.id.equals(user.id)) throw new ForbiddenException("无权关联他人任务");
            session.taskId = task.id;
        }
        return sessions.save(session);
    }

    /**
     * 【暂停专注会话：将状态从 RUNNING 切换为 PAUSED。
     *  暂停时会累计从上次启动到当前的已用时间，并清除 lastStartedAt。
     *  暂停期间不计入已用时间。】
     *
     * @param user 【当前登录用户】
     * @param id 【专注会话 ID】
     * @return 【暂停后的专注会话】
     * @throws BadRequestException 【当前状态不是 RUNNING 时抛出】
     */
    @Transactional
    public FocusSession pause(UserAccount user, Long id) {
        var session = get(user, id);
        if (session.status != FocusStatus.RUNNING) throw new BadRequestException("当前不是运行中状态");
        // 【计算本次运行段的秒数并累加到总已用时间】
        long extra = Duration.between(session.lastStartedAt, LocalDateTime.now()).getSeconds();
        session.elapsedSeconds += (int) extra;
        session.status = FocusStatus.PAUSED;
        session.lastStartedAt = null;
        return sessions.save(session);
    }

    /**
     * 【恢复专注会话：将状态从 PAUSED 切换回 RUNNING。
     *  恢复时重新记录 lastStartedAt，暂停期间的时间不计入已用时间。】
     *
     * @param user 【当前登录用户】
     * @param id 【专注会话 ID】
     * @return 【恢复后的专注会话】
     * @throws BadRequestException 【当前状态不是 PAUSED 时抛出】
     */
    @Transactional
    public FocusSession resume(UserAccount user, Long id) {
        var session = get(user, id);
        if (session.status != FocusStatus.PAUSED) throw new BadRequestException("当前不是暂停状态");
        session.status = FocusStatus.RUNNING;
        session.lastStartedAt = LocalDateTime.now();
        return sessions.save(session);
    }

    /**
     * 【完成/中止专注会话：根据 outcome 参数决定最终状态。
     *  - "aborted"：中止会话，状态变为 ABORTED，不发放经验奖励。
     *  - 其他值：完成会话，状态变为 COMPLETED，发放经验奖励。
     *  经验值计算：min(60, max(5, 已用分钟数))，即最少5点最多60点。
     *  如果关联了学习任务，会将专注时长累加到任务的 actualMinutes 字段。】
     *
     * @param user 【当前登录用户】
     * @param id 【专注会话 ID】
     * @param req 【完成请求体，outcome 为 "aborted" 表示中止】
     * @return 【完成后的专注会话】
     * @throws BadRequestException 【会话已结束时抛出】
     */
    @Transactional
    public FocusSession finish(UserAccount user, Long id, FocusFinishRequest req) {
        var session = get(user, id);
        if (session.status == FocusStatus.COMPLETED || session.status == FocusStatus.ABORTED)
            throw new BadRequestException("会话已结束");
        // 【如果当前正在运行，先累计最后一段已用时间】
        if (session.status == FocusStatus.RUNNING && session.lastStartedAt != null) {
            long extra = Duration.between(session.lastStartedAt, LocalDateTime.now()).getSeconds();
            session.elapsedSeconds += (int) extra;
        }
        session.status = "aborted".equals(req.outcome()) ? FocusStatus.ABORTED : FocusStatus.COMPLETED;
        session.endedAt = LocalDateTime.now();
        sessions.save(session);
        // 【只有完成状态才发放经验奖励和更新任务用时】
        if (session.status == FocusStatus.COMPLETED) {
            int minutes = session.elapsedSeconds / 60;
            // 【经验公式：min(60, max(5, 分钟数))，鼓励长时间专注但有上限】
            int exp = Math.min(60, Math.max(5, minutes));
            pets.addFocusExp(user, exp, "专注完成：" + session.title + "（" + minutes + " 分钟）");
            // 【如果关联了学习任务且有专注时长，累加到任务的实际用时】
            if (session.taskId != null && minutes > 0) {
                tasks.findById(session.taskId).ifPresent(task -> {
                    if (task.user.id.equals(user.id)) {
                        int current = task.actualMinutes == null ? 0 : task.actualMinutes;
                        task.actualMinutes = current + minutes;
                        tasks.save(task);
                    }
                });
            }
        }
        return session;
    }

    /**
     * 【获取并校验专注会话所有权：查找会话并验证当前用户是否为所有者。】
     *
     * @param user 【当前登录用户】
     * @param id 【专注会话 ID】
     * @return 【专注会话实体】
     * @throws NotFoundException 【会话不存在时抛出】
     * @throws ForbiddenException 【会话不属于当前用户时抛出】
     */
    private FocusSession get(UserAccount user, Long id) {
        var session = sessions.findById(id).orElseThrow(() -> new NotFoundException("专注记录不存在"));
        if (!session.user.id.equals(user.id)) throw new ForbiddenException("无权操作");
        return session;
    }
}
