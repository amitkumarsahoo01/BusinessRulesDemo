package com.dmnrules.ai.llm.cost;

import com.dmnrules.ai.llm.InvalidLlmSettingsException;
import com.dmnrules.ai.llm.LlmProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * One monthly budget (calendar month, UTC) across every LLM provider. Every model call is priced and
 * recorded by {@link LlmUsageMeter}; before each chat message {@link #blockReason} decides whether the
 * month's spend leaves room for it.
 *
 * <p>The check runs before a message starts, so the message that crosses the line still finishes:
 * the overshoot is bounded by one message (a few tool rounds, each capped at 4,096 output tokens).
 */
@Service
public class LlmBudgetService {

    private static final Logger log = LoggerFactory.getLogger(LlmBudgetService.class);
    private static final Pattern PREFIX = Pattern.compile("[A-Za-z0-9._:/@-]{1,200}");
    private static final BigDecimal MAX_LIMIT = BigDecimal.valueOf(100_000);
    private static final BigDecimal MAX_PRICE = BigDecimal.valueOf(10_000);

    public enum State { OK, WARNING, EXCEEDED }

    public record Settings(BigDecimal monthlyLimitUsd, int warnPercent, boolean hardStop, String updatedBy,
            Instant updatedAt) {
    }

    public record Status(String month, Settings settings, BigDecimal spentUsd, BigDecimal remainingUsd,
            double percentUsed, State state, List<LlmCostRepository.ModelSpend> byModel,
            List<LlmCostRepository.DailySpend> daily, List<LlmPrice> prices) {
    }

    private final LlmCostRepository repository;
    private final BigDecimal defaultLimit;
    private final int defaultWarnPercent;
    private final Clock clock;

    @Autowired
    public LlmBudgetService(LlmCostRepository repository,
            @Value("${rules-ai.budget.monthly-usd:25}") BigDecimal defaultLimit,
            @Value("${rules-ai.budget.warn-percent:80}") int defaultWarnPercent) {
        this(repository, defaultLimit, defaultWarnPercent, Clock.systemUTC());
    }

    LlmBudgetService(LlmCostRepository repository, BigDecimal defaultLimit, int defaultWarnPercent, Clock clock) {
        this.repository = repository;
        this.defaultLimit = defaultLimit;
        this.defaultWarnPercent = Math.max(1, Math.min(100, defaultWarnPercent));
        this.clock = clock;
    }

    /** Prices and stores one model call. Never throws: metering must not break a chat reply. */
    public void record(LlmProvider provider, String model, int inputTokens, int outputTokens, boolean estimated) {
        try {
            LlmPricing.Resolved price = LlmPricing.resolve(repository.prices(), provider.id(), model);
            BigDecimal cost = price.cost(inputTokens, outputTokens);
            repository.insertUsage(new LlmCostRepository.UsageRow(provider.id(), model, inputTokens, outputTokens,
                    cost, estimated, price.fallback(), clock.instant()));
            if (price.fallback()) {
                log.warn("No price configured for {} / {}; charged at the provider's highest rate", provider.id(), model);
            }
        } catch (RuntimeException e) {
            log.error("Could not record LLM usage for {} / {}", provider.id(), model, e);
        }
    }

    public Settings settings() {
        return repository.budget()
                .map(b -> new Settings(b.monthlyLimitUsd(), b.warnPercent(), b.hardStop(), b.updatedBy(), b.updatedAt()))
                .orElseGet(() -> new Settings(defaultLimit, defaultWarnPercent, true, null, null));
    }

    public BigDecimal spentThisMonth() {
        YearMonth month = YearMonth.now(clock.withZone(ZoneOffset.UTC));
        return repository.spendBetween(start(month), start(month.plusMonths(1)));
    }

    /**
     * Why a new chat message must not go to {@code provider}, or empty if it may. Self-hosted Ollama
     * has no per-token cost, so it keeps working after the paid budget is used up.
     */
    public Optional<String> blockReason(LlmProvider provider) {
        if (provider == LlmProvider.OLLAMA) {
            return Optional.empty();
        }
        Settings s = settings();
        if (!s.hardStop()) {
            return Optional.empty();
        }
        BigDecimal spent = spentThisMonth();
        if (spent.compareTo(s.monthlyLimitUsd()) >= 0) {
            return Optional.of("The AI budget for this month is used up ($" + money(spent) + " of $"
                    + money(s.monthlyLimitUsd()) + "), so Bob is paused until the 1st of next month (UTC). "
                    + "An administrator can raise the budget in the admin console.");
        }
        return Optional.empty();
    }

    public Status status() {
        YearMonth month = YearMonth.now(clock.withZone(ZoneOffset.UTC));
        Instant from = start(month);
        Instant to = start(month.plusMonths(1));
        Settings s = settings();
        BigDecimal spent = repository.spendBetween(from, to);
        BigDecimal remaining = s.monthlyLimitUsd().subtract(spent).max(BigDecimal.ZERO);
        double percent = s.monthlyLimitUsd().signum() == 0
                ? (spent.signum() > 0 ? 100.0 : 0.0)
                : spent.multiply(BigDecimal.valueOf(100)).divide(s.monthlyLimitUsd(), 2, RoundingMode.HALF_UP).doubleValue();
        // Same rule as blockReason: a $0 budget counts as used up.
        State state = spent.compareTo(s.monthlyLimitUsd()) >= 0 ? State.EXCEEDED
                : percent >= s.warnPercent() ? State.WARNING : State.OK;
        return new Status(month.toString(), s, spent, remaining, percent, state,
                repository.spendByModel(from, to), repository.dailySpend(from, to), repository.prices());
    }

    public Settings update(BigDecimal limit, Integer warnPercent, Boolean hardStop, String actor) {
        if (limit == null || limit.signum() < 0 || limit.compareTo(MAX_LIMIT) > 0) {
            throw new InvalidLlmSettingsException("Monthly budget must be between $0 and $" + MAX_LIMIT + ".");
        }
        int warn = warnPercent == null ? defaultWarnPercent : warnPercent;
        if (warn < 1 || warn > 100) {
            throw new InvalidLlmSettingsException("Warning threshold must be between 1% and 100%.");
        }
        LlmCostRepository.BudgetRow row = new LlmCostRepository.BudgetRow(limit.setScale(2, RoundingMode.HALF_UP),
                warn, hardStop == null || hardStop, actor, clock.instant());
        repository.saveBudget(row);
        log.info("LLM budget changed by {}: ${}/month, warn at {}%, hard stop {}", actor, row.monthlyLimitUsd(), warn,
                row.hardStop());
        return settings();
    }

    public void upsertPrice(String provider, String modelPrefix, BigDecimal in, BigDecimal out, String actor) {
        LlmProvider p = LlmProvider.fromId(provider)
                .orElseThrow(() -> new InvalidLlmSettingsException("Unknown provider: " + provider));
        String prefix = modelPrefix == null ? "" : modelPrefix.trim();
        if (!PREFIX.matcher(prefix).matches()) {
            throw new InvalidLlmSettingsException("Model name is required and may only contain letters, digits and . _ : / @ -");
        }
        if (in == null || out == null || in.signum() < 0 || out.signum() < 0
                || in.compareTo(MAX_PRICE) > 0 || out.compareTo(MAX_PRICE) > 0) {
            throw new InvalidLlmSettingsException("Prices must be between $0 and $" + MAX_PRICE + " per million tokens.");
        }
        repository.upsertPrice(p.id(), prefix, in, out, actor, clock.instant());
    }

    public void deletePrice(String provider, String modelPrefix) {
        if (!repository.deletePrice(provider, modelPrefix == null ? "" : modelPrefix.trim())) {
            throw new InvalidLlmSettingsException("No price row for " + provider + " / " + modelPrefix);
        }
    }

    private static Instant start(YearMonth month) {
        return LlmCostRepository.startOfDay(LocalDate.of(month.getYear(), month.getMonth(), 1));
    }

    private static String money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
