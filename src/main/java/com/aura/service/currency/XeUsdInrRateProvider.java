package com.aura.service.currency;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Date;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.OptionalDouble;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * USD/INR from the {@code currency_rate_xe} table, which AuraDataFiller's {@code ExchangeRateService}
 * fills from xe.com's daily currency tables. When the table has no current rate, this fetches one
 * from the same page and stores it, so the next request (and AuraDataFiller) reuses it.
 *
 * <p>"Current" means dated today or yesterday: xe.com only publishes a day's table once that day is
 * over, so during the day yesterday's is the newest there is. If xe.com can't be reached, the most
 * recent stored rate is used rather than failing the request; the returned {@code rateDate} shows
 * how old it is.
 */
@Service
public class XeUsdInrRateProvider implements UsdInrRateProvider {

    private static final Logger log = LoggerFactory.getLogger(XeUsdInrRateProvider.class);

    private static final String XE_TABLES_URL = "https://www.xe.com/currencytables/?from=INR&date=";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    // The USD entry in the historicRates array embedded in the page: {"currency":"USD","rate":0.0105...}
    private static final Pattern USD_RATE =
            Pattern.compile("\"currency\"\\s*:\\s*\"USD\"\\s*,\\s*\"rate\"\\s*:\\s*([0-9.]+)");
    // Today's table 404s until the day ends; look back a few days in case of a gap.
    private static final int XE_LOOKBACK_DAYS = 3;
    private static final LocalDate ANY_DATE = LocalDate.of(1900, 1, 1);

    private static final String LATEST_RATE_SQL =
            "SELECT rate_date, rate FROM currency_rate_xe " +
            "WHERE from_currency = 'INR' AND to_currency = 'USD' AND rate_date >= :since " +
            "ORDER BY rate_date DESC LIMIT 1";

    // Same upsert as AuraDataFiller's CrawlerDatabaseService.upsertExchangeRate.
    private static final String UPSERT_RATE_SQL =
            "INSERT INTO currency_rate_xe (rate_date, from_currency, to_currency, rate, fetched_at) " +
            "VALUES (:rateDate, 'INR', 'USD', :rate, NOW()) " +
            "ON CONFLICT (rate_date, from_currency, to_currency) " +
            "DO UPDATE SET rate = EXCLUDED.rate, fetched_at = NOW()";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final TransactionTemplate transactionTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    public XeUsdInrRateProvider(TransactionTemplate transactionTemplate) {
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public UsdInrRate currentRate() {
        LocalDate today = LocalDate.now();
        UsdInrRate stored = latestStoredSince(today.minusDays(1));
        return stored != null ? stored : fetchAndStore(today);
    }

    // Synchronized so concurrent requests on a cold day make one xe.com call, not one each.
    private synchronized UsdInrRate fetchAndStore(LocalDate today) {
        UsdInrRate stored = latestStoredSince(today.minusDays(1));
        if (stored != null) {
            return stored;
        }
        for (int daysBack = 0; daysBack <= XE_LOOKBACK_DAYS; daysBack++) {
            LocalDate date = today.minusDays(daysBack);
            OptionalDouble rate = fetchFromXe(date);
            if (rate.isPresent()) {
                UsdInrRate fetched = new UsdInrRate(date, rate.getAsDouble());
                store(fetched);
                log.info("exchange_rate fetched from xe.com rate_date={} inr_to_usd={}", date, fetched.inrToUsd());
                return fetched;
            }
        }
        UsdInrRate fallback = latestStoredSince(ANY_DATE);
        if (fallback == null) {
            throw new ExchangeRateUnavailableException("No USD/INR exchange rate is stored and xe.com is unavailable");
        }
        log.warn("exchange_rate xe.com unavailable, using stored rate from {}", fallback.rateDate());
        return fallback;
    }

    private UsdInrRate latestStoredSince(LocalDate since) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(LATEST_RATE_SQL)
                .setParameter("since", Date.valueOf(since))
                .getResultList();
        if (rows.isEmpty()) {
            return null;
        }
        Object[] row = rows.get(0);
        LocalDate rateDate = row[0] instanceof Date d ? d.toLocalDate() : (LocalDate) row[0];
        return new UsdInrRate(rateDate, ((Number) row[1]).doubleValue());
    }

    private void store(UsdInrRate rate) {
        transactionTemplate.executeWithoutResult(status -> entityManager.createNativeQuery(UPSERT_RATE_SQL)
                .setParameter("rateDate", Date.valueOf(rate.rateDate()))
                .setParameter("rate", rate.inrToUsd())
                .executeUpdate());
    }

    private OptionalDouble fetchFromXe(LocalDate date) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(XE_TABLES_URL + date))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Referer", "https://www.xe.com/")
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.info("exchange_rate xe.com status={} rate_date={}", response.statusCode(), date);
                return OptionalDouble.empty();
            }
            return parseInrToUsd(response.body());
        } catch (IOException ex) {
            log.warn("exchange_rate xe.com request failed rate_date={} cause={}", date, ex.toString());
            return OptionalDouble.empty();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return OptionalDouble.empty();
        }
    }

    static OptionalDouble parseInrToUsd(String body) {
        Matcher m = USD_RATE.matcher(body);
        if (!m.find()) {
            return OptionalDouble.empty();
        }
        double rate = Double.parseDouble(m.group(1));
        return rate > 0 ? OptionalDouble.of(rate) : OptionalDouble.empty();
    }
}
