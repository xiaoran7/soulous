# Soulous 生产部署手册

适用于把当前仓库部署成对外可用的服务。开发态请看 `README` / memory；本文只覆盖生产。

## 拓扑

```
浏览器
   │ HTTPS (必须)
   ▼
反向代理 (nginx / Caddy / Traefik / Cloudflare Tunnel)
   │ ── /api/**, /uploads/**, /h2-console(不应暴露) → 后端 :8080
   └── /, /assets/**                              → 前端 dist/
```

前后端**必须同域**（或同主域不同子域）。当前 `csrf.disable()` + cookie 走 `SameSite=Lax`，跨站会被浏览器拦。

---

## 0. 准备

- JDK 21+
- Node 20+
- 一台支持 HTTPS 终止的反向代理（裸 HTTP 上线，cookie `secure=true` 拒发，登录立刻失败）
- 可选：MySQL 8、S3 兼容存储（R2 / OSS / MinIO）、LLM key (DeepSeek / OpenAI / Anthropic)

---

## 1. 必填环境变量（prod profile 缺一不可）

| 变量 | 说明 |
|---|---|
| `SOULOUS_JWT_SECRET` | ≥32 字节强随机串。`openssl rand -base64 48` 生成。**用默认 dev 串启动 prod 会主动抛错** |
| `SOULOUS_CORS_ORIGIN` | 前端实际域名，多个逗号分隔，如 `https://soulous.example.com` |

## 2. 强烈建议设的环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `SOULOUS_COOKIE_SECURE` | `true` (prod) | HTTPS 上线必须 true；本地 HTTP 调试时才设 false |
| `SOULOUS_BOOTSTRAP_ADMIN_USERNAME` | 空 | 首次启动指定 → 自动确保该账户为 ADMIN |
| `SOULOUS_BOOTSTRAP_ADMIN_PASSWORD` | 空 | 仅当 username 对应用户**不存在时**用作初始密码；已存在用户不会被改密 |
| `SOULOUS_BOOTSTRAP_ADMIN_NICKNAME` | 空 | 可选昵称 |
| `SOULOUS_CAPTCHA_ENABLED` | `true` | **登录**图形验证码开关（注册改用邮箱验证码，见 §3.5）；只在自动化测试或脚本批量操作时设 `false` |

> **Bootstrap 行为**：用户存在 → 仅升级角色为 ADMIN；用户不存在 → 用 password 创建（必须通过 PasswordPolicy：8-72 位、字母/数字/符号至少两类、不含用户名、不含空格）。密码不会被覆写，所以 env 长期挂着也不算后门——只是个角色升级开关。
>
> **不再播种 demo/admin 默认账户**。任何环境都不会自动种子，全部账户必须通过 bootstrap env 或注册流程创建。管理员登录后可在「审核管理 → 新建账号」面板里直接创建普通用户或更多管理员。

## 3. LLM（可选，不配会走 mock 规则版）

| 变量 | 示例 (DeepSeek) |
|---|---|
| `SOULOUS_LLM_PROVIDER` | `openai`（DeepSeek/Moonshot/通义都走 openai 兼容协议）/ `anthropic` / `mock` |
| `SOULOUS_LLM_API_KEY` | `sk-xxx` |
| `SOULOUS_LLM_BASE_URL` | `https://api.deepseek.com` (不带 `/v1`) |
| `SOULOUS_LLM_MODEL` | `deepseek-chat` 或 `deepseek-v4-pro`（reasoning 模型耗 token 更多） |
| `SOULOUS_LLM_TIMEOUT_SECONDS` | `30`（默认）| LLM 单次调用超时秒数；reasoning 模型建议设 `120` |

LLM 调用全部失败时自动回落到规则版，不会让接口报错。

## 3.5 邮件（注册验证码 + 每日提醒，可选）

注册改用**邮箱验证码**，每日提醒也走邮件。**不配 SMTP 时验证码/提醒只打到后端日志**（开发期够用，但生产注册体验需要真发邮件）。默认预置 Gmail（`smtp.gmail.com:587` STARTTLS）。

| 变量 | 默认 | 说明 |
|---|---|---|
| `SOULOUS_MAIL_HOST` | 空 | SMTP 主机，如 `smtp.gmail.com`。**留空 = 不发真实邮件，验证码进日志** |
| `SOULOUS_MAIL_PORT` | `587` | STARTTLS 端口 |
| `SOULOUS_MAIL_USERNAME` | 空 | 发信邮箱账号 |
| `SOULOUS_MAIL_PASSWORD` | 空 | **Gmail 用「应用专用密码」，不是账号密码** |
| `SOULOUS_NOTIFICATION_EMAIL_ENABLED` | `false` | 置 `true` 才真正把通知/提醒发邮件（需上面 SMTP 配好） |
| `SOULOUS_NOTIFICATION_EMAIL_FROM` | `no-reply@soulous.local` | 发件人地址 |
| `SOULOUS_EMAIL_CODE_ENABLED` | `true` | 注册邮箱验证码开关（关闭仅用于自动化测试） |
| `SOULOUS_REMINDER_ENABLED` / `_CRON` | `true` / `0 0 20 * * *` | 每日未打卡提醒开关与时间 |
| `SOULOUS_PET_DECAY_ENABLED` / `_INACTIVE_DAYS` / `_CRON` | `true` / `2` / `0 0 4 * * *` | 宠物断签衰减开关、断签天数阈值、定时 |

> 注：`mail` 健康探测已关闭（`management.health.mail.enabled=false`），否则配了 SMTP 后 `/actuator/health` 会因探测 SMTP 连通性而变 DOWN。

## 4. 对象存储（可选，默认本地）

默认存 `backend/uploads/`。多实例 / 容器化 / 想用 CDN → 切 S3 兼容：

```
SOULOUS_STORAGE_BACKEND=s3
SOULOUS_S3_ENDPOINT=https://<account>.r2.cloudflarestorage.com
SOULOUS_S3_REGION=auto
SOULOUS_S3_BUCKET=soulous-uploads
SOULOUS_S3_ACCESS_KEY=...
SOULOUS_S3_SECRET_KEY=...
SOULOUS_S3_PATH_STYLE=true   # R2/MinIO 需 true，AWS S3 可设 false
```

## 5. 数据库（可选，默认 H2 文件库）

单机轻量：默认 H2 文件库 (`backend/data/soulous.mv.db`) 可用，但**不抗压、不好备份**。

正式切 MySQL：

```
--spring.profiles.active=prod,mysql
```

并通过环境覆盖：

```
SPRING_DATASOURCE_URL=jdbc:mysql://db.host:3306/soulous?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
SPRING_DATASOURCE_USERNAME=soulous
SPRING_DATASOURCE_PASSWORD=...
```

**prod profile 下 `ddl-auto=validate`**（Flyway 管表结构）。新增实体字段必须同步写迁移文件 `db/migration/{vendor}/V{n}__*.sql`，否则 Hibernate 校验失败拒绝启动。已有库需手动 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`，详见 `docs/deployment.md`。

---

## 6. 构建 & 启动

### 后端

```powershell
cd backend
mvn -q -DskipTests package
java -jar target/soulous-backend-*.jar --spring.profiles.active=prod
```

或者 systemd / Docker 跑。务必带 `--spring.profiles.active=prod`，否则会启用 H2 console、devOrigins、demo+admin 弱口令账户。

### 前端

```powershell
cd frontend
npm ci
npm run build
# dist/ 用 nginx/Caddy 静态托管
```

构建时如果前端跟后端**不同源**，需要 build 期指定：

```powershell
$env:VITE_API_BASE="https://api.soulous.example.com"; npm run build
```

否则会走 `/api/**` 相对路径（推荐 — 由反代分流）。

### nginx 反代样例

```nginx
server {
  listen 443 ssl http2;
  server_name soulous.example.com;
  ssl_certificate /etc/letsencrypt/.../fullchain.pem;
  ssl_certificate_key /etc/letsencrypt/.../privkey.pem;

  client_max_body_size 6m;          # 后端上传上限 5MB，留点余量

  root /var/www/soulous/dist;
  index index.html;

  location /api/ {
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
  }

  location /uploads/ {
    proxy_pass http://127.0.0.1:8080;
  }

  # 千万别把 /h2-console 转发出去（prod profile 默认已关）

  location / {
    try_files $uri /index.html;
  }
}
```

---

## 7. 上线 checklist

- [ ] `SOULOUS_JWT_SECRET` 已设为强随机串
- [ ] `SOULOUS_CORS_ORIGIN` 已设为真实前端域名
- [ ] HTTPS 终止已配置，浏览器访问首页是 🔒
- [ ] 启动命令带 `--spring.profiles.active=prod`
- [ ] `/h2-console` 反代上不存在或被 deny
- [ ] 反代 `client_max_body_size` ≥ 5MB
- [ ] 已用 `SOULOUS_BOOTSTRAP_ADMIN_USERNAME/PASSWORD` 创建首个管理员，或手动 SQL 升级
- [ ] 首次登录后立即用 `/api/auth/password` 改一次管理员密码
- [ ] 数据库 / uploads 备份策略已就位（H2 备 `backend/data/*.mv.db`，本地存储备 `backend/uploads/`）
- [ ] 应用日志接到 stdout（容器/systemd journal）

---

## 7.1 验证码（登录图形码 / 注册邮箱码）

- **登录**：`GET /api/auth/captcha` → `{ id, image }`（base64 SVG，120s 有效，一次性）；登录体带 `captchaId` + `captchaCode`。前端 AuthScreen 自带刷新（点图换一张）。关闭仅限本地/CI：`SOULOUS_CAPTCHA_ENABLED=false`。
- **注册**：改用邮箱验证码——`POST /api/auth/email-code` 发 6 位码（10 分钟有效，60s 重发冷却），register 体带 `email` + `emailCode`（邮箱必填）。真发邮件需配 SMTP（见 §3.5），未配时码进后端日志。关闭仅限测试：`SOULOUS_EMAIL_CODE_ENABLED=false`。

## 8. 已知边界

- **双 token JWT**：access token 1h，refresh token 30d（HttpOnly cookie，自动 rotate，可吊销）。Refresh replay 检测：同一 refresh token 被使用两次立即吊销该用户全部会话。
- **应用层限流（Bucket4j）**：auth 端点（登录/注册/refresh）+ AI 端点已有独立限流桶；反代层仍建议加 `limit_req` 作双保险。
- **CSRF 已禁用**，依赖 SameSite=Lax + 同源前端。如果以后跨子域部署需要补 CSRF token。
- **`audit_log` 不会自动清理**，长期运行建议定期归档/分区。
- **改密 / logout-all 会自增 `tokenVersion`** 让旧 access token 立即失效；多设备登录场景下用户需重新登录所有设备。
- **prod `ddl-auto=validate`**：新增实体字段不会自动建列，必须写 Flyway 迁移文件，否则启动失败。
