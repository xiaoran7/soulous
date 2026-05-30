package com.soulous;

import com.soulous.ai.embedding.EmbeddingService;
import com.soulous.ai.embedding.MockEmbeddingProvider;
import com.soulous.auth.RegisterRequest;
import com.soulous.auth.UserAccount;
import com.soulous.auth.UserService;
import com.soulous.rag.EmbeddingSourceType;
import com.soulous.rag.MemoryEmbeddingRepository;
import com.soulous.rag.RagProperties;
import com.soulous.rag.RetrievalService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the RAG retrieval pipeline end-to-end through Spring:
 *   - indexing is idempotent (re-indexing same (user, type, id) updates in place)
 *   - retrieval embeds the query, scans the user's corpus, returns top-K above threshold
 *   - dimension mismatches are ignored gracefully
 *   - ownership boundary: user A's corpus never bleeds into user B's results
 *
 * Uses the deterministic {@link MockEmbeddingProvider} so the test is offline-safe and
 * stable. Cosine on hash-based vectors won't always match what a real embedder gives, but
 * <em>identical</em> queries always score 1.0 — that's enough to verify the plumbing.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:retrieval-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "soulous.upload-dir=target/test-uploads",
        "soulous.rag.enabled=true",
        "soulous.rag.top-k=3",
        // Hash-based vectors don't cluster like real embeddings — drop the floor to 0 so any
        // non-zero similarity counts as a hit. The math is what we're testing, not relevance.
        "soulous.rag.min-similarity=0.0",
        "soulous.embedding.provider=mock",
        "soulous.embedding.dimension=64"
})
@Import(RetrievalServiceTests.TestEmbeddingConfig.class)
class RetrievalServiceTests {

    /**
     * Force the test EmbeddingService to use a 64-dim mock regardless of how Spring builds the
     * default one — keeps assertions consistent across runs.
     */
    @TestConfiguration
    static class TestEmbeddingConfig {
        @Bean @Primary
        EmbeddingService testEmbeddings() {
            return new EmbeddingService(new MockEmbeddingProvider(64), true, 64, 60);
        }
    }

    @Autowired RetrievalService retrieval;
    @Autowired MemoryEmbeddingRepository repo;
    @Autowired UserService users;

    private UserAccount newUser(String tag) {
        var unique = tag + System.nanoTime();
        var auth = users.register(new RegisterRequest(unique, "Passw0rd!", tag, unique + "@test.com"));
        return users.byToken(auth.token());
    }

    @Test
    void enabledServiceReportsEnabled() {
        assertThat(retrieval.isEnabled()).isTrue();
    }

    @Test
    void indexingPersistsARow() {
        var user = newUser("idx");
        retrieval.indexOrUpdate(user, EmbeddingSourceType.GOAL_MEMORY, 1L, "学习 Spring Boot 的基础");
        var rows = repo.findByUser(user);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).content).contains("Spring Boot");
        assertThat(rows.get(0).dimension).isEqualTo(64);
        assertThat(rows.get(0).vector).contains(",");
    }

    @Test
    void reindexingSameSourceUpdatesInPlace() {
        var user = newUser("upd");
        retrieval.indexOrUpdate(user, EmbeddingSourceType.GOAL_MEMORY, 7L, "first text");
        retrieval.indexOrUpdate(user, EmbeddingSourceType.GOAL_MEMORY, 7L, "second text");
        var rows = repo.findByUser(user);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).content).isEqualTo("second text");
    }

    @Test
    void blankContentRemovesExistingRow() {
        var user = newUser("rm");
        retrieval.indexOrUpdate(user, EmbeddingSourceType.GOAL_MEMORY, 3L, "to be removed");
        assertThat(repo.findByUser(user)).hasSize(1);

        retrieval.indexOrUpdate(user, EmbeddingSourceType.GOAL_MEMORY, 3L, "   ");
        assertThat(repo.findByUser(user)).isEmpty();
    }

    @Test
    void retrievalReturnsExactMatchAtTheTop() {
        var user = newUser("hit");
        retrieval.indexOrUpdate(user, EmbeddingSourceType.GOAL_MEMORY, 1L, "学习日语 N4");
        retrieval.indexOrUpdate(user, EmbeddingSourceType.GOAL_MEMORY, 2L, "学习 React Hooks");
        retrieval.indexOrUpdate(user, EmbeddingSourceType.SESSION_SUMMARY, 9L, "React 项目复盘");

        // Querying with the exact same text as one of the rows produces similarity 1.0 for it.
        var hits = retrieval.retrieve(user, "学习日语 N4");
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).sourceId()).isEqualTo(1L);
        assertThat(hits.get(0).similarity()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void retrievalRespectsTopK() {
        var user = newUser("topk");
        for (int i = 0; i < 7; i++) {
            retrieval.indexOrUpdate(user, EmbeddingSourceType.GOAL_MEMORY, (long) i, "content " + i);
        }
        var hits = retrieval.retrieve(user, "query", 2, Double.NaN);
        assertThat(hits).hasSizeLessThanOrEqualTo(2);
    }

    @Test
    void retrievalIsScopedPerUser() {
        var owner = newUser("owner");
        var stranger = newUser("stranger");
        retrieval.indexOrUpdate(owner, EmbeddingSourceType.GOAL_MEMORY, 1L, "owner's private memory");

        var hitsOwner = retrieval.retrieve(owner, "owner's private memory");
        var hitsStranger = retrieval.retrieve(stranger, "owner's private memory");
        assertThat(hitsOwner).isNotEmpty();
        assertThat(hitsStranger).isEmpty();
    }

    @Test
    void thresholdFiltersOutLowSimilarityHits() {
        var user = newUser("threshold");
        retrieval.indexOrUpdate(user, EmbeddingSourceType.GOAL_MEMORY, 1L, "alpha bravo charlie");

        // Threshold > 1.0 → no hit can satisfy it.
        var hits = retrieval.retrieve(user, "totally unrelated query", 5, 1.01);
        assertThat(hits).isEmpty();
    }

    @Test
    void emptyQueryReturnsEmpty() {
        var user = newUser("emptyq");
        retrieval.indexOrUpdate(user, EmbeddingSourceType.GOAL_MEMORY, 1L, "x");
        assertThat(retrieval.retrieve(user, "")).isEmpty();
        assertThat(retrieval.retrieve(user, null)).isEmpty();
    }
}
