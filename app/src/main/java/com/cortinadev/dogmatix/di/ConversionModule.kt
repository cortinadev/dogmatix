package com.cortinadev.dogmatix.di

import com.cortinadev.dogmatix.data.service.ChdConverter
import com.cortinadev.dogmatix.data.service.RomConversionService
import com.cortinadev.dogmatix.data.service.RomConverter
import com.cortinadev.dogmatix.data.service.RvzConverter
import com.cortinadev.dogmatix.data.service.UndoHandler
import com.cortinadev.dogmatix.data.service.XisoConverter
import com.cortinadev.dogmatix.util.UndoKinds
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds
import dagger.multibindings.StringKey

/**
 * ROM conversion: each [RomConverter] is bound into the set [RomConversionService] runs, so a new
 * format (CHD, RVZ) is one `@Binds @IntoSet` here; and the undo of a conversion made from Downloads.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ConversionModule {

    @Multibinds
    abstract fun romConverters(): Set<RomConverter>

    @Binds @IntoSet
    abstract fun xiso(converter: XisoConverter): RomConverter

    @Binds @IntoSet
    abstract fun rvz(converter: RvzConverter): RomConverter

    @Binds @IntoSet
    abstract fun chd(converter: ChdConverter): RomConverter

    @Binds @IntoMap @StringKey(UndoKinds.CONVERSION)
    abstract fun conversionUndo(service: RomConversionService): UndoHandler
}
