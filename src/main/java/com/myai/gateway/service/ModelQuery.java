package com.myai.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.myai.gateway.entity.Model;
import com.myai.gateway.mapper.ModelMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 模型查询 - 负责模型的简单查询操作
 */
@Component
public class ModelQuery {

    private final ModelMapper modelMapper;

    public ModelQuery(ModelMapper modelMapper) {
        this.modelMapper = modelMapper;
    }

    public List<Model> listAll() {
        return modelMapper.selectList(
                new LambdaQueryWrapper<Model>().orderByAsc(Model::getCreatedAt));
    }

    public List<Model> listVisible() {
        return modelMapper.selectList(
                new LambdaQueryWrapper<Model>()
                        .eq(Model::getHidden, 0)
                        .eq(Model::getEnabled, 1)
                        .orderByAsc(Model::getCreatedAt));
    }

    public List<Model> listInheritableModels(Long excludeModelId) {
        return modelMapper.selectList(
                new LambdaQueryWrapper<Model>()
                        .eq(Model::getEnabled, 1)
                        .ne(excludeModelId != null, Model::getId, excludeModelId)
                        .orderByAsc(Model::getModelName));
    }
}
