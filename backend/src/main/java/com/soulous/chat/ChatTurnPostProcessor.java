package com.soulous.chat;

import com.soulous.auth.UserAccount;
import com.soulous.moderation.ModerationService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 【AI 回复收尾后台处理器】
 *
 * <p>输出审核（LLM 级，单次数秒）原先同步挡在「流式回复结束 → done 事件返回」之间：
 * 用户已看到完整回复，光标却还停在原地干等审核跑完，体感卡死。
 * 现改为「先落库立即返回，审核甩到后台」：</p>
 * <ul>
 *   <li>用户操作 → 立即反馈：回复正文与计划草案随 done 事件即时返回；</li>
 *   <li>耗时操作 → 后台执行：审核在独立线程池里跑，不占请求线程；</li>
 *   <li>失败处理 → 异步补救：命中违规时回写该条消息为拦截文案并作废待确认计划，
 *       用户下次拉取对话即看到替换结果（输出审核本就是纵深防御的第二道闸，
 *       入口侧 moderateInput 仍然同步拦截）。</li>
 * </ul>
 *
 * <p>任务通过 afterCommit 钩子提交：消息行在调用方事务提交后才对后台线程可见，
 * 提前提交会读不到数据。事务外调用（理论上不会发生）直接入队。</p>
 */
@Component
public class ChatTurnPostProcessor {
    private static final Logger log = LoggerFactory.getLogger(ChatTurnPostProcessor.class);

    private final ModerationService moderation;
    private final ChatMessageRepository messages;
    private final ChatConversationRepository conversations;
    /** 单线程顺序执行：审核 QPS 极低，避免并发打满 LLM 配额；守护线程不阻塞 JVM 退出 */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "chat-output-moderation");
        t.setDaemon(true);
        return t;
    });

    public ChatTurnPostProcessor(ModerationService moderation,
                                 ChatMessageRepository messages,
                                 ChatConversationRepository conversations) {
        this.moderation = moderation;
        this.messages = messages;
        this.conversations = conversations;
    }

    /**
     * 【调度一次后台输出审核。blockedReply 为命中违规时回写的替换文案。】
     *
     * @param user      回复所属用户（仅用于审计落库，跨线程只读）
     * @param convId    对话 ID
     * @param messageId 刚落库的助手消息 ID
     * @param reply     助手回复原文
     * @param userInput 触发该回复的用户输入（审核上下文）
     */
    public void moderateOutputAsync(UserAccount user, Long convId, Long messageId,
                                    String reply, String userInput, String blockedReply) {
        Runnable enqueue = () -> executor.submit(
                () -> runModeration(user, convId, messageId, reply, userInput, blockedReply));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { enqueue.run(); }
            });
        } else {
            enqueue.run();
        }
    }

    private void runModeration(UserAccount user, Long convId, Long messageId,
                               String reply, String userInput, String blockedReply) {
        try {
            var verdict = moderation.moderateOutput(user, reply, userInput, List.of(), convId);
            if (!verdict.blocked()) return;
            log.warn("Output blocked (async) for conversation {}: {}", convId, verdict.reason());
            messages.findById(messageId).ifPresent(msg -> {
                msg.content = blockedReply;
                messages.save(msg);
            });
            conversations.findById(convId).ifPresent(conv -> {
                if (conv.pendingPlanJson != null) {
                    conv.pendingPlanJson = null;
                    conversations.save(conv);
                }
            });
        } catch (Exception ex) {
            // fail-open：审核自身故障不影响已返回的回复，只记日志
            log.warn("Async output moderation failed for conversation {}: {}", convId, ex.getMessage());
        }
    }

    /** 【测试/停机用：等待队列中的审核任务跑完】 */
    public void drain(long timeoutMillis) {
        try {
            executor.submit(() -> { }).get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) { /* 超时或中断：尽力而为 */ }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
