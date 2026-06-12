import os
from typing import Dict, Any, Type, Optional
from dotenv import load_dotenv
from langchain_core.language_models.chat_models import BaseChatModel
from langchain_openai import ChatOpenAI
from langchain_deepseek import ChatDeepSeek

# 加载环境变量
load_dotenv()

# 动态配置注册中心：隔离各模型供应商的底层元数据
# 如果有新的供应商接入，直接在此注册，免除修改业务代码
PROVIDER_MAPPINGS: Dict[str, Dict[str, Any]] = {
    "openai": {
        "class": ChatOpenAI,
        "env_prefix": "OPENAI",
        "default_model": "gpt-4o-mini",
        "default_base_url": None,
    },
    "mimo": {
        "class": ChatOpenAI,  # MIMO 为 OpenAI 兼容模式
        "env_prefix": "MIMO",
        "default_model": "mimo-v2.5-pro",
        "default_base_url": "https://token-plan-cn.xiaomimimo.com/v1"
    },
    "deepseek": {
        "class": ChatDeepSeek,
        "env_prefix": "DEEPSEEK",
        "default_model": "deepseek-v4-flash",
        "default_base_url": "https://api.deepseek.com"
    }
}

def get_llm(temperature: float = 0.7, provider: Optional[str] = None, model: Optional[str] = None, **kwargs: Any) -> BaseChatModel:
    """
    解耦的配置驱动型大语言模型工厂函数。
    支持按参数指定或从环境扫描动态切换提供商，并支持额外控制参数透传。
    
    参数:
        temperature: 模型温度 (默认 0.7 以保确定性)
        provider: 强制指定的供应商名称 ('openai', 'deepseek', 'mimo')
        model: 强制指定的模型名称
        kwargs: 透传给 LangChain 初始化构造函数的其他参数 (如 max_tokens, max_retries)
    """
    # 1. 动态确定当前使用的供应商
    # 优先级：调用传参 > 环境变量 ACTIVE_PROVIDER > 扫描可用 API_KEY -> 默认回退
    if not provider:
        active_env = os.getenv("ACTIVE_PROVIDER")
        if active_env and active_env.lower() == "mock":
            provider = "mock"
        elif active_env and active_env.lower() in PROVIDER_MAPPINGS:
            provider = active_env.lower()
        else:
            # 扫描并获取第一个配置了有效 API Key 的供应商
            for key, config in PROVIDER_MAPPINGS.items():
                prefix = config["env_prefix"]
                if os.getenv(f"{prefix}_API_KEY"):
                    provider = key
                    break
            # 若全部未配置，默认使用首个供应商
            if not provider:
                provider = "openai"

    # mock：确定性回放模型，零 key 跑全链路（pytest / 本地联调）
    if provider == "mock":
        from app.providers.mock_llm import MockChatModel
        return MockChatModel()

    if provider not in PROVIDER_MAPPINGS:
        raise ValueError(
            f"未知的 LLM 提供商 '{provider}'。当前支持的注册列表为: {list(PROVIDER_MAPPINGS.keys())}"
        )

    config = PROVIDER_MAPPINGS[provider]
    model_class = config["class"]
    prefix = config["env_prefix"]

    # 2. 动态映射供应商关联的环境变量
    api_key = os.getenv(f"{prefix}_API_KEY")
    base_url = os.getenv(f"{prefix}_BASE_URL") or os.getenv(f"{prefix}_API_BASE") or config["default_base_url"]
    model_name = model or os.getenv(f"{prefix}_MODEL") or config["default_model"]

    if not api_key:
        raise ValueError(
            f"未找到提供商 '{provider}' 的 API Key 配置。请在 .env 中正确配置 '{prefix}_API_KEY'。"
        )

    # 3. 构建构造参数，允许 kwargs 透传
    init_params = {
        "api_key": api_key,
        "temperature": temperature,
        **kwargs
    }

    # 针对不同模型库的 BaseURL 字段差异进行映射（ChatDeepSeek 使用 api_base）
    if issubclass(model_class, ChatDeepSeek):
        init_params["api_base"] = base_url
        init_params["model"] = model_name
    else:
        init_params["base_url"] = base_url
        init_params["model"] = model_name

    # 4. 实例化模型类
    return model_class(**init_params)
