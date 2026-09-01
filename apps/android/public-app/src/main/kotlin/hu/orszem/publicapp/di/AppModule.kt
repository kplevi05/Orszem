package hu.orszem.publicapp.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import hu.orszem.core.common.DefaultDispatcherProvider
import hu.orszem.core.common.DispatcherProvider
import hu.orszem.core.network.OrszemApi
import hu.orszem.core.network.OrszemApiFactory
import hu.orszem.publicapp.BuildConfig
import hu.orszem.publicapp.location.SettlementLocationProvider
import java.time.Clock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun orszemApi(): OrszemApi =
        OrszemApiFactory.create(baseUrl = BuildConfig.API_BASE_URL, debug = BuildConfig.DEBUG)

    @Provides
    fun dispatchers(): DispatcherProvider = DefaultDispatcherProvider

    @Provides
    @Singleton
    fun clock(): Clock = Clock.systemDefaultZone()

    @Provides
    @Singleton
    fun settlementLocationProvider(@ApplicationContext context: Context): SettlementLocationProvider =
        SettlementLocationProvider(context)
}
