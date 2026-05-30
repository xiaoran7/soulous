package com.soulous.pet;

import com.soulous.auth.UserService;
import com.soulous.common.web.BaseController;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 【宠物控制器：处理所有与宠物相关的 HTTP 请求，包括查看宠物状态、喂食、
 *  查看经验日志、设置头像、重命名等操作。
 *  继承 BaseController 获取当前用户认证能力。】
 */
@RestController
@RequestMapping("/api/pet")
class PetController extends BaseController {
    /** 【宠物业务逻辑服务，处理所有宠物相关的业务操作】 */
    private final PetService pets;

    /**
     * 【构造注入：通过 Spring 依赖注入用户服务和宠物服务。】
     */
    PetController(UserService users, PetService pets) {
        super(users);
        this.pets = pets;
    }

    /**
     * 【获取当前用户的宠物信息，包含等级、经验、心情、饱腹感、成长阶段等完整状态。】
     *
     * @param request 【HTTP 请求，用于从 token 中解析当前用户】
     * @return 【宠物实体的 JSON 表示】
     */
    @GetMapping
    Object pet(HttpServletRequest request) {
        return pets.get(current(request));
    }

    /**
     * 【喂食宠物：增加宠物饱腹感和心情，每次喂食饱腹感+20、心情+5，上限100。】
     *
     * @param request 【HTTP 请求，用于解析当前用户】
     * @return 【喂食后的宠物实体】
     */
    @PostMapping("/feed")
    Object feed(HttpServletRequest request) {
        return pets.feed(current(request));
    }

    /**
     * 【获取当前用户的宠物经验变动日志（最近20条），
     *  包括任务完成、专注完成、任务开始、驳回等事件。】
     *
     * @param request 【HTTP 请求，用于解析当前用户】
     * @return 【经验日志列表】
     */
    @GetMapping("/logs")
    Object logs(HttpServletRequest request) {
        return pets.logs(current(request));
    }

    /**
     * 【设置宠物头像 URL，传入 null 或空白字符串可清除头像。】
     *
     * @param request 【HTTP 请求，用于解析当前用户】
     * @param body 【包含 avatarUrl 的请求体】
     * @return 【更新后的宠物实体】
     */
    @PostMapping("/avatar")
    Object avatar(HttpServletRequest request, @RequestBody PetAvatarRequest body) {
        return pets.setAvatar(current(request), body == null ? null : body.avatarUrl());
    }

    /**
     * 【重命名宠物：如果名称为空或空白则恢复为用户名，名称最长32字符。】
     *
     * @param request 【HTTP 请求，用于解析当前用户】
     * @param body 【包含 name 字段的 Map】
     * @return 【更新后的宠物实体】
     */
    @PatchMapping
    Object rename(HttpServletRequest request, @RequestBody Map<String, String> body) {
        var name = body == null ? null : body.get("name");
        return pets.rename(current(request), name);
    }
}
