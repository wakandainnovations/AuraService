package com.aura.service.controller;

import com.aura.service.dto.GraphSubgraphResponse;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.enums.GraphNodeType;
import com.aura.service.enums.GraphRelationType;
import com.aura.service.exception.GlobalExceptionHandler;
import com.aura.service.exception.ResourceNotFoundException;
import com.aura.service.service.EntityAccessService;
import com.aura.service.service.GraphQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers {@link GraphController}'s wiring: the ownership/admin-scoping guard runs before the query
 * (mirroring {@code DashboardController.getMentions}), depth/size are clamped to the controller's
 * ceilings, and query params are passed through to {@link GraphQueryService}. {@link GraphQueryService}
 * and {@link EntityAccessService} are mocked as interfaces, never concrete classes.
 */
class GraphControllerTest {

    private static final Long MOVIE_ID = 5L;

    private GraphQueryService graphQueryService;
    private EntityAccessService entityAccessService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        graphQueryService = mock(GraphQueryService.class);
        entityAccessService = mock(EntityAccessService.class);
        GraphController controller = new GraphController(graphQueryService, entityAccessService);

        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        MappingJackson2HttpMessageConverter jacksonConverter =
                new MappingJackson2HttpMessageConverter(mapper);

        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(jacksonConverter)
                .build();
    }

    @Test
    void notOwned_returnsNotFound() throws Exception {
        doThrow(new ResourceNotFoundException("not found"))
                .when(entityAccessService).assertAccessible(eq(MOVIE_ID), isNull());

        mvc.perform(get("/api/graph/movies/{movieId}", MOVIE_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    void defaultParams_returnsSubgraphAndClampsDepthAndSize() throws Exception {
        when(entityAccessService.assertAccessible(eq(MOVIE_ID), isNull())).thenReturn(new ManagedEntity());
        GraphSubgraphResponse response = new GraphSubgraphResponse(
                MOVIE_ID, 1, List.of(), List.of(), 0, 100, false);
        when(graphQueryService.getMovieSubgraph(
                eq(MOVIE_ID), isNull(), isNull(), isNull(), isNull(), eq(1), any(Pageable.class)))
                .thenReturn(response);

        mvc.perform(get("/api/graph/movies/{movieId}", MOVIE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.movieId").value(MOVIE_ID))
                .andExpect(jsonPath("$.depth").value(1))
                .andExpect(jsonPath("$.nodes.length()").value(0))
                .andExpect(jsonPath("$.edges.length()").value(0));
    }

    @Test
    void depthAboveCeiling_isClampedBeforeReachingTheService() throws Exception {
        when(entityAccessService.assertAccessible(eq(MOVIE_ID), isNull())).thenReturn(new ManagedEntity());
        when(graphQueryService.getMovieSubgraph(any(), any(), any(), any(), any(), any(Integer.class), any()))
                .thenReturn(new GraphSubgraphResponse(MOVIE_ID, 5, List.of(), List.of(), 0, 100, false));

        mvc.perform(get("/api/graph/movies/{movieId}", MOVIE_ID).param("depth", "50"))
                .andExpect(status().isOk());

        verify(graphQueryService).getMovieSubgraph(
                eq(MOVIE_ID), isNull(), isNull(), isNull(), isNull(), eq(5), any(Pageable.class));
    }

    @Test
    void filtersAndDateRangeAndPagingArePassedThrough() throws Exception {
        when(entityAccessService.assertAccessible(eq(MOVIE_ID), isNull())).thenReturn(new ManagedEntity());
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-06-30T00:00:00Z");
        when(graphQueryService.getMovieSubgraph(any(), any(), any(), any(), any(), any(Integer.class), any()))
                .thenReturn(new GraphSubgraphResponse(MOVIE_ID, 2, List.of(), List.of(), 1, 20, true));

        mvc.perform(get("/api/graph/movies/{movieId}", MOVIE_ID)
                        .param("nodeType", "ACTOR")
                        .param("relationType", "WATCHED")
                        .param("from", from.toString())
                        .param("to", to.toString())
                        .param("depth", "2")
                        .param("page", "1")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasMore").value(true));

        verify(graphQueryService).getMovieSubgraph(
                eq(MOVIE_ID), eq(GraphNodeType.ACTOR), eq(GraphRelationType.WATCHED), eq(from), eq(to),
                eq(2), any(Pageable.class));
    }
}
