package com.example.personalledger

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 本地数据库。
 *
 * **升级约定（重要）**：任何改动实体（字段、索引、表）的提交，都必须
 * 1. 把 [Database.version] 加一；
 * 2. 在 [MIGRATIONS] 里补一条 `Migration(旧版本, 新版本)`，写清楚从旧结构到新结构的 SQL；
 * 3. 构建后把 KSP 生成的 `app/schemas/<版本>.json` 一并提交，供代码评审比对结构变化。
 *
 * 缺少迁移会导致老用户升级后启动即崩（`IllegalStateException: A migration from X to Y was
 * required but not found`），因此迁移与实体改动必须成对出现。
 */
@Database(
    entities = [LedgerEntryEntity::class, BudgetEntity::class, ChatMessageEntity::class],
    version = 4,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun ledgerEntryDao(): LedgerEntryDao

    abstract fun budgetDao(): BudgetDao

    abstract fun chatMessageDao(): ChatMessageDao

    companion object {
        private const val DB_NAME = "personalledger.db"

        /**
         * v1 → v2：为 `ledger_entries` 补索引。
         *
         * v1 的表只有 `PRIMARY KEY(id)`，而首页分页、收支聚合、报表分组都依赖
         * `timeMillis` / `isExpense` / `categoryName`，全表扫描 + 临时排序会随记录数线性变慢。
         *
         * 索引名沿用 Room 的生成规则 `index_<表名>_<列名...>`，必须与
         * [LedgerEntryEntity] 上的 `@Index` 完全一致，否则 Room 的 schema 校验会失败。
         * 索引只影响查询计划，不改变任何既有数据。
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_ledger_entries_timeMillis` " +
                        "ON `ledger_entries` (`timeMillis`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_ledger_entries_isExpense_timeMillis` " +
                        "ON `ledger_entries` (`isExpense`, `timeMillis`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_ledger_entries_categoryName_timeMillis` " +
                        "ON `ledger_entries` (`categoryName`, `timeMillis`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_ledger_entries_isExpense_categoryName_timeMillis` " +
                        "ON `ledger_entries` (`isExpense`, `categoryName`, `timeMillis`)"
                )
            }
        }

        /**
         * v2 → v3：新增 `budgets` 表，把预算从「DataStore 里的单个 Double」升级为结构化规则
         * （可按分类、可选月/周周期）。
         *
         * 只建表、不动既有表；`ledger_entries` 的数据完全不受影响。
         * 旧的 DataStore 月度总预算由 [LedgerRepository] 做一次性搬迁（跨存储，不适合放在 SQL 迁移里）。
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `budgets` (" +
                        "`periodType` TEXT NOT NULL, " +
                        "`categoryName` TEXT NOT NULL, " +
                        "`limitCents` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`periodType`, `categoryName`))"
                )
            }
        }

        /**
         * v3 → v4：新增 `chat_messages` 表，存放 AI 助手的对话记录与**待确认账目草稿**。
         *
         * 只建表、不动既有表；`ledger_entries` 与 `budgets` 的数据完全不受影响。
         *
         * 建表 SQL 的写法有两个必须踩准的点（都不是手写能靠感觉蒙对的）：
         * 1. `@PrimaryKey(autoGenerate = true)` 的 Long 主键，Room 生成的是
         *    `` `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL ``，
         *    **没有**独立的 `PRIMARY KEY(...)` 尾子句 —— 与 `ledger_entries` 那种
         *    TEXT 主键的写法不同；
         * 2. 可空列 `draftJson` 生成的是 `` `draftJson` TEXT ``，**不带** `NOT NULL`。
         *
         * 因此这里的 SQL 是从构建产出的 `app/schemas/.../4.json` 里逐字抄下来的
         * （只把 `${TABLE_NAME}` 换成表名）。任何一处不一致，Room 的 schema 校验都会在
         * 老用户升级后的首次启动直接抛异常。
         */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `chat_messages` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`role` TEXT NOT NULL, " +
                        "`content` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`draftJson` TEXT)"
                )
            }
        }

        /** 全部迁移，按版本顺序传入 [Room.databaseBuilder]。 */
        private val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME
                )
                    .addMigrations(*MIGRATIONS)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
