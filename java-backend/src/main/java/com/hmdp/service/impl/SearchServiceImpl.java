package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.service.ISearchService;
import com.hmdp.service.IShopService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 搜索服务实现
 */
@Service
public class SearchServiceImpl implements ISearchService {

    @Autowired
    private IShopService shopService;

    @Autowired
    private RestTemplate restTemplate;

    // FastAPI RAG 接口地址
    private static final String RAG_API_URL = "http://localhost:8000/api/rag/chat";

    @Override
    public Result globalSearch(String query) {
        // Step 1: 数据库查询
        List<Shop> shops = shopService.query()
                .like(StrUtil.isNotBlank(query), "name", query)
                .list();

        // 判断是否找到数据
        if (shops != null && !shops.isEmpty()) {
            // 数据库命中，封装返回
            Map<String, Object> data = new HashMap<>();
            data.put("type", "database");
            data.put("records", shops);
            return Result.ok(data);
        } else {
            // 数据库未命中，调用 AI 补位
            String ragAnswer = callRagApi(query);
            Map<String, Object> data = new HashMap<>();
            data.put("type", "rag");
            data.put("answer", ragAnswer);
            return Result.ok(data);
        }
    }

    /**
     * 调用 FastAPI RAG 接口
     * @param query 搜索词
     * @return RAG 回答
     */
    private String callRagApi(String query) {
        try {
            // 构建请求体，匹配 FastAPI 接口格式，在python那边请求中必须包含input和session_id两个字段
            Map<String, String> requestBody = new HashMap<>();
            requestBody.put("input", query);
            requestBody.put("session_id", "default");

            // 设置请求头
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            // 构建请求实体，HttpEntity需包含请求体和请求头两个参数
            HttpEntity<Map<String, String>> requestEntity = new HttpEntity<>(requestBody, headers);

            // 调用 FastAPI 接口，返回值类型是map
            //postForObject，基于restTemplate这一spring提供的发送请求的工具类，发送post请求，拿到json，自动转为map类型
            Map<String, Object> response = restTemplate.postForObject(RAG_API_URL, requestEntity, Map.class);

            // 解析响应，匹配 FastAPI 接口格式
            /*返回值格式
            response = {
                    "code": 200,
                    "data": { "answer": "推荐你去XX餐厅..." },
                    "msg": "success"
             }
             */
            if (response != null && response.containsKey("data")) {
                //获取返回信息中的数据部分（data）
                Map<String, Object> data = (Map<String, Object>) response.get( "data");
                if (data.containsKey("answer")) {
                    //获取数据中的answer字段值
                    return data.get("answer").toString();
                }
            }
        } catch (Exception e) {
            // 调用失败，返回默认提示
            e.printStackTrace();
        }

        // 默认回答
        return "抱歉，暂时无法提供相关信息，请尝试其他搜索词。";
    }
}