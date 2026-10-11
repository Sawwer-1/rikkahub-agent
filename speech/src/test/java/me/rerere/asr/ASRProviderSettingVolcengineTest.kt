package me.rerere.asr

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/**
 * Adapted from the ExTV upstream test of the same name. The upstream fork adds a
 * `silenceDurationMs` field and defaults to the `bigmodel_async` endpoint for its
 * server-VAD voice turns (ASRVoiceTurn + pauseCapture), which this fork deliberately
 * did not port. Assertions here pin THIS fork's contract: the classic `bigmodel`
 * bidirectional endpoint, the duration-billed resource id, and voice-mode capability
 * detection.
 */
class ASRProviderSettingVolcengineTest {
    @Test fun `defaults select bidirectional API with voice mode capability`() {
        val provider = ASRProviderSetting.Volcengine()
        assertEquals("wss://openspeech.bytedance.com/api/v3/sauc/bigmodel", provider.websocketUrl)
        assertEquals("volc.seedasr.sauc.duration", provider.resourceId)
        assertTrue(provider.supportsServerVadVoiceMode)
    }

    @Test fun `legacy persisted official endpoint remains unchanged`() {
        val old = Json.decodeFromString<ASRProviderSetting>("""{"type":"volcengine","apiKey":"test-key","websocketUrl":"wss://openspeech.bytedance.com/api/v3/sauc/bigmodel","resourceId":"volc.bigasr.sauc.duration"}""") as ASRProviderSetting.Volcengine
        assertEquals("wss://openspeech.bytedance.com/api/v3/sauc/bigmodel", old.websocketUrl)
        assertEquals("test-key", old.apiKey)
        assertEquals("volc.bigasr.sauc.duration", old.resourceId)
    }

    @Test fun `custom endpoints remain unchanged through copyProvider`() {
        val provider = ASRProviderSetting.Volcengine(websocketUrl = "wss://proxy.example/asr")
        val copy = provider.copyProvider(name = "renamed") as ASRProviderSetting.Volcengine
        assertEquals("wss://proxy.example/asr", copy.websocketUrl)
        assertEquals(provider.resourceId, copy.resourceId)
        assertEquals(provider.language, copy.language)
    }
}
