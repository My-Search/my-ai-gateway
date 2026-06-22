package com.myai.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.myai.gateway.entity.Channel;
import com.myai.gateway.entity.ChannelModel;
import com.myai.gateway.entity.Model;
import com.myai.gateway.entity.ModelChannelRel;
import com.myai.gateway.mapper.CircuitBreakerConfigMapper;
import com.myai.gateway.mapper.ChannelMapper;
import com.myai.gateway.mapper.ChannelModelMapper;
import com.myai.gateway.mapper.ModelChannelRelMapper;
import com.myai.gateway.mapper.ModelMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * ModelService 单元测试
 * 覆盖：
 * - 自添加 / 继承 模式下 getChannelRels 的解析
 * - 多级继承的递归解析与环检测
 * - 模式切换（self_add ↔ inherit）的副作用
 * - 被继承模型不可删除的级联保护
 * - 继承模式下 add/remove/sort 的禁用
 */
class ModelServiceTest {

    private ModelMapper modelMapper;
    private ModelChannelRelMapper relMapper;
    private ChannelModelMapper channelModelMapper;
    private ChannelMapper channelMapper;
    private CircuitBreakerConfigMapper circuitBreakerConfigMapper;
    private ChannelService channelService;
    private ModelService service;

    @BeforeEach
    void setUp() {
        modelMapper = mock(ModelMapper.class);
        relMapper = mock(ModelChannelRelMapper.class);
        channelModelMapper = mock(ChannelModelMapper.class);
        channelMapper = mock(ChannelMapper.class);
        circuitBreakerConfigMapper = mock(CircuitBreakerConfigMapper.class);
        channelService = mock(ChannelService.class);
        service = new ModelService(modelMapper, relMapper, channelModelMapper, channelMapper,
                circuitBreakerConfigMapper, channelService);
    }

    // ==================== Helpers ====================

    private Model newSelfAddModel(long id, String name) {
        Model m = new Model(name, "", "failover");
        m.setId(id);
        m.setEnabled(1);
        m.setRelMode(Model.RelMode.SELF_ADD);
        return m;
    }

    private Model newInheritModel(long id, String name, long sourceId) {
        Model m = new Model(name, "", "failover");
        m.setId(id);
        m.setEnabled(1);
        m.setRelMode(Model.RelMode.INHERIT);
        m.setInheritFromModelId(sourceId);
        return m;
    }

    private ModelChannelRel newRel(long relId, long modelId, long channelModelId, int sortOrder) {
        ModelChannelRel rel = new ModelChannelRel(modelId, channelModelId);
        rel.setId(relId);
        rel.setSortOrder(sortOrder);
        rel.setEnabled(1);
        rel.setWeight(1);
        return rel;
    }

    private void stubRelLookup(long modelId, List<ModelChannelRel> rels) {
        when(relMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenAnswer(inv -> {
                    LambdaQueryWrapper<ModelChannelRel> w = inv.getArgument(0);
                    // 通过 toString 区分（MyBatis-Plus 的 wrapper 会包含 model_id 条件）
                    String sql = w.toString();
                    // 简化判断：只根据参数 modelId 过滤
                    return rels.stream()
                            .filter(r -> r.getModelId().equals(modelId))
                            .toList();
                });
    }

    // ==================== getChannelRels - self_add ====================

    @Test
    void getChannelRels_selfAddMode_returnsOwnRelsWithChannelInfoFilled() {
        Model model = newSelfAddModel(1L, "self-model");
        ChannelModel cm1 = new ChannelModel(10L, "gpt-4o", "gpt-4o");
        cm1.setId(100L);
        Channel ch1 = new Channel();
        ch1.setId(10L);
        ch1.setName("OpenAI");
        ch1.setChannelType("openai");
        ch1.setEnabled(1);

        ModelChannelRel rel1 = newRel(1000L, 1L, 100L, 0);

        when(modelMapper.selectById(1L)).thenReturn(model);
        when(relMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(rel1));
        when(channelModelMapper.selectById(100L)).thenReturn(cm1);
        when(channelMapper.selectById(10L)).thenReturn(ch1);

        List<ModelChannelRel> result = service.getChannelRels(1L);

        assertThat(result).hasSize(1);
        ModelChannelRel got = result.get(0);
        assertThat(got.getChannelModelName()).isEqualTo("gpt-4o");
        assertThat(got.getChannelName()).isEqualTo("OpenAI");
        assertThat(got.getChannelType()).isEqualTo("openai");
        assertThat(got.getChannelId()).isEqualTo(10L);
        assertThat(got.getChannelEnabled()).isEqualTo(1);
    }

    // ==================== getChannelRels - inherit ====================

    @Test
    void getChannelRels_inheritMode_returnsSourceRelsNotOwn() {
        Model child = newInheritModel(2L, "premium", 1L);
        Model source = newSelfAddModel(1L, "gpt-4o");
        // 源模型有 1 个 rel，子模型有 0 个 rel
        ModelChannelRel sourceRel = newRel(1000L, 1L, 100L, 0);
        ChannelModel cm = new ChannelModel(10L, "gpt-4o", "gpt-4o");
        cm.setId(100L);
        Channel ch = new Channel();
        ch.setId(10L);
        ch.setName("OpenAI");
        ch.setEnabled(1);

        // 由于继承会递归：第一次 modelMapper.selectById(2L) 返回 child
        // 第二次 modelMapper.selectById(1L) 返回 source
        // 第三次再查 rels 用 modelId=1L
        when(modelMapper.selectById(2L)).thenReturn(child);
        when(modelMapper.selectById(1L)).thenReturn(source);
        when(relMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenAnswer(inv -> List.of(sourceRel));
        when(channelModelMapper.selectById(100L)).thenReturn(cm);
        when(channelMapper.selectById(10L)).thenReturn(ch);

        List<ModelChannelRel> result = service.getChannelRels(2L);

        // 应返回源模型的 rels（不是子模型自有的）
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getModelId()).isEqualTo(1L); // 源模型的 id
        assertThat(result.get(0).getChannelModelName()).isEqualTo("gpt-4o");
    }

    @Test
    void getChannelRels_inheritChain_resolvesRecursively() {
        // A (inherit) -> B (inherit) -> C (self_add with 1 rel)
        Model a = newInheritModel(3L, "A", 2L);
        Model b = newInheritModel(2L, "B", 1L);
        Model c = newSelfAddModel(1L, "C");
        ModelChannelRel cRel = newRel(1000L, 1L, 100L, 0);
        ChannelModel cm = new ChannelModel(10L, "gpt-4o", "gpt-4o");
        cm.setId(100L);
        Channel ch = new Channel();
        ch.setId(10L);
        ch.setName("OpenAI");
        ch.setEnabled(1);

        when(modelMapper.selectById(3L)).thenReturn(a);
        when(modelMapper.selectById(2L)).thenReturn(b);
        when(modelMapper.selectById(1L)).thenReturn(c);
        when(relMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenAnswer(inv -> List.of(cRel));
        when(channelModelMapper.selectById(100L)).thenReturn(cm);
        when(channelMapper.selectById(10L)).thenReturn(ch);

        List<ModelChannelRel> result = service.getChannelRels(3L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getChannelModelName()).isEqualTo("gpt-4o");
    }

    @Test
    void getChannelRels_inheritCycle_returnsEmptyAndDoesNotStackOverflow() {
        // A (inherit) -> B (inherit) -> A 形成环
        Model a = newInheritModel(1L, "A", 2L);
        Model b = newInheritModel(2L, "B", 1L);
        when(modelMapper.selectById(1L)).thenReturn(a);
        when(modelMapper.selectById(2L)).thenReturn(b);

        List<ModelChannelRel> result = service.getChannelRels(1L);

        assertThat(result).isEmpty();
    }

    @Test
    void getChannelRels_inheritSourceDeleted_returnsEmpty() {
        Model child = newInheritModel(2L, "premium", 1L);
        when(modelMapper.selectById(2L)).thenReturn(child);
        when(modelMapper.selectById(1L)).thenReturn(null);

        List<ModelChannelRel> result = service.getChannelRels(2L);

        assertThat(result).isEmpty();
    }

    // ==================== setRelMode ====================

    @Nested
    class SetRelMode {

        @Test
        void selfAddToInherit_deletesOwnRelsAndSetsInheritSource() {
            Model model = newSelfAddModel(1L, "m");
            when(modelMapper.selectById(1L)).thenReturn(model);
            when(modelMapper.selectById(99L)).thenReturn(newSelfAddModel(99L, "source"));
            // rels owned by model 1L should be deleted
            when(relMapper.delete(any(LambdaQueryWrapper.class))).thenReturn(2);

            Model updated = service.setRelMode(1L, "inherit", 99L);

            // 验证删除自己 rels 被调用一次
            verify(relMapper, times(1)).delete(any(LambdaQueryWrapper.class));

            // 验证更新字段
            assertThat(updated.getRelMode()).isEqualTo("inherit");
            assertThat(updated.getInheritFromModelId()).isEqualTo(99L);
            verify(modelMapper).updateById(model);
        }

        @Test
        void inheritToSelfAdd_copiesSourceRelsAsOwnRels() {
            // 源模型有 2 个 rel
            Model source = newSelfAddModel(99L, "source");
            ModelChannelRel sr1 = newRel(9001L, 99L, 1001L, 0);
            ModelChannelRel sr2 = newRel(9002L, 99L, 1002L, 1);
            ChannelModel cm1 = new ChannelModel(10L, "gpt-4o", "gpt-4o");
            cm1.setId(1001L);
            Channel ch1 = new Channel();
            ch1.setId(10L);
            ch1.setName("OpenAI");
            ch1.setEnabled(1);
            ChannelModel cm2 = new ChannelModel(20L, "claude", "claude");
            cm2.setId(1002L);
            Channel ch2 = new Channel();
            ch2.setId(20L);
            ch2.setName("Anthropic");
            ch2.setEnabled(1);

            // 子模型当前是 inherit 模式
            Model child = newInheritModel(1L, "child", 99L);
            when(modelMapper.selectById(1L)).thenReturn(child);
            when(modelMapper.selectById(99L)).thenReturn(source);
            // 解析源 rels：第二次会按 modelId=99L 查
            when(relMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenAnswer(inv -> List.of(sr1, sr2));
            when(channelModelMapper.selectById(1001L)).thenReturn(cm1);
            when(channelModelMapper.selectById(1002L)).thenReturn(cm2);
            when(channelMapper.selectById(10L)).thenReturn(ch1);
            when(channelMapper.selectById(20L)).thenReturn(ch2);

            Model updated = service.setRelMode(1L, "self_add", null);

            // 验证插入了 2 个新 rels，modelId 改为子模型自己
            ArgumentCaptor<ModelChannelRel> insertCaptor = ArgumentCaptor.forClass(ModelChannelRel.class);
            verify(relMapper, times(2)).insert(insertCaptor.capture());
            List<ModelChannelRel> inserted = insertCaptor.getAllValues();
            assertThat(inserted).hasSize(2);
            assertThat(inserted.get(0).getModelId()).isEqualTo(1L);
            assertThat(inserted.get(0).getChannelModelId()).isEqualTo(1001L);
            assertThat(inserted.get(0).getSortOrder()).isEqualTo(0);
            assertThat(inserted.get(1).getModelId()).isEqualTo(1L);
            assertThat(inserted.get(1).getChannelModelId()).isEqualTo(1002L);
            assertThat(inserted.get(1).getSortOrder()).isEqualTo(1);

            // 验证更新
            assertThat(updated.getRelMode()).isEqualTo("self_add");
            assertThat(updated.getInheritFromModelId()).isNull();
        }

        @Test
        void selfAddToSelfAdd_doesNothingToRels() {
            Model model = newSelfAddModel(1L, "m");
            when(modelMapper.selectById(1L)).thenReturn(model);

            Model updated = service.setRelMode(1L, "self_add", null);

            verify(relMapper, never()).delete(any(LambdaQueryWrapper.class));
            verify(relMapper, never()).insert(any(ModelChannelRel.class));
            assertThat(updated.getRelMode()).isEqualTo("self_add");
        }

        @Test
        void inheritToInherit_doesNotDeleteRels_justUpdatesSource() {
            Model model = newInheritModel(1L, "m", 99L);
            when(modelMapper.selectById(1L)).thenReturn(model);
            when(modelMapper.selectById(100L)).thenReturn(newSelfAddModel(100L, "new-source"));

            Model updated = service.setRelMode(1L, "inherit", 100L);

            verify(relMapper, never()).delete(any(LambdaQueryWrapper.class));
            verify(relMapper, never()).insert(any(ModelChannelRel.class));
            assertThat(updated.getInheritFromModelId()).isEqualTo(100L);
        }

        @Test
        void setRelMode_selfInherit_throws() {
            Model model = newSelfAddModel(1L, "m");
            when(modelMapper.selectById(1L)).thenReturn(model);

            assertThatThrownBy(() -> service.setRelMode(1L, "inherit", 1L))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("自身");
        }

        @Test
        void setRelMode_inheritWithoutSource_throws() {
            Model model = newSelfAddModel(1L, "m");
            when(modelMapper.selectById(1L)).thenReturn(model);

            assertThatThrownBy(() -> service.setRelMode(1L, "inherit", null))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("必须指定源模型");
        }

        @Test
        void setRelMode_inheritSourceNotFound_throws() {
            Model model = newSelfAddModel(1L, "m");
            when(modelMapper.selectById(1L)).thenReturn(model);
            when(modelMapper.selectById(99L)).thenReturn(null);

            assertThatThrownBy(() -> service.setRelMode(1L, "inherit", 99L))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("源模型不存在");
        }

        @Test
        void setRelMode_unknownMode_throws() {
            Model model = newSelfAddModel(1L, "m");
            when(modelMapper.selectById(1L)).thenReturn(model);

            assertThatThrownBy(() -> service.setRelMode(1L, "magic", null))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("未知的关联模式");
        }

        @Test
        void setRelMode_createsCycle_throws() {
            // 现状：1L 继承 2L，2L 继承 3L
            // 现在想把 3L 改为继承 1L → 会形成环 1→2→3→1
            Model m1 = newInheritModel(1L, "A", 2L);
            Model m2 = newInheritModel(2L, "B", 3L);
            Model m3 = newSelfAddModel(3L, "C");
            when(modelMapper.selectById(3L)).thenReturn(m3);
            when(modelMapper.selectById(2L)).thenReturn(m2);
            when(modelMapper.selectById(1L)).thenReturn(m1);
            when(modelMapper.selectById(99L)).thenReturn(newSelfAddModel(99L, "alt-source"));

            // 先把 3L 设为继承 2L 没问题（检测源 2L 链：2L→3L，3L 已在 visited）
            // 现在让 3L 继承 1L：1L 自身是 inherit 模式且 inheritFromModelId=2L
            // 从 sourceModelId=1L 开始检测：1L 是 inherit，next=2L
            // 2L 是 inherit，next=3L，3L 在 visited（开始时含 3L）→ 环
            assertThatThrownBy(() -> service.setRelMode(3L, "inherit", 1L))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("循环");
        }
    }

    // ==================== delete ====================

    @Test
    void delete_modelIsInheritedBy_throwsAndDoesNotDelete() {
        Model self = newSelfAddModel(1L, "source");
        Model child = newInheritModel(2L, "child", 1L);
        when(modelMapper.selectById(1L)).thenReturn(self);
        when(modelMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenAnswer(inv -> {
                    LambdaQueryWrapper<Model> w = inv.getArgument(0);
                    return List.of(child);
                });

        assertThatThrownBy(() -> service.delete(1L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("正被以下模型继承");

        verify(modelMapper, never()).deleteById(anyLong());
    }

    @Test
    void delete_noInheritor_cascadesRelsAndCircuitBreaker() {
        Model self = newSelfAddModel(1L, "lone");
        when(modelMapper.selectById(1L)).thenReturn(self);
        when(modelMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of());

        service.delete(1L);

        verify(relMapper).delete(any(LambdaQueryWrapper.class));
        verify(circuitBreakerConfigMapper).delete(any(LambdaQueryWrapper.class));
        verify(modelMapper).deleteById(1L);
    }

    // ==================== addChannelRel / batchAddChannelRels (继承模式拒绝) ====================

    @Test
    void addChannelRel_inheritMode_throws() {
        Model inheritModel = newInheritModel(1L, "m", 99L);
        when(modelMapper.selectById(1L)).thenReturn(inheritModel);

        ModelChannelRel rel = new ModelChannelRel(1L, 100L);

        assertThatThrownBy(() -> service.addChannelRel(rel))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("继承模式");
    }

    @Test
    void batchAddChannelRels_inheritMode_throws() {
        Model inheritModel = newInheritModel(1L, "m", 99L);
        when(modelMapper.selectById(1L)).thenReturn(inheritModel);

        assertThatThrownBy(() -> service.batchAddChannelRels(1L, List.of(100L, 200L)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("继承模式");
    }

    @Test
    void removeChannelRel_relBelongsToInheritModel_throws() {
        Model inheritModel = newInheritModel(1L, "m", 99L);
        ModelChannelRel rel = newRel(10L, 1L, 100L, 0);
        when(relMapper.selectById(10L)).thenReturn(rel);
        when(modelMapper.selectById(1L)).thenReturn(inheritModel);

        assertThatThrownBy(() -> service.removeChannelRel(10L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("继承模式");
    }

    @Test
    void updateChannelRelSortOrder_relBelongsToInheritModel_throws() {
        Model inheritModel = newInheritModel(1L, "m", 99L);
        ModelChannelRel rel = newRel(10L, 1L, 100L, 0);
        when(relMapper.selectById(10L)).thenReturn(rel);
        when(modelMapper.selectById(1L)).thenReturn(inheritModel);

        assertThatThrownBy(() -> service.updateChannelRelSortOrder(10L, 5))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("继承模式");
    }

    @Test
    void updateChannelRelSortOrders_relBelongsToInheritModel_throws() {
        Model inheritModel = newInheritModel(1L, "m", 99L);
        ModelChannelRel rel = newRel(10L, 1L, 100L, 0);
        when(relMapper.selectById(10L)).thenReturn(rel);
        when(modelMapper.selectById(1L)).thenReturn(inheritModel);

        assertThatThrownBy(() -> service.updateChannelRelSortOrders(List.of(10L)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("继承模式");
    }

    @Test
    void removeChannelRel_relNotFound_throws() {
        when(relMapper.selectById(10L)).thenReturn(null);

        assertThatThrownBy(() -> service.removeChannelRel(10L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("关联不存在");
    }

    // ==================== listInheritableModels ====================

    @Test
    void listInheritableModels_buildsCorrectQuery() {
        // 验证方法被调用：实际 SQL 过滤由 MyBatis-Plus 负责，不在单元测试范围内
        when(modelMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenAnswer(inv -> {
                    // 简单校验 wrapper 不为 null
                    Object wrapper = inv.getArgument(0);
                    assertThat(wrapper).isNotNull();
                    return List.of(newSelfAddModel(2L, "b"));
                });

        List<Model> result = service.listInheritableModels(1L);

        verify(modelMapper).selectList(any(LambdaQueryWrapper.class));
        assertThat(result).hasSize(1);
    }
}
