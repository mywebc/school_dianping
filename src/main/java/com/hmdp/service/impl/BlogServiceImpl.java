package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;

    @Override
    public Result queryBlogById(Long id) {
        // 根据id查询
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("博客不存在");
        }
        // 查询用户
        queryBlogUser(blog);
        // 查看当前登录用户是否点赞，需要给前端一个字段，前端需要这个字段去高亮点赞按钮
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            // 用户不登录，不需要查询
            return;
        }
        Long userId = UserHolder.getUser().getId();
        // 先去redis的set集合里看是否有当前用户
        String key = "blog:liked:" + blog.getId();
//        Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            queryBlogUser(blog);
            // 查看当前登录用户是否点赞
            isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result likeBlog(Long id) {
        // 获取登录用户
        Long userId = UserHolder.getUser().getId();
        // 查看当前登录用户是否点赞
        // 先去redis的set集合里看是否有当前用户
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        // 从set取值
//        Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        // 优化： 从Zset取值
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null) {
            // 如果没有，说明没有点赞，数据库需要+1，redis需要添加
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            if (isSuccess) {
                // 存到set
//                stringRedisTemplate.opsForSet().add(key, userId.toString());
                // 优化：存到zset, 分数用时间戳  zadd key value score
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
            // 如果有，说明已经点赞，数据库需要-1，redis需要删除
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            if (isSuccess) {
                // 删除
//                stringRedisTemplate.opsForSet().remove(key, userId.toString());
                // 优化：从zset删除, 分数用时间戳  zadd key value score
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        // 查询top5的点赞用户（根据zset）
        Set<String> top = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top == null || top.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 解析出用户id
        List<Long> ids = top.stream().map(Long::valueOf).collect(Collectors.toList());
        // 再根据用户id查询用户信息
//        List<UserDTO> userDTOS = userService.listByIds(ids)
//                .stream()
//                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
//                .collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        // 我们要修改sql语句，我们要加orderByField
        List<UserDTO> userDTOS = userService.query()
                .in("id", ids)
                .last("ORDER BY FIELD(id, " + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        // 返回用户信息
        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 1. 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 2. 保存探店博文
        boolean isSuccess = save(blog);
        if (!isSuccess) {
            return Result.fail("保存失败");
        }
        // 查询笔记作者的粉丝 select * from tb_follow where follow_user_id = 作者id
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        // 推送笔记id给所有的粉丝
        for (Follow follow : follows) {
            // 获取粉丝的id
            Long userId = follow.getUserId();
            // 推送笔记id给粉丝
            String key = RedisConstants.FEED_KEY + userId;
            // 推送 zadd key score value
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        // 3. 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        // 大体总结下： 1. 先获取登录用户的粉丝 2. 根据粉丝去查询粉丝的笔记 3. 根据max和offset去查询
        // 1. 获取登录用户
        Long userId = UserHolder.getUser().getId();
        // 2. 查询粉丝(滚动分页查询) ZREVRANGEBYSCORE key max min LIMIT ffset count
        String key = RedisConstants.FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int os = 1;
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            // 获取笔记id
            ids.add(Long.valueOf(typedTuple.getValue()));
            // 获取时间戳
            long time = typedTuple.getScore().longValue();
            if (time == minTime) {
                os++;
            } else {
                minTime = time;
                os = 1;
            }
        }
        // 根据id查询笔记
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query()
                .in("id", ids)
                .last("ORDER BY FIELD(id, " + idStr + ")").list();

        for (Blog blog : blogs) {
            // 查询用户
            queryBlogUser(blog);
            // 查看当前登录用户是否点赞，需要给前端一个字段，前端需要这个字段去高亮点赞按钮
            isBlogLiked(blog);
        }
        // 封装并返回
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setOffset(os);
        r.setMinTime(minTime);
        return Result.ok(r);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
