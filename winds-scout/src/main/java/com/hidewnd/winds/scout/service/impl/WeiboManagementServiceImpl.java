package com.hidewnd.winds.scout.service.impl;

import com.hidewnd.winds.scout.dto.AccountCreateRequest;
import com.hidewnd.winds.scout.dto.AccountCredentialsRequest;
import com.hidewnd.winds.scout.dto.AccountResponse;
import com.hidewnd.winds.scout.exception.ScoutApiException;
import com.hidewnd.winds.scout.model.WeiboAccount;
import com.hidewnd.winds.scout.repository.WeiboAccountRepository;
import com.hidewnd.winds.scout.repository.WeiboAccountStateRepository;
import org.springframework.data.mongodb.core.query.Update;
import com.hidewnd.winds.scout.service.WeiboManagementService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 微博抓取账号管理服务实现，维护账号凭据和状态。
 */
@Service
public class WeiboManagementServiceImpl implements WeiboManagementService {

    private final WeiboAccountRepository accountRepository;
    private final WeiboCookieStore cookieStore;
    private final Clock clock;
    private final WeiboAccountStateRepository states;
    private final WeiboAlertService alerts;

    public WeiboManagementServiceImpl(
            WeiboAccountRepository accountRepository,
            WeiboCookieStore cookieStore,
            Clock clock, WeiboAccountStateRepository states, WeiboAlertService alerts) {
        this.accountRepository = accountRepository;
        this.cookieStore = cookieStore;
        this.clock = clock;
        this.states = states;
        this.alerts = alerts;
    }

    @Override
    public List<AccountResponse> listAccounts() {
        return accountRepository.findAll().stream().map(AccountResponse::from).toList();
    }

    @Override
    public AccountResponse getAccount(String id) {
        return AccountResponse.from(requireAccount(id));
    }

    @Override
    public AccountResponse createAccount(AccountCreateRequest request) {
        String id = request.id();
        Instant now = clock.instant();
        WeiboAccount account = new WeiboAccount(
                id, request.cookie(), "", "active", 0, 0,
                null, "", null, null, now, now);
        replaceCredentials(account, request.cookie());
        alerts.initializeAccount(account);
        try {
            account.setStateVersion(UUID.randomUUID().toString());
            AccountResponse response = AccountResponse.from(accountRepository.insert(account));
            alerts.maintain();
            return response;
        } catch (DuplicateKeyException exception) {
            throw new ScoutApiException(HttpStatus.CONFLICT, "微博账号已存在");
        }
    }

    @Override
    public AccountResponse updateCredentials(String id, AccountCredentialsRequest request) {
        WeiboAccount account = requireAccount(id);
        replaceCredentials(account, request.cookie());
        Instant now = clock.instant();
        Update update = new Update().set("cookie", account.getCookie()).set("xsrfToken", account.getXsrfToken())
                .set("cookies", account.getCookies()).set("status", "active").set("failCount", 0)
                .unset("lastErrorAt").set("lastErrorMessage", "").unset("recoverAt").unset("recoveryAttemptId").unset("requestLeaseUntil")
                .set("updatedAt", now).set("stateVersion", UUID.randomUUID().toString());
        return updateAccount(account, update);
    }

    @Override
    public AccountResponse setAccountStatus(String id, String status) {
        return updateAccount(requireAccount(id), new Update().set("status", status).unset("recoverAt")
                .unset("recoveryAttemptId").unset("requestLeaseUntil").set("updatedAt", clock.instant()).set("stateVersion", UUID.randomUUID().toString()));
    }

    @Override
    public void deleteAccount(String id) {
        alerts.flush();
        WeiboAccount account = requireAccount(id);
        alerts.preserveRecovery(account);
        if (!states.delete(account)) {
            throw new ScoutApiException(HttpStatus.CONFLICT, "账号状态已变化或仍有待保存告警，请稍后重试删除");
        }
        alerts.maintain();
    }

    private AccountResponse updateAccount(WeiboAccount account, Update update) {
        WeiboAccount saved = states.update(account, update);
        if (saved == null) throw new ScoutApiException(HttpStatus.CONFLICT, "账号状态已变化，请重试");
        alerts.maintain();
        return AccountResponse.from(saved);
    }

    private WeiboAccount requireAccount(String id) {
        return accountRepository.findById(id)
                .orElseThrow(() -> new ScoutApiException(HttpStatus.NOT_FOUND, "微博账号不存在"));
    }

    private void replaceCredentials(WeiboAccount account, String cookie) {
        cookieStore.replaceSeed(account, cookie);
        if (!StringUtils.hasText(account.getXsrfToken())) {
            throw new ScoutApiException(HttpStatus.BAD_REQUEST, "Cookie中缺少有效的XSRF-TOKEN");
        }
    }

}
