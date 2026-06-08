# -*- coding: utf-8 -*-
"""
湖南工业大学 教务系统爬虫 (Playwright 版)
==========================

功能：
  1. 个人课表 (--mode schedule)  —— 默认
  2. 考试安排 (--mode exam)
  3. 课程成绩 (--mode grade)
  4. 学期信息 (--mode info)      —— 含开学日期推算

关键接口（抓包确认）：
  登录   : https://mycas.hut.edu.cn/cas/login  (CAS SPA)
  SSO    : http://jwxt.hut.edu.cn/jsxsd/sso.jsp
  课表   : GET /jsxsd/xskb/xskb_list.do?viweType=0&xnxq01id=<学期>&zc=<周次>
  考试   : GET /jsxsd/xsksap/xsksap_list.do?xnxq01id=<学期>
  成绩   : POST /jsxsd/kscj/cjcx_list.do  body: oper=&kch=&kcmc=&xnxq01id=<学期>
  周次   : GET /jsxsd/xskb/jxzlzc_xnxq_ajax?xnxq01id=<学期>
  当前周 : GET /jsxsd/framework/xsMainV.htmlx  (正文含"第N周")

Cookie 持久化：
  保存到 ~/.hut_session.json（含过期时间戳），下次优先复用，失效才重新 Playwright 登录。

依赖：
  pip install requests beautifulsoup4 playwright pycryptodome
  playwright install chromium

用法：
  python hut_schedule.py                         # 课表（默认）
  python hut_schedule.py --mode exam             # 考试安排
  python hut_schedule.py --mode grade            # 课程成绩
  python hut_schedule.py --mode info             # 学期信息（含开学日期）
  python hut_schedule.py --mode all              # 全部（课表+考试+成绩+学期信息）
  python hut_schedule.py --term 2025-2026-2 --out result.json
  python hut_schedule.py --relogin               # 强制重新登录，忽略缓存 Cookie
"""

# ============================================================================
# 0. 自动检测并安装依赖
# ============================================================================

def check_and_install_dependencies():
    """检测缺失的依赖包和 Playwright 浏览器内核，并自动下载安装。"""
    import subprocess
    import sys
    
    # 外部依赖包映射：import 名 -> pip 安装包名
    required_packages = {
        "requests": "requests",
        "bs4": "beautifulsoup4",
        "playwright": "playwright",
        "Crypto": "pycryptodome"
    }
    
    missing_packages = []
    for import_name, pkg_name in required_packages.items():
        try:
            __import__(import_name)
        except ImportError:
            missing_packages.append(pkg_name)
            
    if missing_packages:
        print(f"[*] 检测到缺少依赖包: {missing_packages}，正在自动下载安装...")
        try:
            # 确保使用当前 Python 解释器运行 pip
            subprocess.check_call([sys.executable, "-m", "pip", "install", *missing_packages])
            print("[+] 依赖包安装成功！")
        except Exception as e:
            print(f"[!] 依赖包自动安装失败: {e}，请手动运行 pip install {' '.join(missing_packages)}")
            sys.exit(1)
            
    # 检查 Playwright 浏览器内核 (Chromium) 是否存在
    try:
        from playwright.sync_api import sync_playwright
        with sync_playwright() as p:
            browser = p.chromium.launch(headless=True)
            browser.close()
    except Exception as e:
        err_msg = str(e)
        if "Executable doesn't exist" in err_msg or "playwright install" in err_msg or "not installed" in err_msg or "look like Playwright was" in err_msg or "not found" in err_msg.lower():
            print("[*] 检测到未安装 Playwright 浏览器内核，正在自动下载 (Chromium) ...")
            try:
                subprocess.check_call([sys.executable, "-m", "playwright", "install", "chromium"])
                print("[+] Playwright 浏览器内核安装成功！")
            except Exception as pe:
                print(f"[!] 自动安装 Playwright 浏览器内核失败: {pe}，请手动运行 python -m playwright install chromium")
                sys.exit(1)
        else:
            # 其它非内核缺失引发的异常，直接打印
            print(f"[*] 检查 Playwright 内核时遇到异常 (可能会在后续执行中报错): {e}")

# 服务端模式开关：后端通过环境变量 SOULOUS_SERVER_MODE=1 注入。
# 该模式下脚本在生产环境运行，需满足：依赖预装、强制无头、不写 scratch 调试文件、不持久化 cookie。
import os
SERVER_MODE = os.environ.get("SOULOUS_SERVER_MODE") == "1"

# 在导入第三方模块之前执行检测与安装。
# 服务端模式下跳过：依赖应在部署镜像里预装，绝不在请求期间联网 pip install / 下载 Chromium
# （否则会拖慢请求、吃满超时、并额外 fork 一堆子进程）。
if not SERVER_MODE:
    check_and_install_dependencies()

import re
import sys
import json
import time
import argparse
import math
from datetime import datetime, timedelta
from pathlib import Path

import requests
from bs4 import BeautifulSoup

# ----------------------------------------------------------------------------
# 配置区
# ----------------------------------------------------------------------------
USERNAME = ""           #学号（登录智慧工大的，手机号好像也行）必填
PASSWORD = ""           #密码（智慧工大密码）必填

def _get_default_term() -> str:
    today = datetime.now()
    year = today.year
    month = today.month
    if month >= 9 or month == 1:
        acad_year_start = year - 1 if month == 1 else year
        semester_num = 1
    else:
        acad_year_start = year - 1
        semester_num = 2
    return f"{acad_year_start}-{acad_year_start + 1}-{semester_num}"

TERM     = _get_default_term() #可以不用改，改的话格式类似"2025-2026-2"
WEEK     = ""           #可以不用改，输入账号密码会自己爬取，改的话格式类似"第13周"
KBJCMSID = ""           #节次时间模式 id，通常不需要改，留空走默认。部分特殊学期可能需要指定，如"2024-2025-1"的默认是模式1，但如果你发现节次时间不对，可以尝试改成"2"或"3"看看。

CAS_LOGIN   = "https://mycas.hut.edu.cn/cas/login"
SSO_ENTRY   = "http://jwxt.hut.edu.cn/jsxsd/sso.jsp"
JWXT_BASE   = "http://jwxt.hut.edu.cn/jsxsd"
KB_API      = JWXT_BASE + "/xskb/xskb_list.do"
EXAM_API    = JWXT_BASE + "/xsksap/xsksap_list.do"
GRADE_API   = JWXT_BASE + "/kscj/cjcx_list.do"
MAIN_PAGE   = JWXT_BASE + "/framework/xsMainV.htmlx"
WEEK_API    = JWXT_BASE + "/xskb/jxzlzc_xnxq_ajax"

# Cookie 缓存文件路径（~/.hut_session.json）
SESSION_FILE = Path.home() / ".hut_session.json"
# Cookie 有效期（秒）：4 小时，超过则重新登录
SESSION_TTL = 4 * 3600

WEEKDAYS = ["星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"]
SECTIONS = [
    {"name": "第一大节", "jc": "1-2",  "time": "08:00~09:40"},
    {"name": "第二大节", "jc": "3-4",  "time": "10:00~11:40"},
    {"name": "第三大节", "jc": "5-6",  "time": "14:00~15:40"},
    {"name": "第四大节", "jc": "7-8",  "time": "16:00~17:40"},
    {"name": "第五大节", "jc": "9-10", "time": "19:00~20:40"},
]

# 中文数字 -> 阿拉伯数字（用于解析"第十三周"）
_CN_NUM = {"一":1,"二":2,"三":3,"四":4,"五":5,"六":6,"七":7,"八":8,"九":9,"十":10,
           "十一":11,"十二":12,"十三":13,"十四":14,"十五":15,"十六":16,"十七":17,"十八":18}


# ============================================================================
# 一、Cookie 持久化
# ============================================================================

def save_cookies(cookies: dict):
    """把 cookie 字典 + 时间戳写到本地缓存文件。
    服务端模式下不落盘：多用户共用一个 home，持久化 cookie 既有跨用户串号风险，
    并发写还会互相覆盖；服务端已强制 --relogin，本就不复用缓存。"""
    if SERVER_MODE:
        return
    data = {"ts": time.time(), "cookies": cookies}
    with open(SESSION_FILE, "w", encoding="utf-8") as f:
        json.dump(data, f)


def load_cookies() -> dict | None:
    """读取缓存 Cookie；未过期返回 dict，否则返回 None。"""
    if not SESSION_FILE.exists():
        return None
    try:
        with open(SESSION_FILE, encoding="utf-8") as f:
            data = json.load(f)
        if time.time() - data.get("ts", 0) < SESSION_TTL:
            return data["cookies"]
    except Exception:
        pass
    return None


def validate_cookies(cookies: dict) -> bool:
    """用轻量请求验证 Cookie 是否仍有效（返回主页不是登录页则有效）。"""
    try:
        sess = _make_session(cookies)
        r = sess.get(MAIN_PAGE, timeout=8, allow_redirects=True)
        r.encoding = r.apparent_encoding or "utf-8"
        # 避免将返回的登录页面误判为有效主页
        if "欢迎登录" in r.text or "请先登录" in r.text or "RANDOMCODE" in r.text:
            return False
        return "xsMainV" in r.url and ("第" in r.text or "退出" in r.text or "xsMain" in r.text or "首页" in r.text)
    except Exception:
        return False


# ============================================================================
# 二、Playwright 登录
# ============================================================================

def login_and_get_cookies(username, password, headless=False, wait_login=60):
    """驱动 Chromium 完成 CAS 登录，返回 Cookie 字典。"""
    from playwright.sync_api import sync_playwright

    with sync_playwright() as p:
        browser = p.chromium.launch(
            headless=headless,
            args=["--disable-blink-features=AutomationControlled"]
        )
        context = browser.new_context(viewport={"width": 1280, "height": 800})
        page = context.new_page()

        try:
            print("[*] 打开统一身份认证登录页 ...")
            page.goto(CAS_LOGIN)

            try:
                page.wait_for_selector("input[type='password']:not([type='hidden'])", timeout=20000)
                
                # 找可见账号框
                user_input = None
                for sel in [
                    "input[type='text']:not([type='hidden'])",
                    "input[type='tel']",
                    "input[autocomplete='username']",
                    "input[placeholder*='账号']",
                    "input[placeholder*='学号']",
                ]:
                    loc = page.locator(sel)
                    count = loc.count()
                    for i in range(count):
                        el = loc.nth(i)
                        if el.is_visible():
                            user_input = el
                            break
                    if user_input:
                        break
                
                pwd_input = None
                pwd_loc = page.locator("input[type='password']")
                for i in range(pwd_loc.count()):
                    el = pwd_loc.nth(i)
                    if el.is_visible():
                        pwd_input = el
                        break

                if user_input and pwd_input:
                    user_input.fill(username)
                    page.wait_for_timeout(300)
                    pwd_input.fill(password)
                    page.wait_for_timeout(500)
                    pwd_input.press("Enter")
                    print("[*] 已填入账号密码并按回车提交")
            except Exception as e:
                print("[!] 自动填写未完成，请在弹出的浏览器中手动登录：", e)

            print("[*] 等待登录成功（最多 %d 秒）..." % wait_login)
            deadline = time.time() + wait_login
            logged_in = False
            while time.time() < deadline:
                url = page.url
                cookies_list = context.cookies()
                names = {c["name"] for c in cookies_list}
                src = page.content()
                if "CASTGC" in names or "TGC" in names or "/cas/login" not in url or "登录成功" in src:
                    logged_in = True
                    break
                page.wait_for_timeout(1500)
            if not logged_in:
                raise TimeoutError("登录超时（%d秒），请检查账号密码或手动完成验证。" % wait_login)

            # 单点登录进教务系统
            print("[*] 通过 sso.jsp 单点登录进入教务系统 ...")
            page.goto(SSO_ENTRY)
            try:
                page.wait_for_url(re.compile(r"jsxsd|jwxt"), timeout=20000)
            except Exception:
                if "jsxsd" not in page.url and "jwxt" not in page.url:
                    raise
            
            page.goto(KB_API)
            page.wait_for_timeout(1500)

            cookies_list = context.cookies()
            cookies = {c["name"]: c["value"] for c in cookies_list}
            print("[+] 登录成功，已获取 %d 个 Cookie。" % len(cookies))
            save_cookies(cookies)
            return cookies
        finally:
            browser.close()


# ============================================================================
# 三、会话管理与直连登录
# ============================================================================

def login_by_requests(username, password) -> dict:
    """尝试通过 requests 直接登录 (CAS + SSO)，成功则返回 Cookie 字典。"""
    import base64
    from Crypto.PublicKey import RSA
    from Crypto.Cipher import PKCS1_v1_5
    
    s = requests.Session()
    s.headers.update({
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
    })
    
    # 1. 请求 CAS 登录页，建立 Session，获取 execution token
    url = "https://mycas.hut.edu.cn/cas/login?service=http%3A%2F%2Fjwxt.hut.edu.cn%2Fjsxsd%2Fsso.jsp"
    r1 = s.get(url, timeout=15)
    r1.raise_for_status()
    
    soup1 = BeautifulSoup(r1.text, "html.parser")
    exec_el = soup1.find("input", {"name": "execution"})
    if not exec_el:
        raise ValueError("未能解析到 CAS 登录 execution 参数")
    execution = exec_el.get("value")
    
    # 2. 获取 RSA 加密公钥
    r_pk = s.get("https://mycas.hut.edu.cn/cas/jwt/publicKey", timeout=10)
    r_pk.raise_for_status()
    pk_pem = r_pk.text
    
    # 3. 对密码进行 RSA 加密
    key = RSA.importKey(pk_pem)
    cipher = PKCS1_v1_5.new(key)
    encrypted_bytes = cipher.encrypt(password.encode("utf-8"))
    enc_pwd = "__RSA__" + base64.b64encode(encrypted_bytes).decode("utf-8")
    
    # 4. POST 登录请求
    data = {
        "username": username,
        "password": enc_pwd,
        "mfaState": "",
        "failN": "0",
        "execution": execution,
        "_eventId": "submit",
        "geolocation": "",
        "fpVisitorId": "",
        "submit": "Login1"
    }
    
    headers = {
        "Referer": url,
        "Origin": "https://mycas.hut.edu.cn"
    }
    r2 = s.post(url, data=data, headers=headers, timeout=20)
    r2.raise_for_status()
    
    # 5. 验证是否成功登录教务系统 (通过轻量请求验证)
    r_main = s.get(MAIN_PAGE, timeout=15)
    r_main.encoding = r_main.apparent_encoding or "utf-8"
    
    if "欢迎登录" in r_main.text or "请先登录" in r_main.text or "RANDOMCODE" in r_main.text:
        raise RuntimeError("密码错误或登录失败（返回了登录页面）")
        
    if "xsMainV" in r_main.url or "第" in r_main.text or "xsMain" in r_main.text or "首页" in r_main.text:
        cookies_dict = s.cookies.get_dict()
        if cookies_dict:
            return cookies_dict
            
    raise RuntimeError("密码错误或登录失败（未包含教务系统主页特征）")


def get_valid_cookies(username, password, headless=False, relogin=False):
    """返回有效 Cookie 字典；优先读缓存，失效则重新登录。"""
    if not relogin:
        cached = load_cookies()
        if cached:
            print("[*] 发现缓存 Cookie，验证有效性 ...")
            if validate_cookies(cached):
                print("[+] 缓存 Cookie 有效，跳过登录。")
                return cached
            else:
                print("[!] 缓存 Cookie 已失效，重新登录。")

    # 优先使用 requests 登录
    try:
        print("[*] 尝试通过 requests 直接登录教务系统 ...")
        cookies = login_by_requests(username, password)
        print("[+] requests 登录成功！")
        save_cookies(cookies)
        return cookies
    except Exception as e:
        print(f"[!] requests 登录失败: {e}。将使用 Playwright 兜底登录 ...")

    return login_and_get_cookies(username, password, headless=headless)



class WafSession(requests.Session):
    """自定义 Session 类，自动拦截并重试 WAF 浏览器 Cookie 验证页。"""
    def request(self, method, url, *args, **kwargs):
        max_retries = 3
        for i in range(max_retries):
            r = super().request(method, url, *args, **kwargs)
            r.encoding = r.apparent_encoding or "utf-8"
            if "location.replace(location.href.split" in r.text or "flashcookie.swf" in r.text:
                print(f"[*] 检测到 WAF 验证防护，正在尝试第 {i+1} 次自动绕过重试: {url}")
                time.sleep(1.2)
                continue
            return r
        return r

def _make_session(cookies: dict) -> requests.Session:
    """构造带 Cookie 的 requests Session。"""
    sess = WafSession()
    sess.headers.update({
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                      "(KHTML, like Gecko) Chrome/124.0 Safari/537.36",
        "Referer": JWXT_BASE + "/",
    })
    for k, v in cookies.items():
        sess.cookies.set(k, v, domain="jwxt.hut.edu.cn")
    return sess


# ============================================================================
# 四、开学日期推算
# ============================================================================

def fetch_semester_info(cookies: dict, term: str) -> dict:
    """
    推算开学日期（第1周周一）。

    原理：
      1. 从主页正文提取当前周次（如"第13周"）
      2. 算出本周周一日期
      3. 开学日期 = 本周周一 - (当前周次-1)*7天
    同时返回总周次范围（qszc~jszc）。
    """
    sess = _make_session(cookies)

    # 拿当前周次（主页正文含"第N周"）
    r = sess.get(MAIN_PAGE, timeout=10)
    r.encoding = r.apparent_encoding or "utf-8"
    m = re.search(r"第([一二三四五六七八九十]+|\d+)周", r.text)
    current_week = None
    if m:
        raw = m.group(1)
        current_week = _CN_NUM.get(raw) or (int(raw) if raw.isdigit() else None)

    # 拿起止周
    r2 = sess.get(WEEK_API, params={"xnxq01id": term}, timeout=8)
    r2.encoding = "utf-8"
    try:
        wdata = r2.json()
        start_wk = wdata[0].get("qszc", 1) if wdata else 1
        end_wk   = wdata[0].get("jszc", 18) if wdata else 18
    except Exception:
        start_wk, end_wk = 1, 18

    # 推算开学日期
    semester_start = None
    if current_week:
        today = datetime.now().date()
        # 本周周一 = 今天 - weekday 偏移（weekday(): 周一=0, 周日=6）
        this_monday = today - timedelta(days=today.weekday())
        semester_start = this_monday - timedelta(weeks=current_week - 1)

    return {
        "学期": term,
        "当前周次": current_week,
        "起始周": start_wk,
        "结束周": end_wk,
        "总周数": end_wk - start_wk + 1,
        "开学日期": semester_start.strftime("%Y-%m-%d") if semester_start else None,
        "推算方法": "本周周一(%s) - (第%s周-1)*7天" % (
            (datetime.now().date() - timedelta(days=datetime.now().date().weekday())).strftime("%Y-%m-%d"),
            current_week
        ) if current_week else "未能获取当前周次",
    }


# ============================================================================
# 五、课表抓取与解析
# ============================================================================

def fetch_schedule_html(cookies, term=TERM, week=WEEK, kbjcmsid=KBJCMSID):
    sess = _make_session(cookies)
    params = {"viweType": "0", "xnxq01id": term, "zc": week}
    if kbjcmsid:
        params["kbjcmsid"] = kbjcmsid
    resp = sess.get(KB_API, params=params, timeout=20)
    resp.encoding = resp.apparent_encoding or "utf-8"
    if "courselists" not in resp.text and "qz-weeklyTable" not in resp.text:
        raise RuntimeError("未获取到课表数据，可能会话已失效。返回片段：\n" + resp.text[:300])
    return resp.text


_LABELS = ["老师", "时间", "地点", "课程编号", "班级", "总人数", "考核方式", "总学时", "分组名", "网课群号"]
_NEXT   = r"(?=(?:" + "|".join(_LABELS) + r")[:：]|[;；]|$)"


def _field(text, label):
    m = re.search(label + r"[:：](.*?)" + _NEXT, text)
    return m.group(1).strip() if m else ""


def _parse_detail(text):
    text = re.sub(r"\s+", "", text)
    teacher  = _field(text, "老师")
    time_str = _field(text, "时间")
    weeks = (re.search(r"([\d,\-]+)周", time_str) or [None, ""])[1] if time_str else ""
    jc    = (re.search(r"\[([\d,\-]+)节\]", time_str) or [None, ""])[1] if time_str else ""
    place = _field(text, "地点")
    return {
        "教师": teacher, "周次": weeks, "节次": jc, "地点": place,
        "课程编号": _field(text, "课程编号"),
        "班级":     _field(text, "班级"),
        "总人数":   _field(text, "总人数"),
        "考核方式": _field(text, "考核方式"),
        "总学时":   _field(text, "总学时"),
    }


def parse_schedule(html):
    soup  = BeautifulSoup(html, "html.parser")
    table = soup.select_one("table.qz-weeklyTable")
    courses, remarks = [], []
    if table:
        section_idx = 0
        for tr in table.find_all("tr"):
            if "大节" not in tr.get_text(strip=True):
                continue
            tds = tr.find_all("td")
            sec = SECTIONS[section_idx] if section_idx < len(SECTIONS) else {"name": "", "jc": "", "time": ""}
            for col, td in enumerate(tds[1:8]):
                for li in td.select("li.courselists-item"):
                    title_el  = li.select_one(".qz-hasCourse-title")
                    detail_el = li.select_one("p.qz-hasCourse-detaillists")
                    name = title_el.get_text(strip=True) if title_el else ""
                    if not name:
                        continue
                    d = _parse_detail(detail_el.get_text(" ", strip=True) if detail_el else "")
                    courses.append({
                        "课程名":  name,
                        "星期":    WEEKDAYS[col] if col < 7 else "?",
                        "星期序号": col + 1,
                        "大节":    sec["name"],
                        "节次":    d["节次"] or sec["jc"],
                        "上课时间": sec["time"],
                        "周次":    d["周次"],
                        "教师":    d["教师"],
                        "地点":    d["地点"],
                        "班级":    d["班级"],
                        "课程编号": d["课程编号"],
                        "考核方式": d["考核方式"],
                        "总学时":  d["总学时"],
                        "总人数":  d["总人数"],
                    })
            section_idx += 1

        for tr in table.find_all("tr"):
            if tr.get_text(strip=True).startswith("备注"):
                tds = tr.find_all("td")
                if len(tds) >= 2:
                    for seg in re.split(r"[;；]", tds[1].get_text(" ", strip=True)):
                        if seg.strip():
                            remarks.append(seg.strip())
    return courses, remarks


def _fetch_all_pages(sess, url: str, base_payload: dict, method: str = "GET") -> list:
    """
    通用分页拉取所有数据。
    """
    page_size = 20
    payload = base_payload.copy()
    payload["pageNum"] = "1"
    payload["pageSize"] = str(page_size)
    
    all_items = []
    
    try:
        print(f"[*] 正在请求第 1 页: {url} 参数: {payload}")
        if method == "GET":
            r = sess.get(url, params=payload, timeout=20)
        else:
            r = sess.post(url, data=payload, timeout=20)
            
        r.encoding = r.apparent_encoding or "utf-8"
        
        # 调试保存第一页（服务端模式禁用：成绩/考试属个人隐私，不明文落盘，且并发同名文件会互相覆盖）
        if not SERVER_MODE:
            try:
                name_prefix = "exam" if "xsksap_list" in url else "grade"
                os.makedirs("scratch", exist_ok=True)
                with open(f"scratch/debug_{name_prefix}_{payload.get('xnxqid') or payload.get('kksj')}_page1.html", "w", encoding="utf-8") as f:
                    f.write(r.text)
            except Exception:
                pass

        js = r.json()
        if js.get("code") == 0 and isinstance(js.get("data"), list):
            data_list = js["data"]
            all_items.extend(data_list)
            total_count = js.get("count", 0)
            try:
                total_count = int(total_count)
            except Exception:
                total_count = len(data_list)
                
            print(f"[+] 第 1 页获取成功，返回 {len(data_list)} 条，总共 {total_count} 条记录")
            
            if total_count > page_size:
                total_pages = math.ceil(total_count / page_size)
                print(f"[*] 检测到多页数据，共 {total_pages} 页，开始拉取后续页面...")
                for page in range(2, total_pages + 1):
                    page_payload = payload.copy()
                    page_payload["pageNum"] = str(page)
                    print(f"[*] 正在请求第 {page}/{total_pages} 页: {url} 参数: {page_payload}")
                    if method == "GET":
                        r_page = sess.get(url, params=page_payload, timeout=20)
                    else:
                        r_page = sess.post(url, data=page_payload, timeout=20)
                    r_page.encoding = r_page.apparent_encoding or "utf-8"
                    js_page = r_page.json()
                    if js_page.get("code") == 0 and isinstance(js_page.get("data"), list):
                        all_items.extend(js_page["data"])
                        print(f"[+] 第 {page} 页获取成功，当前追加 {len(js_page['data'])} 条记录")
                    else:
                        print(f"[!] 第 {page} 页获取失败: {js_page}")
        else:
            print(f"[!] 接口响应格式异常，未找到 code=0 或 data 列表。响应: {js}")
    except Exception as e:
        print(f"[!] 分页获取失败: {e}")
        
    return all_items


def fetch_server_terms(cookies: dict) -> tuple:
    """
    访问课表页面，从下拉框中解析当前活动学期和所有可选学期。
    返回: (当前学期, [所有学期列表])
    """
    sess = _make_session(cookies)
    try:
        r = sess.get(KB_API, params={"viweType": "0"}, timeout=20)
        r.encoding = r.apparent_encoding or "utf-8"
        soup = BeautifulSoup(r.text, "html.parser")
        select_el = soup.find("select", id="xnxq01id")
        if select_el:
            options = select_el.find_all("option")
            all_terms = []
            current_term = None
            for opt in options:
                val = opt.get("value", "").strip()
                if val:
                    all_terms.append(val)
                    if opt.has_attr("selected") or "selected" in opt.attrs:
                        current_term = val
            
            if not current_term and all_terms:
                current_term = all_terms[0]
            
            if current_term:
                return current_term, all_terms
    except Exception as e:
        print(f"[!] 从教务系统解析学期列表失败: {e}")
    
    # 兜底计算
    today = datetime.now()
    year = today.year
    month = today.month
    if month >= 9 or month == 1:
        acad_year_start = year - 1 if month == 1 else year
        semester_num = 1
    else:
        acad_year_start = year - 1
        semester_num = 2
    fallback_term = f"{acad_year_start}-{acad_year_start + 1}-{semester_num}"
    return fallback_term, [fallback_term]


# ============================================================================
# 六、考试安排抓取与解析
# ============================================================================

def fetch_exams(cookies: dict, term: str) -> list:
    """
    接口: GET /jsxsd/xsks/xsksap_list
    """
    sess = _make_session(cookies)
    
    # 1. 访问查询页面以建立会话参数上下文
    print("[*] 正在请求考试安排表单上下文...")
    query_frm_url = JWXT_BASE + "/xsks/xsksap_query"
    try:
        sess.get(query_frm_url, timeout=15)
    except Exception as e:
        print(f"[!] 获取考试表单上下文失败: {e}")

    url = JWXT_BASE + "/xsks/xsksap_list"
    payload = {
        "xnxqid": term,
        "xqlb": ""
    }
    
    print(f"[*] 正在请求考试安排 JSON 接口 (通过分页 helper): {url} 参数: {payload}")
    exams = []
    try:
        raw_data = _fetch_all_pages(sess, url, payload, method="GET")
        
        # 将 JSON 键映射为前端显示所需的标准中文表头名
        field_map = {
            "xqmc": "校区",
            "ksxq": "考试校区",
            "kssj": "考试时间",
            "js_mc": "考场",
            "kch": "课程编号",
            "kskcmc": "课程名称",
            "jsxm": "授课教师",
            "zwh": "座位号",
            "zkzh": "准考证号",
            "ksccmc": "考试场次",
            "bzywmc": "备注"
        }
        
        for item in raw_data:
            row = {}
            for json_key, ch_name in field_map.items():
                val = item.get(json_key)
                row[ch_name] = str(val).strip() if val is not None else ""
            exams.append(row)
        print(f"[+] 考试安排拉取成功，共获得 {len(exams)} 条考试安排记录。")
    except Exception as e:
        print(f"[!] 解析考试安排接口响应失败: {e}")
            
    return exams


# ============================================================================
# 七、成绩查询抓取与解析
# ============================================================================

def fetch_grades(cookies: dict, term: str) -> list:
    """
    接口: GET /jsxsd/kscj/cjcx_list
    """
    sess = _make_session(cookies)
    
    # 1. 访问成绩查询页面，设定会话上下文，以便能拉取到成绩
    print("[*] 正在请求课程成绩表单上下文...")
    frm_url = JWXT_BASE + "/kscj/cjcx_frm"
    try:
        sess.get(frm_url, timeout=15)
    except Exception as e:
        print(f"[!] 获取课程成绩表单上下文失败: {e}")

    # 2. 调用 Layui table 所需 Jun 分页接口
    url = JWXT_BASE + "/kscj/cjcx_list"
    query_term = "" if term in ("auto", "") else term
    payload = {
        "kksj": query_term,
        "kcxz": "",
        "kcsx": "",
        "kcmc": "",
        "xsfs": "all",
        "sfxsbcxq": "1"
    }
    
    print(f"[*] 正在请求课程成绩 API 接口 (通过分页 helper): {url} 参数: {payload}")
    grades = []
    try:
        raw_data = _fetch_all_pages(sess, url, payload, method="GET")
        
        # 将 JSON 键映射为前端显示所需的标准中文表头名
        field_map = {
            "xnxqid": "开课学期",
            "kch": "课程编号",
            "kc_mc": "课程名称",
            "ksdw": "开课单位",
            "zcjstr": "成绩",
            "cjbs": "成绩标识",
            "xf": "学分",
            "zxs": "总学时",
            "jd": "绩点",
            "ksfs": "考核方式",
            "ksxz": "考试性质",
            "kcsx": "课程属性",
            "kcxzmc": "课程性质",
            "bcxq": "补重学期",
            "txklb": "通选课类别"
        }
        
        for item in raw_data:
            row = {}
            for json_key, ch_name in field_map.items():
                val = item.get(json_key)
                row[ch_name] = str(val).strip() if val is not None else ""
            grades.append(row)
        print(f"[+] 成绩拉取成功，共获得 {len(grades)} 门课程成绩记录。")
    except Exception as e:
        print(f"[!] 解析成绩接口响应失败: {e}")
        
    return grades


def dump_menu_links(cookies: dict):
    """抓取教务系统的左侧导航菜单，提取所有链接并写入 scratch/menu_links.txt。"""
    try:
        sess = _make_session(cookies)
        # 尝试抓取左侧菜单页
        left_url = JWXT_BASE + "/framework/xsMain_left.htmlx"
        r = sess.get(left_url, timeout=10)
        r.encoding = r.apparent_encoding or "utf-8"
        
        soup = BeautifulSoup(r.text, "html.parser")
        links = []
        for a in soup.find_all("a"):
            href = a.get("href", "").strip()
            text = a.get_text(strip=True)
            if href and text:
                links.append(f"{text}: {href}")
                
        # 如果左侧菜单没有链接，尝试抓取主页
        if not links:
            main_url = JWXT_BASE + "/framework/xsMainV.htmlx"
            r_main = sess.get(main_url, timeout=10)
            r_main.encoding = r_main.apparent_encoding or "utf-8"
            soup_main = BeautifulSoup(r_main.text, "html.parser")
            for frame in soup_main.find_all("frame"):
                src = frame.get("src", "").strip()
                name = frame.get("name", "").strip()
                if src:
                    links.append(f"Frame {name}: {src}")
                    
        os.makedirs("scratch", exist_ok=True)
        with open("scratch/menu_links.txt", "w", encoding="utf-8") as f:
            f.write("\n".join(links))
        print("[+] 已成功导出导航菜单链接至 scratch/menu_links.txt")
    except Exception as e:
        print(f"[!] 导出导航菜单失败: {e}")


# ============================================================================
# 八、主入口
# ============================================================================

def main():
    ap = argparse.ArgumentParser(description="湖南工业大学教务爬虫 (Playwright 版)")
    ap.add_argument("--user",    default=USERNAME, help="学号")
    ap.add_argument("--pwd",     default=PASSWORD, help="统一身份认证密码（明文，仅本地手动调试用）")
    ap.add_argument("--pwd-stdin", action="store_true",
                    help="从标准输入读取密码（服务端安全模式，避免命令行/进程列表/日志泄露明文）")
    ap.add_argument("--term",    default=TERM,     help="学年学期，如 2025-2026-2")
    ap.add_argument("--week",    default=WEEK,     help="周次，留空=整学期")
    ap.add_argument("--kbjcmsid",default=KBJCMSID, help="节次时间模式 id，留空走默认")
    ap.add_argument("--headless",action="store_true", help="无头模式")
    ap.add_argument("--relogin", action="store_true", help="强制重新登录，忽略缓存 Cookie")
    ap.add_argument("--mode",    default="all",
                    choices=["schedule", "exam", "grade", "info", "all"],
                    help="抓取模式：schedule(课表) / exam(考试) / grade(成绩) / info(学期信息) / all")
    ap.add_argument("--out",     default="", help="结果输出 JSON 文件；留空只打印不存盘")
    args = ap.parse_args()

    # ---- 密码来源：服务端走 stdin（不落进程列表/日志），本地调试可用 --pwd ----
    password = args.pwd
    if args.pwd_stdin:
        password = sys.stdin.readline().rstrip("\r\n")

    # ---- 获取有效 Cookie ----
    # 服务端通常无显示器，Playwright 兜底登录必须无头，否则启动带界面 Chromium 会直接失败。
    headless = args.headless or SERVER_MODE
    cookies = get_valid_cookies(args.user, password,
                                headless=headless, relogin=args.relogin)

    # 自动从教务系统服务器获取最新的真实学期和所有学期
    server_current_term, server_all_terms = fetch_server_terms(cookies)
    print(f"[+] 教务系统当前学期: {server_current_term}, 可选学期: {server_all_terms}")
    
    # 如果命令行参数没有指定 --term，或者指定的 --term 为 auto，使用教务系统的当前学期
    target_term = args.term
    if not target_term or target_term == "auto":
        target_term = server_current_term

    # 导出菜单链接以供调试（服务端模式禁用：纯调试产物，且会向 CWD 落 scratch 文件）
    if not SERVER_MODE:
        dump_menu_links(cookies)

    result = {}

    # ---- 学期信息 / 开学日期 ----
    if args.mode in ("info", "all"):
        info = fetch_semester_info(cookies, target_term)
        result["学期信息"] = info
        print("\n========== 学期信息 ==========")
        for k, v in info.items():
            print("  %s: %s" % (k, v))

    # ---- 课表 ----
    if args.mode in ("schedule", "all"):
        html = fetch_schedule_html(cookies, term=target_term, week=args.week, kbjcmsid=args.kbjcmsid)
        courses, remarks = parse_schedule(html)
        courses_sorted = sorted(courses, key=lambda c: (c["星期序号"], c["节次"]))
        result["学期"]   = target_term
        result["课程"]   = courses_sorted
        result["备注"]   = remarks
        
        # 顺便获取学期信息（含开学日期）以自动推算开学时间
        try:
            info = fetch_semester_info(cookies, target_term)
            result["学期信息"] = info
        except Exception as e:
            print("[!] 自动获取学期信息失败:", e)

        print("\n========== 课表（%s，共 %d 门次） ==========" % (target_term, len(courses)))

        for c in courses_sorted:
            print("  {星期} {大节}({节次}节) {上课时间} | {课程名} | {教师} | 第{周次}周 | {地点}".format(**c))
        if remarks:
            print("\n---- 备注/实践类课程 ----")
            for r in remarks:
                print("  *", r)

    # ---- 考试安排 ----
    if args.mode in ("exam", "all"):
        exams = fetch_exams(cookies, target_term)
        result["考试安排"] = exams
        print("\n========== 考试安排（%d 场） ==========" % len(exams))
        for e in exams:
            print("  ", " | ".join(str(v) for v in e.values() if v))

    # ---- 成绩 ----
    if args.mode in ("grade", "all"):
        grades = fetch_grades(cookies, args.term)
        result["成绩"] = grades
        print("\n========== 课程成绩（%d 条） ==========" % len(grades))
        for g in grades:
            print("  ", " | ".join(str(v) for v in g.values() if v))

    # ---- 落盘 ----
    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            json.dump(result, f, ensure_ascii=False, indent=2)
        print("\n[+] 已保存到 %s" % args.out)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(1)
