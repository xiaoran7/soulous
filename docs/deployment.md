# 部署说明

> **完整生产部署指南见根目录 [`DEPLOY.md`](../DEPLOY.md)**，包含环境变量表、nginx 配置、上线 checklist、数据库切换等。
>
> 本文补充当前实际运行的 VPS 环境信息，供快速参考。

---

## 当前 VPS 部署（2026-05-26）

| 项目 | 值 |
|---|---|
| 服务器 | `107.175.236.156`，SSH 端口 `54078` |
| 前端 | nginx 静态托管，监听 `0.0.0.0:80`，根目录 `/var/www/soulous` |
| 后端 | systemd `soulous.service`，Spring Boot `localhost:8080` |
| 数据库 | H2 文件库，`/opt/soulous/backend/data/soulous.mv.db` |
| 上传文件 | `/opt/soulous/backend/uploads/` |
| 环境变量文件 | `/opt/soulous/backend/soulous.env` |
| 日志 | `journalctl -u soulous -f` |

### 快速重启

```bash
systemctl restart soulous   # 后端
systemctl reload nginx       # 前端（静态文件直接替换，reload 即可）
```

### 本地 → VPS 更新流程

1. `npm run build`（frontend）
2. `mvn package -Dmaven.test.skip=true`（backend）
3. scp / sftp 上传 `frontend/dist/` → `/var/www/soulous/`
4. scp / sftp 上传 `backend/target/soulous-backend-0.1.0.jar` → `/opt/soulous/backend/`
5. `systemctl restart soulous`

### schema 补列（遇到 `missing column` 启动失败时）

prod 模式 `ddl-auto: validate`，新字段须手动补：

```bash
systemctl stop soulous
cd /opt/soulous/backend
java -cp soulous-backend-0.1.0.jar -Dloader.main=org.h2.tools.Shell \
  org.springframework.boot.loader.launch.PropertiesLauncher \
  -url 'jdbc:h2:file:./data/soulous;MODE=MySQL;DATABASE_TO_LOWER=TRUE' \
  -user sa -password '' \
  -sql 'ALTER TABLE <table> ADD COLUMN IF NOT EXISTS <col> <type>;'
systemctl start soulous
```

根本解：在 `db/migration/h2/V{n}__*.sql` 里写 `ADD COLUMN IF NOT EXISTS`，下次部署自动跑。
