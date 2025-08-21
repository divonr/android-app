package com.example.ApI.data.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

object ModelSerializer : KSerializer<Model> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Model")

    override fun deserialize(decoder: Decoder): Model {
        require(decoder is JsonDecoder)
        val element = decoder.decodeJsonElement()
        
        return when {
            element is JsonPrimitive && element.isString -> {
                Model.SimpleModel(name = element.content)
            }
            element is JsonObject -> {
                val name = element["name"]?.jsonPrimitive?.contentOrNull
                val minPoints = element["min_points"]?.jsonPrimitive?.intOrNull
                
                if (name != null) {
                    Model.ComplexModel(
                        name = name,
                        min_points = minPoints,
                        other_fields = element.filterKeys { it != "name" && it != "min_points" }
                    )
                } else {
                    Model.ComplexModel(
                        name = element.toString(),
                        min_points = minPoints
                    )
                }
            }
            else -> Model.SimpleModel(name = element.toString())
        }
    }

    override fun serialize(encoder: Encoder, value: Model) {
        require(encoder is JsonEncoder)
        when (value) {
            is Model.SimpleModel -> encoder.encodeString(value.name)
            is Model.ComplexModel -> {
                val json = buildJsonObject {
                    value.name?.let { put("name", it) }
                    value.min_points?.let { put("min_points", it) }
                    value.other_fields?.forEach { (key, element) ->
                        put(key, element)
                    }
                }
                encoder.encodeJsonElement(json)
            }
        }
    }
}
