package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.SystemConstants;

import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;


    /**
     * 根据id查询商铺信息
     * @param id 商铺id
     * @return 商铺详情
     */
    @Override
    public Result queryById(Long id) {


/*
        //解决缓存击穿
        Shop shop = cacheClient.queryWithLogicalExpire("cache:shop:", id, Shop.class, this::getById, 30L, TimeUnit.MINUTES);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        //缓存击穿成功，返回店铺信息
        return Result.ok(shop);
*/



        //解决缓存穿透
        Shop shop = cacheClient.queryWithPassThrough("cache:shop:", id, Shop.class, this::getById, 30L, TimeUnit.MINUTES);
        if(shop == null){
            return Result.fail("店铺不存在");
        }

        return Result.ok(shop);

        /*//从redis中判断是否存在
        String key = "cache:shop:"+id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        //存在，反序列化+返回
        if(StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }

        //不存在，根据id查询数据库
        Shop shop = getById(id);

        //数据库不存在，报错
        if(shop == null){
            return Result.fail("店铺不存在");
        }

        //数据库存在，写入redis。加上过期时间
        shopJson = JSONUtil.toJsonStr(shop);    // 序列化
        stringRedisTemplate.opsForValue().set(key, shopJson,30L, TimeUnit.MINUTES);

        // 返回
        return Result.ok(shop);*/

    }

     /**
      * 更新商铺信息
      * @param shop 商铺数据
      * @return 无
      */
    @Override
    @Transactional      // 开启事务
    public Result update(Shop shop) {

        //校验id是否为空
        Long id = shop.getId();
        if(id == null){
            return Result.fail("店铺id不能为空");
        }

        //更新数据库
        updateById(shop);

        //删除缓存
        stringRedisTemplate.delete("cache:shop:"+id);
        return Result.ok();
    }

    /**
     * 根据商铺类型分页查询商铺信息
     * @param typeId 商铺类型
     * @param current 页码
     * @param x 经度
     * @param y 纬度
     * @return 商铺列表
     */
    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        if(x == null || y == null){
            // 根据类型分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }

        //计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;      //当前页码 * 每页大小

        //查询redis，按照距离排序，分页
        String key = "shop:geo:"+typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().search(key,
                GeoReference.fromCoordinate(x, y),
                new Distance(5000),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end));

        //解析id
        if(results == null){
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if(list.size() <= from){
            return Result.ok(Collections.emptyList());
        }

        //截取from-end部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String,Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
                    //获取店铺id
                    String shopIdStr = result.getContent().getName();
                    ids.add(Long.valueOf(shopIdStr));
                    //获取距离
                    Distance distance = result.getDistance();
                    distanceMap.put(shopIdStr, distance);
                });

        //根据id查询shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("order by field(id,"+idStr+")").list();
        for(Shop shop : shops){
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        //返回
        return Result.ok(shops);

    }


}
