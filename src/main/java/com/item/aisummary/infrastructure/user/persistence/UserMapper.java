package com.item.aisummary.infrastructure.user.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 用户表 Mapper，常规查询由 MyBatis-Plus 附加 deleted_at IS NULL。 */
@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {
}
