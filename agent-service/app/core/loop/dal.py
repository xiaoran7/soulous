import sqlite3
import json
import os
from typing import Dict, Any, Optional

class DataAccessLayer:
    """
    数据访问层 (DAL)，物理隔离图节点与底层 SQLite 数据库。
    重构：新增对中间推理链蒸馏结果 (reasoning_distillation) 的持久化支持。
    """
    def __init__(self, db_path: str = "agent_data.db"):
        self.db_path = db_path
        self._init_db()

    def _init_db(self):
        db_dir = os.path.dirname(os.path.abspath(self.db_path))
        if db_dir:
            os.makedirs(db_dir, exist_ok=True)
            
        with sqlite3.connect(self.db_path) as conn:
            cursor = conn.cursor()
            # 1. 用户长期画像表 (存放稳定事实)
            cursor.execute("""
                CREATE TABLE IF NOT EXISTS user_profiles (
                    user_id TEXT PRIMARY KEY,
                    profile_data TEXT,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
            """)
            # 2. 系统交互审计日志表 (新增推理蒸馏字段)
            cursor.execute("""
                CREATE TABLE IF NOT EXISTS interaction_logs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id TEXT,
                    query TEXT,
                    response TEXT,
                    reasoning_distillation TEXT,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
            """)
            conn.commit()

    def save_user_profile(self, user_id: str, profile: Dict[str, Any]):
        """
        保存或更新长期用户画像
        """
        with sqlite3.connect(self.db_path) as conn:
            cursor = conn.cursor()
            profile_json = json.dumps(profile, ensure_ascii=False)
            cursor.execute("""
                INSERT INTO user_profiles (user_id, profile_data, updated_at)
                VALUES (?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT(user_id) DO UPDATE SET
                    profile_data = excluded.profile_data,
                    updated_at = CURRENT_TIMESTAMP
            """, (user_id, profile_json))
            conn.commit()

    def get_user_profile(self, user_id: str) -> Dict[str, Any]:
        """
        获取用户长期画像
        """
        with sqlite3.connect(self.db_path) as conn:
            cursor = conn.cursor()
            cursor.execute("SELECT profile_data FROM user_profiles WHERE user_id = ?", (user_id,))
            row = cursor.fetchone()
            if row:
                return json.loads(row[0])
            return {}

    def log_interaction(self, session_id: str, query: str, response: str, reasoning_distillation: Optional[str] = None):
        """
        记录对话交互日志，包含蒸馏后的思维推理链。
        """
        with sqlite3.connect(self.db_path) as conn:
            cursor = conn.cursor()
            cursor.execute("""
                INSERT INTO interaction_logs (session_id, query, response, reasoning_distillation)
                VALUES (?, ?, ?, ?)
            """, (session_id, query, response, reasoning_distillation))
            conn.commit()
