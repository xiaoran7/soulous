package com.soulous;

import jakarta.persistence.Entity;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * 【Flyway 基线 DDL 一次性生成工具：扫描项目中所有 JPA @Entity 实体类，
 * 通过 Hibernate Schema 生成机制分别输出 H2 和 MySQL 两种方言的建表 SQL。
 * 仅在显式设置系统属性 soulous.schemaDump=true 时触发，不会在常规测试中运行。
 *
 * <p>One-shot generator for Flyway baseline DDL. Triggered explicitly via
 *   mvn test -Dtest=SchemaDumpTool -Dsoulous.schemaDump=true
 * Output: backend/src/main/resources/db/migration/{h2,mysql}/V1__baseline.sql</p>
 */
public class SchemaDumpTool {

    /**
     * 【测试方法：分别使用 H2 和 MySQL 方言生成基线 DDL 脚本，
     * 输出到 src/main/resources/db/migration/ 对应子目录下的 V1__baseline.sql。
     * 通过 @EnabledIfSystemProperty 限制仅在 soulous.schemaDump=true 时执行。】
     */
    @Test
    @EnabledIfSystemProperty(named = "soulous.schemaDump", matches = "true")
    void dumpBaseline() throws IOException, ClassNotFoundException {
        Path projectRoot = Path.of(System.getProperty("user.dir"));
        Path migrationRoot = projectRoot.resolve("src/main/resources/db/migration");
        dump("org.hibernate.dialect.H2Dialect", migrationRoot.resolve("h2/V1__baseline.sql"));
        dump("org.hibernate.dialect.MySQLDialect", migrationRoot.resolve("mysql/V1__baseline.sql"));
    }

    /**
     * 【核心导出方法：配置 Hibernate 的方言、命名策略、DDL 生成模式等参数，
     * 扫描所有 @Entity 实体并触发 buildSessionFactory() 以生成 SQL 脚本文件。
     * 使用完后在 finally 块中销毁 ServiceRegistry 释放资源。】
     */
    private static void dump(String dialect, Path target) throws IOException, ClassNotFoundException {
        Files.createDirectories(target.getParent());
        Files.deleteIfExists(target);

        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting(AvailableSettings.DIALECT, dialect)
                .applySetting(AvailableSettings.FORMAT_SQL, "true")
                .applySetting(AvailableSettings.HBM2DDL_AUTO, "none")
                .applySetting("hibernate.physical_naming_strategy",
                        "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
                .applySetting("hibernate.implicit_naming_strategy",
                        "org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy")
                .applySetting("jakarta.persistence.schema-generation.scripts.action", "create")
                .applySetting("jakarta.persistence.schema-generation.scripts.create-source", "metadata")
                .applySetting("jakarta.persistence.schema-generation.scripts.create-target",
                        target.toAbsolutePath().toString())
                .applySetting("jakarta.persistence.schema-generation.create-database-schemas", "false")
                .build();
        try {
            var sources = new MetadataSources(registry);
            for (Class<?> entity : findEntities()) {
                sources.addAnnotatedClass(entity);
            }
            // buildSessionFactory 会触发 schema-generation-scripts 执行，从而生成 SQL 文件
            // buildSessionFactory triggers schema-generation-scripts execution
            try (var sf = sources.buildMetadata().buildSessionFactory()) {
                // 整体为空 — 构建 SessionFactory 的副作用即为生成脚本文件
                // intentionally empty — side-effect of building is the script
            }
            System.out.println("Wrote " + target);
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    /**
     * 【扫描 com.soulous 包下所有标注了 @Entity 的类，
     * 返回这些实体类的集合供 MetadataSources 注册使用。】
     */
    private static Set<Class<?>> findEntities() throws ClassNotFoundException {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        Set<Class<?>> result = new HashSet<>();
        for (var bd : scanner.findCandidateComponents("com.soulous")) {
            result.add(Class.forName(bd.getBeanClassName()));
        }
        return result;
    }
}
