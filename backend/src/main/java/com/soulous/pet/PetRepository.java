package com.soulous.pet;

import com.soulous.auth.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 【宠物数据仓库接口：一个用户可拥有多只宠物（owned_pet），其中 active=true 的为出战宠物。
 *  奖励/展示以出战宠物为准；市场拥有列表按获得时间排序。】
 */
public interface PetRepository extends JpaRepository<Pet, Long> {
    /** 【出战宠物（active=true）。每个用户最多一只 active】 */
    Optional<Pet> findByUserAndActiveTrue(UserAccount user);

    /** 【用户拥有的全部宠物，按获得时间升序】 */
    List<Pet> findByUserOrderByAcquiredAtAsc(UserAccount user);

    /** 【是否已拥有任意宠物（用于「免费领养首只」资格判断）】 */
    boolean existsByUser(UserAccount user);

    /** 【是否已拥有某品种（避免重复购买同款）】 */
    boolean existsByUserAndSpecies(UserAccount user, PetSpecies species);

    /** 【按 id + 用户查（确保只能操作自己的宠物）】 */
    Optional<Pet> findByIdAndUser(Long id, UserAccount user);
}
