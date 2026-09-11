package com.example.personalledger

/**
 * 首页记录列表的分页计算。
 *
 * 首页只从数据库读取当前页的数据（而不是整表），因此记录很多时也能快速打开；
 * 这里集中存放每页条数与页码换算规则，纯函数实现便于单元测试。
 */
object LedgerPaging {

    /** 首页记录列表每页最多展示的记录条数。 */
    const val PAGE_SIZE = 10

    /** 由总条数计算总页数；没有记录时也保留第 1 页，便于界面展示空状态。 */
    fun pageCount(totalCount: Int, pageSize: Int = PAGE_SIZE): Int {
        if (totalCount <= 0 || pageSize <= 0) return 1
        return (totalCount + pageSize - 1) / pageSize
    }

    /** 把页码收敛到 `[1, 总页数]`，用于删除 / 导入导致总页数变少后自动回到最后一页。 */
    fun clampPage(page: Int, totalCount: Int, pageSize: Int = PAGE_SIZE): Int =
        page.coerceIn(1, pageCount(totalCount, pageSize))

    /** 第 [page] 页在数据库中的起始偏移量（从第 1 页开始计）。 */
    fun offsetOf(page: Int, pageSize: Int = PAGE_SIZE): Int =
        (page.coerceAtLeast(1) - 1) * pageSize
}
