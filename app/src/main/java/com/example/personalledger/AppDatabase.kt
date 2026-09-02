package com.example.personalledger

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [LedgerEntryEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {

    abstract fun ledgerEntryDao(): LedgerEntryDao

    companion object {
        private const val DB_NAME = "personalledger.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME
                ).build().also { INSTANCE = it }
            }
    }
}
