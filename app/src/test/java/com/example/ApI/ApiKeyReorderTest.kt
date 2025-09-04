package com.example.ApI

import com.example.ApI.data.model.ApiKey
import org.junit.Test
import org.junit.Assert.*

class ApiKeyReorderTest {
    
    @Test
    fun testReorderApiKeys() {
        // Create a sample list of API keys
        val apiKeys = mutableListOf(
            ApiKey(id = "1", provider = "openai", key = "key1"),
            ApiKey(id = "2", provider = "poe", key = "key2"),
            ApiKey(id = "3", provider = "google", key = "key3"),
            ApiKey(id = "4", provider = "openai", key = "key4")
        )
        
        // Test moving item from index 0 to index 2
        val fromIndex = 0
        val toIndex = 2
        
        val item = apiKeys.removeAt(fromIndex)
        apiKeys.add(toIndex, item)
        
        // Verify the order
        assertEquals("2", apiKeys[0].id) // POE moved to first
        assertEquals("3", apiKeys[1].id) // Google moved to second
        assertEquals("1", apiKeys[2].id) // OpenAI (was first) now at third
        assertEquals("4", apiKeys[3].id) // OpenAI2 stays at fourth
    }
    
    @Test
    fun testReorderApiKeysBackward() {
        // Create a sample list of API keys
        val apiKeys = mutableListOf(
            ApiKey(id = "1", provider = "openai", key = "key1"),
            ApiKey(id = "2", provider = "poe", key = "key2"),
            ApiKey(id = "3", provider = "google", key = "key3"),
            ApiKey(id = "4", provider = "openai", key = "key4")
        )
        
        // Test moving item from index 3 to index 1
        val fromIndex = 3
        val toIndex = 1
        
        val item = apiKeys.removeAt(fromIndex)
        apiKeys.add(toIndex, item)
        
        // Verify the order
        assertEquals("1", apiKeys[0].id) // OpenAI1 stays first
        assertEquals("4", apiKeys[1].id) // OpenAI2 moved to second
        assertEquals("2", apiKeys[2].id) // POE moved to third
        assertEquals("3", apiKeys[3].id) // Google moved to fourth
    }
    
    @Test
    fun testCalculateActualIndex() {
        val draggedItemIndex = 0
        val targetIndex = 2
        val indices = listOf(0, 1, 2, 3)
        
        val actualIndices = indices.map { index ->
            when {
                index == draggedItemIndex -> targetIndex
                draggedItemIndex < targetIndex && index > draggedItemIndex && index <= targetIndex -> index - 1
                draggedItemIndex > targetIndex && index >= targetIndex && index < draggedItemIndex -> index + 1
                else -> index
            }
        }
        
        // When dragging item at index 0 to index 2:
        // Item 0 should be at position 2
        // Item 1 should be at position 0 (shifted left)
        // Item 2 should be at position 1 (shifted left)
        // Item 3 should stay at position 3
        assertEquals(2, actualIndices[0])
        assertEquals(0, actualIndices[1])
        assertEquals(1, actualIndices[2])
        assertEquals(3, actualIndices[3])
    }
}
