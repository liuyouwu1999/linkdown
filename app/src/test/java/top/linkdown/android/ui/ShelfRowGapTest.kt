package top.linkdown.android.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 书距纯函数直测（bookRowGap）：富余宽度均分进书间缝撑满搁板、上限封顶防"两本吊两头"、
 * 单本返回基础缝、书宽超排宽下限回基础缝。
 */
class ShelfRowGapTest {

    @Test fun `单本或空排返回基础缝`() {
        assertEquals(10.dp, bookRowGap(0, 340.dp, 100.dp))
        assertEquals(10.dp, bookRowGap(1, 340.dp, 100.dp))
    }

    @Test fun `满排无富余书距即基础缝`() {
 // rowWidth = 3×书宽 + 2×基础缝 → leftover=0
        assertEquals(10.dp, bookRowGap(3, 320.dp, 100.dp))
    }

    @Test fun `富余宽度均分进书缝`() {
 // leftover = 360-300-20 = 40 → 10 + 40/2 = 30dp
        assertEquals(30.dp, bookRowGap(3, 360.dp, 100.dp))
    }

    @Test fun `富余过大封顶不吊两头`() {
 // 两本书大排宽：leftover=500-200-10=290 → 300dp → 封顶 32dp
        assertEquals(32.dp, bookRowGap(2, 500.dp, 100.dp))
    }

    @Test fun `书宽超排宽回退基础缝不负缝`() {
 // leftover = 340-400-10 = -70 → 10-70 <0 → 下限 10dp
        assertEquals(10.dp, bookRowGap(2, 340.dp, 200.dp))
    }
}
