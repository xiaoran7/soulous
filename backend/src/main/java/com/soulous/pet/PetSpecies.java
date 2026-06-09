package com.soulous.pet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 【宠物品种（市场目录）：宠物市场上架的可领养/购买品种。
 *  starter=true 的为入门款，新用户可免费领养其一；其余按 price 用金币购买。
 *  spritePath 指向前端 spritesheet 资源（当前多品种暂复用同一张占位图，后续可用 hatch 技能逐一生成）。】
 */
@Entity
@Table(name = "pet_species")
public class PetSpecies {
    /** 【主键】 */
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** 【唯一标识 slug，如 feixue】 */
    @Column(nullable = false, unique = true)
    public String slug;

    /** 【展示名】 */
    @Column(nullable = false)
    public String name;

    /** 【稀有度：COMMON / RARE / EPIC 等，仅展示用】 */
    public String rarity;

    /** 【价格（金币）。0 表示免费（通常为入门款）】 */
    @Column(nullable = false)
    public int price;

    /** 【是否入门款：新用户可免费领养其一】 */
    @Column(nullable = false)
    public boolean starter;

    /** 【前端 spritesheet 资源路径】 */
    public String spritePath;

    /** 【一句话简介】 */
    public String description;

    /** 【展示排序，越小越靠前】 */
    @Column(nullable = false)
    public int sortOrder;
}
