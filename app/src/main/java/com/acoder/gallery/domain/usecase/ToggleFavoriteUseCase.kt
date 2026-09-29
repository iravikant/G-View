package com.acoder.gallery.domain.usecase

import com.acoder.gallery.domain.model.MediaItem
import com.acoder.gallery.domain.repository.MediaOpResult
import com.acoder.gallery.domain.repository.MediaRepository
import javax.inject.Inject

class ToggleFavoriteUseCase @Inject constructor(private val repository: MediaRepository) {
    suspend operator fun invoke(items: List<MediaItem>, favorite: Boolean): MediaOpResult = repository.setFavorite(items, favorite)
}
