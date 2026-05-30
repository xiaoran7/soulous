package com.soulous.config;

import com.soulous.auth.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Arrays;

/**
 * 【应用启动配置类 —— 定义应用启动时执行的初始化逻辑。
 * 包含一个 CommandLineRunner Bean，在 Spring 容器完全就绪后执行以下任务：
 * <ul>
 *   <li>生产环境安全检查：拒绝使用开发默认 JWT 密钥启动</li>
 *   <li>H2 数据库遗留 Schema 迁移：兼容旧版数据库结构</li>
 *   <li>引导管理员账户创建：通过配置文件指定初始管理员</li>
 * </ul>】
 */
@Configuration
public class AppConfig {
    /** 【开发环境默认 JWT 密钥 —— 生产环境中必须替换为强密钥】 */
    private static final String DEV_JWT_SECRET = "soulous-default-dev-secret-please-change-in-production-32bytes";

    /**
     * 【应用启动时执行的初始化任务。
     *
     * <p>执行流程：</p>
     * <ol>
     *   <li>检查当前是否为生产环境（prod profile），若是且 JWT 密钥仍为默认值，
     *       则抛出 IllegalStateException 阻止启动，防止安全风险</li>
     *   <li>执行 H2 数据库的遗留 Schema 迁移，确保旧版数据库兼容新代码</li>
     *   <li>若配置了引导管理员用户名，则创建或更新管理员账户</li>
     * </ol>
     *
     * @param users                【用户服务，用于管理员账户引导】
     * @param dataSource           【数据源，用于直接执行 SQL 迁移】
     * @param env                  【Spring 环境对象，用于读取激活的 Profile】
     * @param jwtSecret            【JWT 密钥，从配置项 soulous.jwt.secret 读取】
     * @param bootstrapAdminUsername 【引导管理员用户名，为空则跳过创建】
     * @param bootstrapAdminPassword 【引导管理员密码】
     * @param bootstrapAdminNickname 【引导管理员昵称，可选】
     * @return 【CommandLineRunner 实例】
     */
    @Bean
    CommandLineRunner seed(UserService users, DataSource dataSource,
                            Environment env,
                            @Value("${soulous.jwt.secret}") String jwtSecret,
                            @Value("${soulous.bootstrap-admin.username:}") String bootstrapAdminUsername,
                            @Value("${soulous.bootstrap-admin.password:}") String bootstrapAdminPassword,
                            @Value("${soulous.bootstrap-admin.nickname:}") String bootstrapAdminNickname) {
        return args -> {
            // 【生产环境安全检查：禁止使用开发默认密钥】
            var isProd = Arrays.asList(env.getActiveProfiles()).contains("prod");
            if (isProd && DEV_JWT_SECRET.equals(jwtSecret)) {
                throw new IllegalStateException(
                        "Refusing to start in 'prod' profile with the development JWT secret. "
                                + "Set SOULOUS_JWT_SECRET to a strong value.");
            }
            // 【执行 H2 数据库遗留 Schema 迁移】
            migrateLegacyH2Schema(dataSource);
            // 【引导管理员账户创建】
            if (bootstrapAdminUsername != null && !bootstrapAdminUsername.isBlank()) {
                users.bootstrapAdmin(bootstrapAdminUsername.trim(), bootstrapAdminPassword,
                        bootstrapAdminNickname == null ? null : bootstrapAdminNickname.trim());
            }
        };
    }

    /**
     * 【H2 数据库遗留 Schema 迁移 —— 对旧版数据库执行增量 DDL 变更使其兼容新代码。
     * 仅在数据库类型为 H2 时执行，每条 ALTER 语句单独 try-catch 以保证幂等性
     * （已存在的列/表不会导致迁移失败）。
     *
     * <p>迁移内容包括：</p>
     * <ul>
     *   <li>pet.status 列扩展为 VARCHAR(32)</li>
     *   <li>task_submission 新增 admin_comment CLOB 列</li>
     *   <li>study_task 新增 archived_at TIMESTAMP 列</li>
     *   <li>user_account 移除旧 token 列，新增 token_version 列</li>
     *   <li>pet 新增 avatar_url 列</li>
     *   <li>重置所有表的自增 ID 序列以避免主键冲突</li>
     * </ul>】
     *
     * @param dataSource 【数据源，用于获取数据库连接】
     * @throws SQLException 【SQL 执行异常】
     */
    private void migrateLegacyH2Schema(DataSource dataSource) throws SQLException {
        try (var connection = dataSource.getConnection()) {
            var product = connection.getMetaData().getDatabaseProductName();
            if (!"H2".equalsIgnoreCase(product)) return;
            try (var s = connection.createStatement()) {
                s.execute("ALTER TABLE pet ALTER COLUMN status VARCHAR(32)");
            } catch (SQLException ignored) {}
            try (var s = connection.createStatement()) {
                s.execute("ALTER TABLE task_submission ADD COLUMN IF NOT EXISTS admin_comment CLOB");
            } catch (SQLException ignored) {}
            try (var s = connection.createStatement()) {
                s.execute("ALTER TABLE study_task ADD COLUMN IF NOT EXISTS archived_at TIMESTAMP");
            } catch (SQLException ignored) {}
            try (var s = connection.createStatement()) {
                s.execute("ALTER TABLE user_account DROP COLUMN IF EXISTS token");
            } catch (SQLException ignored) {}
            try (var s = connection.createStatement()) {
                s.execute("ALTER TABLE user_account ADD COLUMN IF NOT EXISTS token_version INT NOT NULL DEFAULT 0");
            } catch (SQLException ignored) {}
            try (var s = connection.createStatement()) {
                s.execute("ALTER TABLE pet ADD COLUMN IF NOT EXISTS avatar_url VARCHAR(255)");
            } catch (SQLException ignored) {}
            // 【重置各表的自增 ID 序列，避免与已有数据的主键冲突】
            resyncIdentity(connection, "pet");
            resyncIdentity(connection, "user_account");
            resyncIdentity(connection, "study_task");
            resyncIdentity(connection, "task_submission");
            resyncIdentity(connection, "exp_log");
        }
    }

    /**
     * 【重置指定表的 H2 自增 ID 序列 —— 查询表中当前最大 ID 值，
     * 将自增序列重置为 max(id) + 1，确保新插入的记录不会与已有数据主键冲突。
     * 操作失败时静默忽略（表可能不存在或无数据）。】
     *
     * @param connection 【数据库连接】
     * @param table      【需要重置自增序列的表名】
     */
    private void resyncIdentity(java.sql.Connection connection, String table) {
        try (var s = connection.createStatement();
             var rs = s.executeQuery("SELECT COALESCE(MAX(id), 0) FROM " + table)) {
            if (!rs.next()) return;
            long next = rs.getLong(1) + 1;
            try (var alter = connection.createStatement()) {
                alter.execute("ALTER TABLE " + table + " ALTER COLUMN id RESTART WITH " + next);
            }
        } catch (SQLException ignored) {}
    }
}
