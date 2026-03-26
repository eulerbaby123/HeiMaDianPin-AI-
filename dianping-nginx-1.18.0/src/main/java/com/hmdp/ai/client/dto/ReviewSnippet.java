package com.hmdp.ai.client.dto;

import lombok.Data;

@Data
public class ReviewSnippet {
    private Long blogId;
    private String title;
    private String content;
    private Integer liked;
    private String createTime;
}

