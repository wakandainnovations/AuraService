package com.aura.service.service;

import com.aura.service.dto.BoxOfficeMovieBaseline;
import com.aura.service.dto.MovieBacktestRow;

/**
 * Computes the server-side baseline potential (B0) for a movie: present-value-adjusted budget
 * times a talent/concept/IP multiplier. The LLM never sees or influences this number - it only
 * supplies the compounding factor ratings applied on top of it. See
 * {@link BoxOfficeBaselineServiceImpl} for the formula details.
 */
public interface BoxOfficeBaselineService {

    BoxOfficeMovieBaseline computeBaseline(MovieBacktestRow row);
}
