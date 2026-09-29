package com.acoder.gallery.domain.usecase

import com.acoder.gallery.domain.model.MediaItem
import com.acoder.gallery.domain.repository.MediaOpResult
import com.acoder.gallery.domain.repository.MediaRepository
import javax.inject.Inject

class DeleteMediaUseCase @Inject constructor(private val repository: MediaRepository) {
    suspend operator fun invoke(items: List<MediaItem>): MediaOpResult = repository.requestDelete(items)
}
