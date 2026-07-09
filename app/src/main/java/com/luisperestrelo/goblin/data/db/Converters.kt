package com.luisperestrelo.goblin.data.db

import androidx.room.TypeConverter
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class Converters {

    @TypeConverter
    fun stringListToJson(value: List<String>): String =
        Json.encodeToString(ListSerializer(String.serializer()), value)

    @TypeConverter
    fun jsonToStringList(value: String): List<String> =
        Json.decodeFromString(ListSerializer(String.serializer()), value)
}
