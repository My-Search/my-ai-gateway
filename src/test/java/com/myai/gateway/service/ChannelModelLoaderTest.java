package com.myai.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myai.gateway.entity.Channel;
import com.myai.gateway.entity.ChannelModel;
import com.myai.gateway.entity.ModelChannelRel;
import com.myai.gateway.mapper.ChannelApiKeyMapper;
import com.myai.gateway.mapper.ChannelMapper;
import com.myai.gateway.mapper.ChannelModelMapper;
import com.myai.gateway.mapper.ModelChannelRelMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ChannelModelLoader 单元测试
 * 重点验证：刷新模型（自动/手动）删除 source='api' 模型时，入口模型关联
 * （model_channel_rels）不悬空——同名单模型恢复关联、模型消失则清理关联
 */
class ChannelModelLoaderTest {

    private ChannelModelMapper channelModelMapper;
    private ModelChannelRelMapper relMapper;
    private ChannelModelLoader loader;

    @BeforeEach
    void setUp() {
        ChannelMapper channelMapper = mock(ChannelMapper.class);
        channelModelMapper = mock(ChannelModelMapper.class);
        relMapper = mock(ModelChannelRelMapper.class);
        ChannelApiKeyMapper apiKeyMapper = mock(ChannelApiKeyMapper.class);
        MultiModalRuleService rules = mock(MultiModalRuleService.class);
        when(rules.computeInput(any())).thenReturn("text");
        loader = mock(ChannelModelLoader.class, withSettings()
                .useConstructor(channelMapper, channelModelMapper, relMapper, apiKeyMapper,
                        new ObjectMapper(), rules,
                        mock(org.springframework.transaction.PlatformTransactionManager.class))
                .defaultAnswer(CALLS_REAL_METHODS));
    }

    private Channel channel() {
        Channel c = new Channel("ch", "openai", "https://example.com/v1");
        c.setId(1L);
        return c;
    }

    private ChannelModel apiModel(long id, String modelName) {
        ChannelModel cm = new ChannelModel(1L, modelName, modelName);
        cm.setId(id);
        cm.setSource("api");
        return cm;
    }

    @Test
    void loadModels_preservesRelsByModelNameAfterRefresh() {
        // 旧 api 模型 101(gpt-4o) 被入口模型 7 关联
        ChannelModel oldApi = apiModel(101L, "gpt-4o");
        ModelChannelRel rel = new ModelChannelRel(7L, 101L);
        rel.setSortOrder(2);
        rel.setEnabled(1);
        // 刷新后服务商返回同名 gpt-4o（新 id 201）
        ChannelModel newApi = apiModel(201L, "gpt-4o");

        when(channelModelMapper.selectList(any())).thenReturn(
                List.of(oldApi),   // 待删 api 模型
                List.of(),         // 现有手动模型
                List.of(newApi),   // 刷新后渠道全部模型（恢复关联依据）
                List.of(newApi));  // 最终返回
        when(relMapper.selectList(any())).thenReturn(List.of(rel));
        // 注意：CALLS_REAL_METHODS 的 mock 需用 doReturn，避免 when() 中真实执行方法
        doReturn(List.of(newApi)).when(loader).fetchNewModels(any(), any());

        List<ChannelModel> result = loader.loadModels(channel(), null);

        // 旧关联（指向 101）先被清理
        verify(relMapper).delete(any());
        // 关联迁移到刷新后的模型 id=201，并保留原 sortOrder/enabled
        ArgumentCaptor<ModelChannelRel> captor = ArgumentCaptor.forClass(ModelChannelRel.class);
        verify(relMapper).insert(captor.capture());
        ModelChannelRel migrated = captor.getValue();
        assertThat(migrated.getModelId()).isEqualTo(7L);
        assertThat(migrated.getChannelModelId()).isEqualTo(201L);
        assertThat(migrated.getSortOrder()).isEqualTo(2);
        assertThat(migrated.getEnabled()).isEqualTo(1);
        assertThat(result).hasSize(1);
    }

    @Test
    void loadModels_dropsRelsWhenModelNoLongerReturned() {
        ChannelModel oldApi = apiModel(101L, "gpt-4o");
        ModelChannelRel rel = new ModelChannelRel(7L, 101L);
        ChannelModel newMini = apiModel(202L, "gpt-4o-mini");

        when(channelModelMapper.selectList(any())).thenReturn(
                List.of(oldApi),
                List.of(),
                List.of(newMini),
                List.of(newMini));
        when(relMapper.selectList(any())).thenReturn(List.of(rel));
        doReturn(List.of(newMini)).when(loader).fetchNewModels(any(), any());

        loader.loadModels(channel(), null);

        // 旧关联被清理；服务商不再返回 gpt-4o，关联不再重建（无悬空）
        verify(relMapper).delete(any());
        verify(relMapper, never()).insert(any(ModelChannelRel.class));
    }

    @Test
    void loadModels_whenNoApiModelsToDelete_skipsRelCleanup() {
        ChannelModel newApi = apiModel(201L, "gpt-4o");
        when(channelModelMapper.selectList(any())).thenReturn(
                List.of(), List.of(), List.of(newApi), List.of(newApi));
        doReturn(List.of(newApi)).when(loader).fetchNewModels(any(), any());

        loader.loadModels(channel(), null);

        // 没有任何 api 模型待删除，不触碰关联表
        verify(relMapper, never()).selectList(any());
        verify(relMapper, never()).delete(any());
        verify(relMapper, never()).insert(any(ModelChannelRel.class));
    }

    @Test
    void loadModels_fetchFailure_keepsExistingModelsAndRels() {
        ChannelModel oldApi = apiModel(101L, "gpt-4o");
        ModelChannelRel rel = new ModelChannelRel(7L, 101L);
        when(channelModelMapper.selectList(any())).thenReturn(
                List.of(oldApi),   // 预查待替换 api 模型
                List.of(oldApi));  // 失败分支返回现有模型
        when(channelModelMapper.selectCount(any())).thenReturn(1L);
        doReturn(List.of()).when(loader).fetchNewModels(any(), any());

        List<ChannelModel> result = loader.loadModels(channel(), null);

        // 拉取失败且渠道已有现役模型：不删除、不替换，保留模型与关联
        verify(channelModelMapper, never()).delete(any());
        verify(relMapper, never()).delete(any());
        verify(relMapper, never()).insert(any(ModelChannelRel.class));
        assertThat(result).containsExactly(oldApi);
    }

    @Test
    void loadModels_fetchFailure_withoutExistingModels_fallsBackToDefaultModels() {
        ChannelModel defaultGpt = apiModel(301L, "gpt-4o");
        when(channelModelMapper.selectList(any())).thenReturn(
                List.of(),              // 预查 api 模型
                List.of(),              // 手动模型
                List.of(defaultGpt));   // 最终返回
        when(channelModelMapper.selectCount(any())).thenReturn(0L);
        doReturn(List.of()).when(loader).fetchNewModels(any(), any());

        List<ChannelModel> result = loader.loadModels(channel(), null);

        // 新建渠道拉取失败：回退预设模型并插入（保留原有能力）
        verify(channelModelMapper, atLeast(1)).insert(any(ChannelModel.class));
        assertThat(result).containsExactly(defaultGpt);
    }
}