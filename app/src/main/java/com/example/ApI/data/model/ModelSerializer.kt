package com.example.ApI.data.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

// Keys used for pricing fields in JSON
private val PRICING_KEYS = setOf("name", "min_points", "points", "1k_input_points", "1k_output_points")

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
                val pricing = extractPricing(element)

                if (name != null) {
                    Model.ComplexModel(
                        name = name,
                        min_points = minPoints,
                        pricing = pricing,
                        other_fields = element.filterKeys { it !in PRICING_KEYS }
                    )
                } else {
                    Model.ComplexModel(
                        name = element.toString(),
                        min_points = minPoints,
                        pricing = pricing
                    )
                }
            }
            else -> Model.SimpleModel(name = element.toString())
        }
    }

    /**
     * Extracts PoePricing from a JSON object if pricing fields are present
     */
    private fun extractPricing(element: JsonObject): PoePricing? {
        val minPoints = element["min_points"]?.jsonPrimitive?.intOrNull
        val points = element["points"]?.jsonPrimitive?.intOrNull
        val inputPointsPer1k = element["1k_input_points"]?.jsonPrimitive?.doubleOrNull
        val outputPointsPer1k = element["1k_output_points"]?.jsonPrimitive?.doubleOrNull

        // Return null if no pricing info is available
        if (minPoints == null && points == null && inputPointsPer1k == null && outputPointsPer1k == null) {
            return null
        }

        return PoePricing(
            min_points = minPoints,
            points = points,
            input_points_per_1k = inputPointsPer1k,
            output_points_per_1k = outputPointsPer1k
        )
    }

    override fun serialize(encoder: Encoder, value: Model) {
        require(encoder is JsonEncoder)
        when (value) {
            is Model.SimpleModel -> encoder.encodeString(value.name)
            is Model.ComplexModel -> {
                val json = buildJsonObject {
                    value.name?.let { put("name", it) }
                    value.min_points?.let { put("min_points", it) }
                    // Serialize pricing fields
                    value.pricing?.let { pricing ->
                        pricing.points?.let { put("points", it) }
                        pricing.input_points_per_1k?.let { put("1k_input_points", it) }
                        pricing.output_points_per_1k?.let { put("1k_output_points", it) }
                    }
                    value.other_fields?.forEach { (key, element) ->
                        put(key, element)
                    }
                }
                encoder.encodeJsonElement(json)
            }
        }
    }
}
