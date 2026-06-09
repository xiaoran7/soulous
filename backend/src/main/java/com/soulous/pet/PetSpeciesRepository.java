package com.soulous.pet;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 【宠物品种目录仓库】 */
public interface PetSpeciesRepository extends JpaRepository<PetSpecies, Long> {
    /** 【按展示顺序列出全部品种】 */
    List<PetSpecies> findAllByOrderBySortOrderAsc();

    /** 【按 slug 查品种】 */
    Optional<PetSpecies> findBySlug(String slug);
}
