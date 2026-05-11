package com.hmdp.service;

import com.hmdp.dto.Result;

/**
 * 搜索服务接口
 */
public interface ISearchService {

    /**
     * 全局智能搜索
     * @param query 搜索词
     * @return 搜索结果
     */
    Result globalSearch(String query);
}