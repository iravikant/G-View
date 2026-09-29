package com.acoder.gallery.di
import android.content.Context
import com.acoder.gallery.data.mediastore.MediaStoreRepository
import com.acoder.gallery.data.preferences.PreferencesRepository
import com.acoder.gallery.domain.repository.MediaRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Singleton
@Module @InstallIn(SingletonComponent::class) object AppModule {
 @Provides @Singleton fun media(@ApplicationContext c:Context):MediaRepository=MediaStoreRepository(c)
 @Provides @Singleton fun prefs(@ApplicationContext c:Context)=PreferencesRepository(c)
}
