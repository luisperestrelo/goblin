package com.luisperestrelo.goblin.di

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import com.luisperestrelo.goblin.data.api.AuthorizationInterceptor
import com.luisperestrelo.goblin.data.api.EnableBankingApi
import com.luisperestrelo.goblin.data.credentials.CredentialsStore
import com.luisperestrelo.goblin.data.db.AccountDao
import com.luisperestrelo.goblin.data.db.BalanceSnapshotDao
import com.luisperestrelo.goblin.data.db.GoblinDatabase
import com.luisperestrelo.goblin.data.db.SyncLogDao
import com.luisperestrelo.goblin.data.db.TransactionDao
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CredentialsModule {

    @Provides
    @Singleton
    fun provideCredentialsStore(@ApplicationContext context: Context): CredentialsStore =
        CredentialsStore(context)
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json { ignoreUnknownKeys = true }

    @Provides
    @Singleton
    fun provideOkHttpClient(credentialsStore: CredentialsStore): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(AuthorizationInterceptor(credentialsStore))
            .build()

    @Provides
    @Singleton
    fun provideEnableBankingApi(client: OkHttpClient, json: Json): EnableBankingApi =
        Retrofit.Builder()
            .baseUrl("https://api.enablebanking.com/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(EnableBankingApi::class.java)
}

@Module
@InstallIn(SingletonComponent::class)
object WorkModule {

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): GoblinDatabase =
        Room.databaseBuilder(context, GoblinDatabase::class.java, "goblin.db")
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun provideAccountDao(db: GoblinDatabase): AccountDao = db.accountDao()

    @Provides
    fun provideTransactionDao(db: GoblinDatabase): TransactionDao = db.transactionDao()

    @Provides
    fun provideBalanceSnapshotDao(db: GoblinDatabase): BalanceSnapshotDao = db.balanceSnapshotDao()

    @Provides
    fun provideSyncLogDao(db: GoblinDatabase): SyncLogDao = db.syncLogDao()
}
