package com.soulous.wallet;

import com.soulous.auth.UserService;
import com.soulous.common.web.BaseController;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 【钱包 REST 控制器：查询当前用户金币余额与最近流水，供「我的」页展示。
 *  需登录（落在 SecurityConfig 的 anyRequest().authenticated()）。】
 */
@RestController
@RequestMapping("/api/wallet")
class WalletController extends BaseController {
    private final CoinService coins;

    WalletController(UserService users, CoinService coins) {
        super(users);
        this.coins = coins;
    }

    /** 【当前用户钱包：余额 + 最近 50 条流水】 */
    @GetMapping
    Map<String, Object> wallet(HttpServletRequest request) {
        var user = current(request);
        var m = new LinkedHashMap<String, Object>();
        m.put("balance", coins.balance(user));
        m.put("ledger", coins.recent(user).stream().map(CoinService::view).toList());
        return m;
    }
}
