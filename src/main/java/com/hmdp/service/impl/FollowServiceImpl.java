package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Override
    public Result follow(Long id, Boolean isFollow) {
        // 0 .获取登录用户id
        Long userId = UserHolder.getUser().getId();
        // 1. 判断关注还是取关
        if (isFollow) {
            // 2. 关注就新增数据
            Follow follow = new Follow().setUserId(userId).setFollowUserId(id);
            save(follow);
        } else {
            // 3. 取关就删除数据
            remove(new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id", id));
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long id) {
        // 查询是否关注, 查数据后面可以用one, 想知道存在不存在后面用count
        Integer count = query().eq("user_id", UserHolder.getUser().getId()).eq("follow_user_id", id).count();
        return Result.ok(count > 0);
    }
}
