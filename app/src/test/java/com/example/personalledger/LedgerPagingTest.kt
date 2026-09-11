package com.example.personalledger

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 首页记录分页计算的 JVM 单元测试。
 *
 * 首页每页最多展示 [LedgerPaging.PAGE_SIZE] 条记录，页码与偏移量的换算都收敛在这里。
 */
class LedgerPagingTest {

    @Test
    fun pageSize_isTenRecordsPerPage() {
        assertEquals(10, LedgerPaging.PAGE_SIZE)
    }

    @Test
    fun pageCount_roundsUpPartialPage() {
        assertEquals(1, LedgerPaging.pageCount(0))
        assertEquals(1, LedgerPaging.pageCount(1))
        assertEquals(1, LedgerPaging.pageCount(10))
        assertEquals(2, LedgerPaging.pageCount(11))
        assertEquals(3, LedgerPaging.pageCount(25))
        assertEquals(3, LedgerPaging.pageCount(30))
        assertEquals(4, LedgerPaging.pageCount(31))
    }

    @Test
    fun offsetOf_skipsPreviousPages() {
        assertEquals(0, LedgerPaging.offsetOf(1))
        assertEquals(10, LedgerPaging.offsetOf(2))
        assertEquals(20, LedgerPaging.offsetOf(3))
        // 非法页码兜底：不会算出负数偏移
        assertEquals(0, LedgerPaging.offsetOf(0))
    }

    @Test
    fun offsetOf_followsCustomPageSize() {
        assertEquals(0, LedgerPaging.offsetOf(1, pageSize = 5))
        assertEquals(5, LedgerPaging.offsetOf(2, pageSize = 5))
        // 每页 5 条时，11 条记录需要 3 页
        assertEquals(3, LedgerPaging.pageCount(11, pageSize = 5))
    }

    @Test
    fun clampPage_keepsPageWithinRange() {
        assertEquals(2, LedgerPaging.clampPage(2, totalCount = 25))
        assertEquals(1, LedgerPaging.clampPage(0, totalCount = 25))
        // 记录被删到只剩 10 条时，第 3 页应收敛到第 1 页
        assertEquals(1, LedgerPaging.clampPage(3, totalCount = 10))
        assertEquals(3, LedgerPaging.clampPage(9, totalCount = 25))
        // 记录清空后仍保留第 1 页，便于展示空状态
        assertEquals(1, LedgerPaging.clampPage(4, totalCount = 0))
    }
}
