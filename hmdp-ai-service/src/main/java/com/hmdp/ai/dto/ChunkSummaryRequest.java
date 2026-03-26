package com.hmdp.ai.dto;

import lombok.Data;

import java.util.List;

@Data
public class ChunkSummaryRequest {
    private Long shopId;
    private String shopName;
    private Integer chunkIndex;
    private Integer totalChunks;
    private List<ReviewSnippet> reviews;
}

