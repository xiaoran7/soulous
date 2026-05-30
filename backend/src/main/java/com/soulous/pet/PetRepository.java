package com.soulous.pet;

import com.soulous.auth.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

/**
 * 【宠物数据仓库接口：继承 JpaRepository 提供 Pet 实体的 CRUD 操作。
 *  由于 Pet 与 UserAccount 是一对一关系，通过用户查找宠物是核心查询。】
 */
public interface PetRepository extends JpaRepository<Pet, Long> {
    /**
     * 【根据用户账户查找宠物，返回 Optional 以处理宠物不存在的情况。
     *  每个用户最多只有一个宠物，因此返回单个结果。】
     */
    Optional<Pet> findByUser(UserAccount user);
}
