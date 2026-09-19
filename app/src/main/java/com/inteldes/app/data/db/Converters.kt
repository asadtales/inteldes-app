package com.inteldes.app.data.db

import androidx.room.TypeConverter
import com.inteldes.app.data.model.AiProvider
import com.inteldes.app.data.model.QuestionStatus
import com.inteldes.app.data.model.RecordingKind
import com.inteldes.app.data.model.RecordingStatus
import com.inteldes.app.data.model.WhisperEngine

class Converters {
    @TypeConverter
    fun fromRecordingStatus(v: RecordingStatus): String = v.name
    @TypeConverter
    fun toRecordingStatus(v: String): RecordingStatus = RecordingStatus.valueOf(v)

    @TypeConverter
    fun fromRecordingKind(v: RecordingKind): String = v.name
    @TypeConverter
    fun toRecordingKind(v: String): RecordingKind = RecordingKind.valueOf(v)

    @TypeConverter
    fun fromWhisperEngine(v: WhisperEngine): String = v.name
    @TypeConverter
    fun toWhisperEngine(v: String): WhisperEngine = WhisperEngine.valueOf(v)

    @TypeConverter
    fun fromAiProvider(v: AiProvider?): String? = v?.name
    @TypeConverter
    fun toAiProvider(v: String?): AiProvider? = v?.let { AiProvider.valueOf(it) }

    @TypeConverter
    fun fromQuestionStatus(v: QuestionStatus): String = v.name
    @TypeConverter
    fun toQuestionStatus(v: String): QuestionStatus = QuestionStatus.valueOf(v)
}
