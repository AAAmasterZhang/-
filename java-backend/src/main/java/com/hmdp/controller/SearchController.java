package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.service.ISearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * 搜索控制器
 */
@RestController
@RequestMapping("/blog/search")
public class SearchController {

    @Resource
    private ISearchService searchService;

    /**
     * 全局智能搜索
     * @param query 搜索词
     * @return 搜索结果
     */
    @GetMapping("/global")
    public Result globalSearch(@RequestParam("query") String query) {
        return searchService.globalSearch(query);
    }
}