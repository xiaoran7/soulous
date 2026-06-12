package com.soulous.agent;

import com.soulous.rag.MemoryEmbeddingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 【存量 RAG 语料回灌：把 memory_embedding 表中的历史记忆推送进 agent 向量库】
 *
 * <p>一次性迁移工具：soulous.agent.backfill=true 时启动后台执行，幂等（agent 侧
 * (user, sourceType, sourceId) 唯一约束 upsert），完成后应关掉开关。
 * agent 侧用自己的 embedding 模型重新向量化，原向量列不迁移。</p>
 */
@Component
public class AgentRagBackfill implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AgentRagBackfill.class);

    private final AgentProperties props;
    private final AgentClient agent;
    private final MemoryEmbeddingRepository embeddings;

    public AgentRagBackfill(AgentProperties props, AgentClient agent, MemoryEmbeddingRepository embeddings) {
        this.props = props;
        this.agent = agent;
        this.embeddings = embeddings;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.isBackfill() || !agent.enabled()) return;
        var rows = embeddings.findAll();
        log.info("agent RAG 回灌启动：{} 条存量记忆", rows.size());
        var pushed = 0;
        for (var row : rows) {
            if (row.user == null || row.user.id == null || row.content == null || row.content.isBlank()) continue;
            agent.ragUpsertAsync(row.user.id, row.sourceType.name(), row.sourceId, row.content);
            pushed++;
        }
        log.info("agent RAG 回灌已入队 {} 条（异步推送，详见 agent-rag-push 线程日志）", pushed);
    }
}
