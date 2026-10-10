package com.item.aisummary.infrastructure.file.persistence.mapper;

import com.item.aisummary.infrastructure.file.persistence.entity.FileEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FileMapper extends BaseMapper<FileEntity> { }
