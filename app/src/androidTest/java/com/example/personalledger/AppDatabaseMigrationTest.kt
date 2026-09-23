package com.example.personalledger

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room 迁移的仪器化测试（需要真机 / 模拟器，`./gradlew connectedDebugAndroidTest`）。
 *
 * 覆盖两件事：
 * 1. 每一段迁移执行后，数据库结构与导出的 `schemas/<版本>.json` **完全一致**
 *    （`runMigrationsAndValidate` 会做完整 schema 校验，表 / 列 / 主键 / 索引写错都会失败）；
 * 2. 迁移只做增量改动，**既有账目一条都不能少**。
 *
 * 之所以要这些测试：schema 一旦与迁移 SQL 对不上，老用户升级时不会报错而是直接崩溃，
 * 这里把它拦在测试阶段。
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    private val testDbName = "migration-test.db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    private val expectedIndices = listOf(
        "index_ledger_entries_timeMillis",
        "index_ledger_entries_isExpense_timeMillis",
        "index_ledger_entries_categoryName_timeMillis",
        "index_ledger_entries_isExpense_categoryName_timeMillis"
    )

    /** 写入一条 v1 结构的账目记录。 */
    private fun insertLegacyEntry(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO ledger_entries " +
                "(id, amountCents, note, timeMillis, isExpense, categoryName, categoryIconRes) " +
                "VALUES ('legacy-1', 1234, '早餐', 1700000000000, 1, '餐饮', 0)"
        )
    }

    private fun assertLegacyEntryIntact(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.query(
            "SELECT id, amountCents, note, timeMillis, isExpense, categoryName FROM ledger_entries"
        ).use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals("legacy-1", cursor.getString(0))
            assertEquals(1234L, cursor.getLong(1))
            assertEquals("早餐", cursor.getString(2))
            assertEquals(1700000000000L, cursor.getLong(3))
            assertEquals(1, cursor.getInt(4))
            assertEquals("餐饮", cursor.getString(5))
        }
    }

    private fun actualIndexNames(db: androidx.sqlite.db.SupportSQLiteDatabase): Set<String> {
        val names = mutableSetOf<String>()
        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'ledger_entries'"
        ).use { cursor ->
            while (cursor.moveToNext()) names.add(cursor.getString(0))
        }
        return names
    }

    // ---------- v1 → v2：补索引 ----------

    @Test
    fun migrate1To2_addsIndicesAndKeepsExistingRows() {
        helper.createDatabase(testDbName, 1).use { db -> insertLegacyEntry(db) }

        val db = helper.runMigrationsAndValidate(testDbName, 2, true, AppDatabase.MIGRATION_1_2)

        assertLegacyEntryIntact(db)
        val actual = actualIndexNames(db)
        expectedIndices.forEach { name ->
            assertTrue("缺少索引 $name，实际为 $actual", actual.contains(name))
        }
        db.close()
    }

    // ---------- v2 → v3：新增 budgets 表 ----------

    @Test
    fun migrate2To3_createsBudgetsTableAndKeepsExistingRows() {
        helper.createDatabase(testDbName, 2).use { db -> insertLegacyEntry(db) }

        val db = helper.runMigrationsAndValidate(testDbName, 3, true, AppDatabase.MIGRATION_2_3)

        // 账目不受影响，v2 的索引也还在
        assertLegacyEntryIntact(db)
        assertTrue(actualIndexNames(db).containsAll(expectedIndices))

        // budgets 表已建好且为空
        db.query("SELECT COUNT(*) FROM budgets").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }

        db.close()
    }

    @Test
    fun migrate2To3_budgetsPrimaryKeyRejectsDuplicateScope() {
        helper.createDatabase(testDbName, 2).use { db -> insertLegacyEntry(db) }
        val db = helper.runMigrationsAndValidate(testDbName, 3, true, AppDatabase.MIGRATION_2_3)

        db.execSQL("INSERT INTO budgets (periodType, categoryName, limitCents) VALUES ('month', '餐饮', 150000)")
        try {
            // 同一周期 + 同一分类重复插入必须被复合主键拒绝
            db.execSQL("INSERT INTO budgets (periodType, categoryName, limitCents) VALUES ('month', '餐饮', 999999)")
            fail("重复的 (periodType, categoryName) 应被主键约束拒绝")
        } catch (expected: Exception) {
            // 预期行为
        }

        // 不同周期 / 不同分类可以共存；空串表示「不限分类」的总预算
        db.execSQL("INSERT INTO budgets (periodType, categoryName, limitCents) VALUES ('week', '餐饮', 30000)")
        db.execSQL("INSERT INTO budgets (periodType, categoryName, limitCents) VALUES ('month', '', 500000)")

        db.query("SELECT COUNT(*) FROM budgets").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(3, cursor.getInt(0))
        }

        db.close()
    }

    // ---------- v1 → v3：完整链路 ----------

    @Test
    fun migrate1To3_runsTheWholeChain() {
        helper.createDatabase(testDbName, 1).use { db -> insertLegacyEntry(db) }

        val db = helper.runMigrationsAndValidate(
            testDbName,
            3,
            true,
            AppDatabase.MIGRATION_1_2,
            AppDatabase.MIGRATION_2_3
        )

        assertLegacyEntryIntact(db)
        assertTrue(actualIndexNames(db).containsAll(expectedIndices))
        db.query("SELECT COUNT(*) FROM budgets").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }

        db.close()
    }
}
