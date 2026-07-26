package com.aura.service.dto;

/**
 * A movie's natural key in {@code movies_data_collection} (that table has no JPA entity/surrogate
 * id - see {@code BoxOfficeBacktestServiceImpl}). Used to re-run the box-office backtest over an
 * explicit movie set, e.g. one captured from a prior run's results before the app restarted (run
 * state is in-memory only, so it doesn't survive a restart on its own).
 */
public record MovieIdentifier(String movieName, String releaseDate) {
}
