"""
输入输出流安全拦截器（Guardrails）。

实体化实现：回调 Soulous Spring Boot 的内容审核 fast-path（词表级，毫秒返回）。
LLM 级深度审核仍由 Java moderation 在对外入口完成，agent 不重复调用。

降级语义 fail-open：Spring 不可达或超时时放行并打 WARN——
入口处 Java 已做过一次完整审核，guardrail 是纵深防御的第二道闸，不能因网络抖动阻塞业务。
"""
import logging

import httpx

logger = logging.getLogger("Guardrail")


class Guardrail:
    base_url: str = ""
    token: str = ""

    @classmethod
    def configure(cls, base_url: str, token: str):
        cls.base_url = base_url.rstrip("/")
        cls.token = token

    @classmethod
    def _check(cls, text: str, direction: str) -> bool:
        if not cls.base_url or not text:
            return True
        try:
            resp = httpx.post(
                f"{cls.base_url}/internal/moderation/check",
                json={"text": text[:4000], "direction": direction},
                headers={"X-Service-Token": cls.token},
                timeout=2.0,
            )
            if resp.status_code == 200:
                return not resp.json().get("blocked", False)
            logger.warning("[GUARDRAIL] moderation 返回 %d，按放行处理。", resp.status_code)
            return True
        except Exception as e:
            logger.warning("[GUARDRAIL] moderation 不可达（%s），fail-open 放行。", e)
            return True

    @classmethod
    def check_input(cls, text: str) -> bool:
        """验证用户输入是否合规。返回 True 代表安全通过。"""
        return cls._check(text, "INPUT")

    @classmethod
    def check_output(cls, text: str) -> bool:
        """验证大模型生成内容是否合规。返回 True 代表安全通过。"""
        return cls._check(text, "OUTPUT")
