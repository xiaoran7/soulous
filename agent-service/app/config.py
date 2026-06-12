import os
from functools import lru_cache
from pathlib import Path

from dotenv import load_dotenv
from pydantic import BaseModel

load_dotenv()


class Settings(BaseModel):
    """
    agent-service 全局配置。全部来自环境变量（.env），一次加载全局复用。
    """
    host: str = os.getenv("AGENT_HOST", "127.0.0.1")
    port: int = int(os.getenv("AGENT_PORT", "8100"))
    service_token: str = os.getenv("AGENT_SERVICE_TOKEN", "")

    soulous_base_url: str = os.getenv("SOULOUS_BASE_URL", "http://127.0.0.1:8080")
    soulous_service_token: str = os.getenv("SOULOUS_SERVICE_TOKEN", "")

    data_dir: str = os.getenv("AGENT_DATA_DIR", "data")

    budget_system_tokens: int = int(os.getenv("BUDGET_SYSTEM_TOKENS", "2000"))
    budget_conversation_tokens: int = int(os.getenv("BUDGET_CONVERSATION_TOKENS", "2400"))
    budget_scratch_tokens: int = int(os.getenv("BUDGET_SCRATCH_TOKENS", "2000"))

    embedding_api_key: str = os.getenv("EMBEDDING_API_KEY", "")
    embedding_base_url: str = os.getenv("EMBEDDING_BASE_URL", "")
    embedding_model: str = os.getenv("EMBEDDING_MODEL", "text-embedding-v4")
    embedding_dimension: int = int(os.getenv("EMBEDDING_DIMENSION", "768"))

    @property
    def state_db(self) -> str:
        return str(Path(self.data_dir) / "agent_state.db")

    @property
    def dal_db(self) -> str:
        return str(Path(self.data_dir) / "agent_data.db")

    @property
    def rag_db(self) -> str:
        return str(Path(self.data_dir) / "agent_rag.db")


@lru_cache
def get_settings() -> Settings:
    settings = Settings()
    Path(settings.data_dir).mkdir(parents=True, exist_ok=True)
    return settings
