package com.luisperestrelo.goblin.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        AccountEntity::class,
        TransactionEntity::class,
        BalanceSnapshotEntity::class,
        SyncLogEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class GoblinDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun transactionDao(): TransactionDao
    abstract fun balanceSnapshotDao(): BalanceSnapshotDao
    abstract fun syncLogDao(): SyncLogDao
}
