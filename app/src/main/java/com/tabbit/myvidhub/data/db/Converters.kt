package com.tabbit.myvidhub.data.db

import androidx.room.TypeConverter
import com.tabbit.myvidhub.data.model.SourceKind

class Converters {

    @TypeConverter
    fun fromKind(kind: SourceKind): String = kind.name

    @TypeConverter
    fun toKind(value: String): SourceKind =
        runCatching { SourceKind.valueOf(value) }.getOrDefault(SourceKind.TVBOX)
}
