package com.soulous.companion;

import com.soulous.auth.UserAccount;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 【陪伴宠物聊天服务。把 Soulous 用户映射到 Anima 的记忆空间，调 Anima 出回复。
 * 这是一个全新的产品表面（有记忆、会陪伴的宠物），与「拆学习计划」的 {@code ChatService} 互不相干。】
 *
 * <p>记忆隔离：user_id / session 都由登录用户的 id 派生，客户端无法传入，
 * 因此每个用户的记忆彼此隔离、互不可见。</p>
 */
@Service
public class CompanionService {
    private final AnimaClient anima;

    public CompanionService(AnimaClient anima) {
        this.anima = anima;
    }

    /** Anima 侧用户标识（命名空间隔离，便于 Anima 同时服务多个产品）。 */
    public static String petUserId(UserAccount user) {
        return "soulous:" + user.id;
    }

    /** 每个用户固定一条长期宠物会话，聊天与审核的记忆都累积在这条线上。 */
    public static String petSession(UserAccount user) {
        return "pet-" + user.id;
    }

    /** 跟宠物说一句话，拿回复。 */
    public String chat(UserAccount user, String message) {
        if (!anima.isEnabled()) {
            return "陪伴宠物暂时在休息啦，等会儿再来找我玩吧～";
        }
        return anima.run(petUserId(user), petSession(user), message)
                .orElse("呜…我刚走神了一下，能再跟我说一遍吗？");
    }

    /** 拉宠物会话历史（含审核留下的"提交+反馈"），聊天框打开时加载。失败返回空列表。 */
    public List<CompanionDtos.ChatMessage> history(UserAccount user) {
        if (!anima.isEnabled()) {
            return List.of();
        }
        return anima.sessionMessages(petSession(user), 20).map(node -> {
            List<CompanionDtos.ChatMessage> out = new ArrayList<>();
            node.forEach(m -> {
                var content = m.path("content").asText("");
                if (content.isBlank()) {
                    return;
                }
                var role = "assistant".equals(m.path("role").asText("")) ? "pet" : "user";
                out.add(new CompanionDtos.ChatMessage(role, content));
            });
            return out;
        }).orElseGet(List::of);
    }

    /** 宠物「记得你」的结构化记忆（画像事实），侧边栏展示用。失败/未开启返回空列表。 */
    public List<CompanionDtos.MemoryFact> memory(UserAccount user) {
        if (!anima.isEnabled()) {
            return List.of();
        }
        return anima.profileFacts(petUserId(user)).map(node -> {
            List<CompanionDtos.MemoryFact> out = new ArrayList<>();
            node.forEach(f -> {
                if (!"active".equals(f.path("status").asText("active"))) {
                    return; // 已衰减归档的不展示
                }
                var text = f.path("fact_text").asText("");
                if (text.isBlank()) {
                    return;
                }
                out.add(new CompanionDtos.MemoryFact(f.path("category").asText(""), text));
            });
            return out;
        }).orElseGet(List::of);
    }
}
