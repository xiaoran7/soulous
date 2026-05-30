package com.soulous.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

/**
 * 【用户账户的 Spring Data JPA 仓库接口。
 *  继承 JpaRepository 获得基本的 CRUD 操作（save、findById、findAll、delete 等）。
 *  自定义查询方法遵循 Spring Data 的方法名派生查询约定，
 *  框架会根据方法名自动生成 SQL 语句，无需手写 @Query。】
 *
 * <p>Spring Data JPA repository for {@link UserAccount}.</p>
 */
public interface UserRepository extends JpaRepository<UserAccount, Long> {
    /**
     * 【根据用户名查找用户账户。
     *  用于登录验证、注册时检查用户名是否已存在等场景。
     *  用户名在数据库中有唯一约束，因此最多返回一条记录。】
     *
     * @param username 【要查询的用户名，不能为空】
     * @return 【包含匹配用户的 Optional，如果未找到则为 Optional.empty()】
     *
     * <p>Find a user account by username.</p>
     */
    Optional<UserAccount> findByUsername(String username);
}
