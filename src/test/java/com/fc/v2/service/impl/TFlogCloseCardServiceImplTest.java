package com.fc.v2.service.impl;

import com.fc.v2.mapper.auto.TFlogCloseCardMapper;
import com.fc.v2.model.auto.TFlogCloseCard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * 伐区勾销事务卡流转复现测试。
 *
 * 用内存 Map 模拟库表，覆盖勾销岗报修的四个场景：
 *  1. 连点两下推进，环节走了两格
 *  2. 回退一口气退回起点，中间段次丢失
 *  3. 已了断的单子内容还能改
 *  4. 已了断的单子被删除
 */
public class TFlogCloseCardServiceImplTest {

    /** 内存库：id -> 单据，模拟两次请求之间库里的真实状态 */
    private final Map<Long, TFlogCloseCard> db = new HashMap<Long, TFlogCloseCard>();

    private TFlogCloseCardServiceImpl service;

    @BeforeEach
    public void setUp() {
        TFlogCloseCardMapper mapper = Mockito.mock(TFlogCloseCardMapper.class);
        when(mapper.selectById(anyLong())).thenAnswer(inv -> db.get((Long) inv.getArgument(0)));
        when(mapper.updateById(any(TFlogCloseCard.class))).thenAnswer(inv -> {
            TFlogCloseCard r = inv.getArgument(0);
            db.put(r.getId(), r);
            return 1;
        });
        when(mapper.deleteById(anyLong())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            return db.remove(id) != null ? 1 : 0;
        });
        service = new TFlogCloseCardServiceImpl();
        ReflectionTestUtils.setField(service, "flogCloseCardMapper", mapper);
    }

    private TFlogCloseCard newCard(long id, int stage, int status) {
        TFlogCloseCard r = new TFlogCloseCard();
        r.setId(id);
        r.setBizNo("GX-2026-" + id);
        r.setStage(stage);
        r.setStatus(status);
        r.setDelFlag(0);
        db.put(id, r);
        return r;
    }

    // ---------- 报修场景 1：连点两下推进 ----------

    @Test
    public void advanceTwiceWithSameRemark_movesOnlyOneStage() {
        newCard(1L, 0, 1);
        // 连点：两次请求参数完全一致
        assertNotNull(service.advance(1L, "提交计量"));
        service.advance(1L, "提交计量");
        assertEquals(1, db.get(1L).getStage().intValue(),
                "同一动作连点两次只能推进一档，第二次应被判重拒绝");
    }

    // ---------- 报修场景 2：回退退过头 ----------

    @Test
    public void rollbackFromMiddleStage_goesBackExactlyOneStage() {
        newCard(2L, 2, 1);
        TFlogCloseCard r = service.rollback(2L, "复核不通过");
        assertNotNull(r);
        assertEquals(1, db.get(2L).getStage().intValue(),
                "从环节2回退应到环节1，不得直接退回起点");
        assertEquals(1, db.get(2L).getStatus().intValue(), "回退后应仍在办");
    }

    // ---------- 报修场景 3：已了断单子内容被改 ----------

    @Test
    public void updateContentOnTerminalCard_isRejected() {
        TFlogCloseCard r = newCard(3L, 3, 2);
        r.setContent("原始勾销记事");
        assertFalse(service.updateContent(3L, "事后篡改"),
                "已了断单据不得再改内容");
        assertEquals("原始勾销记事", db.get(3L).getContent(), "留档内容不得变动");
    }

    // ---------- 报修场景 4：已了断单子被删除 ----------

    @Test
    public void removeOnTerminalCard_isRejected() {
        newCard(4L, 3, 2);
        assertFalse(service.remove(4L), "已了断单据不得删除");
        assertNotNull(db.get(4L), "已了断单据必须留档");
    }

    // ---------- 既有正常行为（修复后不得破坏） ----------

    @Test
    public void advanceOnce_movesOneStage() {
        newCard(11L, 0, 1);
        TFlogCloseCard r = service.advance(11L, "提交计量");
        assertNotNull(r);
        assertEquals(1, db.get(11L).getStage().intValue());
        assertEquals(1, db.get(11L).getStatus().intValue());
    }

    @Test
    public void advanceToLastStage_marksTerminal() {
        newCard(12L, 2, 1);
        TFlogCloseCard r = service.advance(12L, "复核通过");
        assertNotNull(r);
        assertEquals(3, db.get(12L).getStage().intValue());
        assertEquals(2, db.get(12L).getStatus().intValue(), "走到末环节即已了断");
    }

    @Test
    public void advanceOnTerminalCard_isRejected() {
        newCard(13L, 3, 2);
        assertNull(service.advance(13L, "再推一次"));
        assertEquals(3, db.get(13L).getStage().intValue());
    }

    @Test
    public void rollbackFromFirstStage_isRejected() {
        newCard(14L, 0, 1);
        assertNull(service.rollback(14L, "退无可退"));
        assertEquals(0, db.get(14L).getStage().intValue());
    }

    @Test
    public void updateContentOnActiveCard_works() {
        TFlogCloseCard r = newCard(15L, 1, 1);
        r.setContent("旧记事");
        assertTrue(service.updateContent(15L, "补充记事"));
        assertEquals("补充记事", db.get(15L).getContent());
    }

    @Test
    public void removeOnActiveCard_works() {
        newCard(16L, 1, 1);
        assertTrue(service.remove(16L));
        assertNull(db.get(16L));
    }
}
