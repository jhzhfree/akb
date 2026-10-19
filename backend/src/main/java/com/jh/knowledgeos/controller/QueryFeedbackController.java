package com.jh.knowledgeos.controller;

import com.jh.knowledgeos.common.ApiResponse;
import com.jh.knowledgeos.dto.QueryFeedbackRequest;
import com.jh.knowledgeos.dto.QueryFeedbackReviewRequest;
import com.jh.knowledgeos.service.QueryFeedbackService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import javax.validation.Valid;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** User feedback intake and knowledge-operations review queue. */
@RestController
@RequestMapping("/api/knowledge/feedback")
@CrossOrigin
@Tag(name = "Answer Feedback", description = "回答反馈与知识纠错闭环")
public class QueryFeedbackController {

    private final QueryFeedbackService service;

    public QueryFeedbackController(QueryFeedbackService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "提交或更新本人对问答回答的反馈")
    public ApiResponse<Map<String, Object>> submit(@Valid @RequestBody QueryFeedbackRequest request) {
        return ApiResponse.ok(service.submit(request));
    }

    @GetMapping("/mine/{kb}")
    @Operation(summary = "查看本人在该知识库提交的反馈")
    public ApiResponse<List<Map<String, Object>>> mine(@PathVariable Long kb,
        @RequestParam(value = "limit", required = false) Integer limit) {
        return ApiResponse.ok(service.mine(kb, limit));
    }

    @GetMapping("/ops/{kb}")
    @Operation(summary = "查看知识库反馈队列和状态汇总")
    public ApiResponse<Map<String, Object>> operations(@PathVariable Long kb,
        @RequestParam(value = "status", required = false) String status,
        @RequestParam(value = "limit", required = false) Integer limit) {
        return ApiResponse.ok(service.operations(kb, status, limit));
    }

    @PutMapping("/ops/{id}/review")
    @Operation(summary = "审核反馈并设置整改/解决状态")
    public ApiResponse<Map<String, Object>> review(@PathVariable Long id,
        @Valid @RequestBody QueryFeedbackReviewRequest request) {
        return ApiResponse.ok(service.review(id, request));
    }
}
