package com.fc.v2.service;

import com.fc.v2.mapper.auto.TFlogCloseCardMapper;
import com.fc.v2.model.auto.TFlogCloseCard;
import com.fc.v2.service.impl.TFlogCloseCardServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.Serializable;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 伐区勾销事务卡流转复现测试：站里报上来的四桩毛病逐条稳稳复现。
 * 数据层用内存底账模拟（selectById 返回副本、updateById/deleteById 落账），不落真实库。
 */
class TFlogCloseCardServiceImplTest {

    /** 内存底账：模拟 t_flog_close_card */
    private final Map<Long, TFlogCloseCard> db = new HashMap<Long, TFlogCloseCard>();

    private TFlogCloseCardServiceImpl service;

    private static TFlogCloseCard copyOf(TFlogCloseCard r) {
        if (r == null) {
            return null;
        }
        TFlogCloseCard c = new TFlogCloseCard();
        c.setId(r.getId());
        c.setBizNo(r.getBizNo());
        c.setStage(r.getStage());
        c.setStatus(r.getStatus());
        c.setContent(r.getContent());
        c.setLastAction(r.getLastAction());
        c.setDelFlag(r.getDelFlag());
        c.setCreateBy(r.getCreateBy());
        c.setCreateTime(r.getCreateTime());
        c.setUpdateBy(r.getUpdateBy());
        c.setUpdateTime(r.getUpdateTime());
        c.setRemark(r.getRemark());
        return c;
    }

    @BeforeEach
    void setUp() {
        db.clear();
        TFlogCloseCardMapper mapper = Mockito.mock(TFlogCloseCardMapper.class);
        when(mapper.selectById(any(Serializable.class))).thenAnswer(inv -> {
            Long id = ((Number) inv.getArgument(0)).longValue();
            return copyOf(db.get(id));
        });
        when(mapper.updateById(any(TFlogCloseCard.class))).thenAnswer(inv -> {
            TFlogCloseCard r = inv.getArgument(0);
            if (!db.containsKey(r.getId())) {
                return 0;
            }
            db.put(r.getId(), copyOf(r));
            return 1;
        });
        when(mapper.deleteById(any(Serializable.class))).thenAnswer(inv -> {
            Long id = ((Number) inv.getArgument(0)).longValue();
            return db.remove(id) != null ? 1 : 0;
        });
        service = new TFlogCloseCardServiceImpl();
        ReflectionTestUtils.setField(service, "flogCloseCardMapper", mapper);
    }

    /** 造一张在办卡：编号齐全、未删除 */
    private Long seed(int stage, int status) {
        long id = 1000L + db.size();
        TFlogCloseCard r = new TFlogCloseCard();
        r.setId(id);
        r.setBizNo("GX-2026-" + id);
        r.setStage(stage);
        r.setStatus(status);
        r.setContent("留档记事" + id);
        r.setLastAction("上一道口子");
        r.setDelFlag(0);
        r.setCreateBy("seed");
        r.setCreateTime(new Date());
        db.put(id, r);
        return id;
    }

    /* ---------- 投诉一：连着点两下推进，环节一口气走了两格 ---------- */

    @Test
    void advance_doubleClickSameAction_movesOnlyOneStage() {
        Long id = seed(0, 0);
        TFlogCloseCard first = service.advance(id, "受理通过");
        assertNotNull(first);
        assertEquals(1, first.getStage().intValue());

        TFlogCloseCard second = service.advance(id, "受理通过"); // 连点：同一动作原样再来一次
        assertNull(second, "连点同一动作应被拒");
        assertEquals(1, db.get(id).getStage().intValue(), "环节不应一口气走两格");
    }

    @Test
    void advance_nextStageWithNewAction_stillAllowed() {
        Long id = seed(0, 0);
        service.advance(id, "受理通过");
        TFlogCloseCard r = service.advance(id, "计量完成");
        assertNotNull(r, "换了动作的下一档推进应放行");
        assertEquals(2, r.getStage().intValue());
    }

    /* ---------- 投诉二：回退时一口气退回了起点，中间几段全丢 ---------- */

    @Test
    void rollback_goesBackExactlyOneStage() {
        Long id = seed(2, 1);
        TFlogCloseCard r = service.rollback(id, "核株有误退回");
        assertNotNull(r);
        assertEquals(1, r.getStage().intValue(), "回退应只退一档");
        assertEquals(1, db.get(id).getStage().intValue(), "中间段次不应被跳没");
        assertEquals(1, r.getStatus().intValue(), "退回中段仍应在办");
    }

    /* ---------- 投诉三：已经了断的单子内容竟还能改 ---------- */

    @Test
    void updateContent_terminalCard_rejected() {
        Long id = seed(3, 2);
        boolean ok = service.updateContent(id, "事后改口");
        assertFalse(ok, "已了断单据不得再改内容");
        assertEquals("留档记事" + id, db.get(id).getContent(), "留档应与实情一致");
    }

    /* ---------- 投诉四：收尾的单子被抹走，台账对不上 ---------- */

    @Test
    void remove_terminalCard_rejected() {
        Long id = seed(3, 2);
        boolean ok = service.remove(id);
        assertFalse(ok, "已了断单据不得删除");
        assertTrue(db.containsKey(id), "台账单据不应被抹走");
    }

    /* ---------- 既有好行为：钉死防回归 ---------- */

    @Test
    void advance_singleClick_movesOneStage() {
        Long id = seed(0, 0);
        TFlogCloseCard r = service.advance(id, "受理通过");
        assertNotNull(r);
        assertEquals(1, r.getStage().intValue());
        assertEquals(1, r.getStatus().intValue());
        assertEquals("受理通过", r.getLastAction());
    }

    @Test
    void advance_terminalCard_rejected() {
        Long id = seed(3, 2);
        assertNull(service.advance(id, "再推一把"), "已了断单据不得再流转");
        assertEquals(3, db.get(id).getStage().intValue());
    }

    @Test
    void rollback_fromFirstStage_rejected() {
        Long id = seed(0, 1);
        assertNull(service.rollback(id, "退无可退"));
        assertEquals(0, db.get(id).getStage().intValue());
    }

    @Test
    void rollback_doubleClickSameAction_rejected() {
        Long id = seed(2, 1);
        assertNotNull(service.rollback(id, "核株有误退回"));
        assertNull(service.rollback(id, "核株有误退回"), "同一动作连续回退应被拒");
        assertEquals(1, db.get(id).getStage().intValue());
    }

    @Test
    void updateContent_activeCard_allowed() {
        Long id = seed(1, 1);
        assertTrue(service.updateContent(id, "补充记事"));
        assertEquals("补充记事", db.get(id).getContent());
    }

    @Test
    void remove_activeCard_allowed() {
        Long id = seed(1, 1);
        assertTrue(service.remove(id));
        assertFalse(db.containsKey(id));
    }
}
