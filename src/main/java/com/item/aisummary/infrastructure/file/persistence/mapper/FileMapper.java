package com.item.aisummary.infrastructure.file.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.item.aisummary.infrastructure.file.persistence.entity.FileEntity;
import org.apache.ibatis.annotations.Mapper;

/** file_asset 表的 MyBatis Mapper。 */
@Mapper
public interface FileMapper extends BaseMapper<FileEntity> {}
