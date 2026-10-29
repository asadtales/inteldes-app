package com.inteldes.app.data.db

import androidx.room.TypeConverter
import com.inteldes.app.data.model.AiProvider
import com.inteldes.app.data.model.NotulensiDensity
import com.inteldes.app.data.model.RecordingStatus
import com.inteldes.app.data.model.SectionType
import com.inteldes.app.data.model.WhisperEngine

class Converters {
    @TypeConverter
    fun fromRecordingStatus(v: RecordingStatus): String = v.name
    @TypeConverter
    fun toRecordingStatus(v: String): RecordingStatus = RecordingStatus.valueOf(v)

    @TypeConverter
    fun fromWhisperEngine(v: WhisperEngine): String = v.name
    @TypeConverter
    fun toWhisperEngine(v: String): WhisperEngine = WhisperEngine.valueOf(v)

    @TypeConverter
    fun fromAiProvider(v: AiProvider?): String? = v?.name
    @TypeConverter
    fun toAiProvider(v: String?): AiProvider? = v?.let { AiProvider.valueOf(it) }

    @TypeConverter
    fun fromSectionType(v: SectionType): String = v.name
    @TypeConverter
    fun toSectionType(v: String): SectionType = SectionType.valueOf(v)

    @TypeConverter
    fun fromNotulensiDensity(v: NotulensiDensity?): String? = v?.name
    @TypeConverter
    fun toNotulensiDensity(v: String?): NotulensiDensity? = v?.let { NotulensiDensity.valueOf(it) }
}
