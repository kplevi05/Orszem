package hu.orszem.serviceapp.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import hu.orszem.core.common.DefaultDispatcherProvider
import hu.orszem.core.common.DispatcherProvider
import hu.orszem.core.network.OrszemApi
import hu.orszem.core.network.OrszemApiFactory
import hu.orszem.serviceapp.BuildConfig
import hu.orszem.serviceapp.data.AuthInterceptor
import java.time.Clock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun orszemApi(authInterceptor: AuthInterceptor): OrszemApi =
        OrszemApiFactory.create(
            baseUrl = BuildConfig.API_BASE_URL,
            debug = BuildConfig.DEBUG,
            authInterceptor = authInterceptor,
        )

    @Provides
    fun dispatchers(): DispatcherProvider = DefaultDispatcherProvider

    @Provides
    @Singleton
    fun clock(): Clock = Clock.systemUTC()
}
