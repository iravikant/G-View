package com.acoder.gallery.domain.usecase
import androidx.paging.Pager
import androidx.paging.PagingConfig
import com.acoder.gallery.domain.model.*
import com.acoder.gallery.domain.repository.MediaRepository
import javax.inject.Inject
class CreateMediaPagerUseCase @Inject constructor(private val repository:MediaRepository){operator fun invoke(sort:SortOrder,filter:MediaFilter,query:String,albumId:String?)=Pager(PagingConfig(pageSize=50,initialLoadSize=50,prefetchDistance=10,enablePlaceholders=false)){repository.pagingSource(sort,filter,query,albumId)}.flow}
