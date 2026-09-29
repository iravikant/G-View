package com.acoder.gallery.domain.usecase
import com.acoder.gallery.domain.repository.MediaRepository
import javax.inject.Inject
class GetAlbumsUseCase @Inject constructor(private val repository:MediaRepository){suspend operator fun invoke()=repository.queryAlbums()}
