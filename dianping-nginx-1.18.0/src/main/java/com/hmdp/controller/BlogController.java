package com.hmdp.controller;


import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.dto.ai.AiReviewRiskCheckRequestDTO;
import com.hmdp.dto.ai.AiReviewRiskCheckResponseDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.service.IAiService;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/blog")
public class BlogController {

    @Resource
    private IBlogService blogService;
    @Resource
    private IAiService aiService;
    @Resource
    private IUserService userService;

    @PostMapping
    public Result saveBlog(@RequestBody Blog blog) {
        AiReviewRiskCheckRequestDTO riskRequestDTO = new AiReviewRiskCheckRequestDTO();
        riskRequestDTO.setScene("BLOG_NOTE");
        riskRequestDTO.setTitle(blog.getTitle());
        riskRequestDTO.setContent(blog.getContent());
        riskRequestDTO.setShopId(blog.getShopId());
        Result riskResult = aiService.checkReviewRisk(riskRequestDTO);
        if (!Boolean.TRUE.equals(riskResult.getSuccess())) {
            return riskResult;
        }
        Object riskData = riskResult.getData();
        if (riskData instanceof AiReviewRiskCheckResponseDTO) {
            AiReviewRiskCheckResponseDTO riskResp = (AiReviewRiskCheckResponseDTO) riskData;
            if (shouldBlockByRisk(riskResp)) {
                return Result.fail(buildRiskBlockMessage(riskResp));
            }
        }
       return blogService.saveBlog(blog);
    }

    @PutMapping("/like/{id}")
    public Result likeBlog(@PathVariable("id") Long id) {
        // 修改点赞数量
        return blogService.updateLike(id);
    }

    @GetMapping("/of/me")
    public Result queryMyBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        List<Blog> records = blogService.query()
                .eq("user_id", user.getId())
                .orderByDesc("create_time")
                .list();
        return Result.ok(records == null ? java.util.Collections.emptyList() : records);
    }

    /**
     * 分页查询
     * @param current
     * @return
     */
    @GetMapping("/hot")
    public Result queryHotBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
       return blogService.queryHotBlog(current);
    }

    /**
     * 根据id查询
     * @param id
     * @return
     */
    @GetMapping("/{id}")
    public Result queryById(@PathVariable("id") Long id){
        return blogService.queryBlogById(id);
    }

    @GetMapping("/likes/{id}")
    public Result queryBlogLikes(@PathVariable("id") Long id){
        return blogService.queryBlogLikes(id);
    }

    @GetMapping("/of/user")
    public Result queryBlogByUserId(
            @RequestParam(value = "current",defaultValue = "1") Integer currrent,
            @RequestParam("id") Long id){
        //根据用户查询
        Page<Blog> page = blogService.query()
                .eq("user_id", id).page(new Page<>(currrent, SystemConstants.MAX_PAGE_SIZE));

        //获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }
    @GetMapping("/of/follow")
    public Result queryBlogOfFollow(
            @RequestParam("lastId") Long max,
            @RequestParam(value = "offset",defaultValue = "0") Integer offset){
        return blogService.queryBlogOfFollow(max,offset);

    }

    private String buildRiskBlockMessage(AiReviewRiskCheckResponseDTO riskResp) {
        String tags = "";
        if (riskResp.getRiskTags() != null && !riskResp.getRiskTags().isEmpty()) {
            tags = "（" + riskResp.getRiskTags().stream()
                    .filter(StrUtil::isNotBlank)
                    .limit(3)
                    .collect(Collectors.joining("、")) + "）";
        }
        String suggestion = StrUtil.blankToDefault(riskResp.getSuggestion(), "内容触发风控，请调整后再发布。");
        return "内容触发AI风控" + tags + "，" + suggestion;
    }

    private boolean shouldBlockByRisk(AiReviewRiskCheckResponseDTO riskResp) {
        if (riskResp == null) {
            return false;
        }
        if (Boolean.FALSE.equals(riskResp.getPass())) {
            return true;
        }
        String level = StrUtil.blankToDefault(riskResp.getRiskLevel(), "SAFE").toUpperCase();
        if ("BLOCK".equals(level) || "REVIEW".equals(level)) {
            return true;
        }
        if (riskResp.getRiskScore() != null && riskResp.getRiskScore() >= 45) {
            return true;
        }
        if (riskResp.getRiskTags() != null) {
            for (String tag : riskResp.getRiskTags()) {
                if (StrUtil.isBlank(tag)) {
                    continue;
                }
                if (StrUtil.equalsAnyIgnoreCase(tag.trim(), "广告引流", "联系方式", "违法违禁", "隐私泄露", "人身攻击")) {
                    return true;
                }
            }
        }
        return false;
    }
}
