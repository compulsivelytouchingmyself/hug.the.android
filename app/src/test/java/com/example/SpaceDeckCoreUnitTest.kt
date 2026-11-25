package com.example

import com.example.data.ProfileCodec
import com.example.model.ComponentField
import com.example.model.ComponentKind
import com.example.model.DiscoveryProvenance
import com.example.model.EndpointManifest
import com.example.model.FieldCustomization
import com.example.model.HardwareInfo
import com.example.model.ManifestJsonCodec
import com.example.model.RuntimeStage
import com.example.model.SpaceIdentity
import com.example.model.SpaceManifest
import com.example.model.SpaceProfile
import com.example.network.PythonSourceAnalyzer
import com.example.network.SpaceIdentifierParser
import com.example.security.AllowedHosts
import com.example.security.TokenRedactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpaceDeckCoreUnitTest {

    @Test
    fun parsesAllSupportedSpaceIdentifierFormats() {
        val fromSlug = SpaceIdentifierParser.parse("victor/LongCat-Video-Avatar-1.6")
        assertNotNull(fromSlug)
        assertEquals("victor/LongCat-Video-Avatar-1.6", fromSlug!!.spaceId)
        assertEquals("victor-longcat-video-avatar-1-6", fromSlug.subdomain)

        val fromHubUrl = SpaceIdentifierParser.parse("https://huggingface.co/spaces/mrfakename/voice-isolator")
        assertNotNull(fromHubUrl)
        assertEquals("mrfakename/voice-isolator", fromHubUrl!!.spaceId)
        assertEquals("https://mrfakename-voice-isolator.hf.space", fromHubUrl.defaultHostUrl)

        val fromDirectHost = SpaceIdentifierParser.parse("https://mrfakename-voice-isolator.hf.space/?__theme=dark")
        assertNotNull(fromDirectHost)
        assertEquals("https://mrfakename-voice-isolator.hf.space", fromDirectHost!!.defaultHostUrl)
    }

    @Test
    fun parsesReadmeFrontmatterAppFile() {
        val readme = """
            ---
            title: Voice Isolator
            emoji: 🎙️
            sdk: gradio
            sdk_version: 5.9.1
            app_file: server/main_ui.py
            suggested_hardware: zero-a10g
            ---
            # Docs
        """.trimIndent()
        val meta = SpaceIdentifierParser.parseReadmeFrontmatter(readme)
        assertEquals("Voice Isolator", meta.title)
        assertEquals("server/main_ui.py", meta.appFile)
        assertEquals("5.9.1", meta.sdkVersion)
        assertEquals("zero-a10g", meta.suggestedHardware)
    }

    @Test
    fun detectsStaticAndDynamicZeroGpuDecoratorsAndProvisionalEndpoints() {
        val py = """
            import gradio as gr
            import spaces

            def calc_gpu_time(audio, steps):
                return int(steps * 2)

            @spaces.GPU(duration=90, size="large")
            def isolate_voice(audio_in, strength):
                return audio_in

            @spaces.GPU(duration=calc_gpu_time)
            def batch_process(prompt_box):
                return prompt_box

            with gr.Blocks() as demo:
                audio_in = gr.Audio(label="Input Audio")
                strength = gr.Slider(label="Strength", minimum=0.0, maximum=1.0, step=0.1, value=0.5)
                audio_out = gr.Audio(label="Clean Vocals")
                run_btn = gr.Button("Run")
                run_btn.click(fn=isolate_voice, inputs=[audio_in, strength], outputs=[audio_out], api_name="isolate")
        """.trimIndent()

        val analysis = PythonSourceAnalyzer.analyze(py, "app.py")
        assertTrue(analysis.detectedImportsZeroGpu)
        assertEquals(2, analysis.decorators.size)

        val first = analysis.decorators[0]
        assertEquals("isolate_voice", first.functionName)
        assertEquals(90, first.declaredDurationSeconds)
        assertEquals("large", first.allocationSize)
        assertFalse(first.isDynamicDuration)

        val second = analysis.decorators[1]
        assertEquals("batch_process", second.functionName)
        assertTrue(second.isDynamicDuration)
        assertEquals("calc_gpu_time", second.dynamicDurationExpression)

        assertEquals(1, analysis.provisionalEndpoints.size)
        val ep = analysis.provisionalEndpoints.first()
        assertEquals("/isolate", ep.apiName)
        assertEquals(2, ep.inputs.size)
        assertEquals(ComponentKind.AUDIO, ep.inputs[0].kind)
        assertEquals(ComponentKind.SLIDER, ep.inputs[1].kind)
        assertEquals(90, ep.zeroGpuDecorator?.declaredDurationSeconds)
    }

    @Test
    fun enforcesHostAllowlistAndTokenRedaction() {
        assertTrue(AllowedHosts.isTrustedForCredentials("https://huggingface.co/api/whoami-v2"))
        assertTrue(AllowedHosts.isTrustedForCredentials("https://mrfakename-voice-isolator.hf.space/gradio_api/info"))
        assertFalse(AllowedHosts.isTrustedForCredentials("http://huggingface.co/api/whoami-v2"))
        assertFalse(AllowedHosts.isTrustedForCredentials("https://evil.example.com/hf.space"))
        assertFalse(AllowedHosts.isAllowedNetworkTarget("https://127.0.0.1/admin"))
        assertFalse(AllowedHosts.isAllowedNetworkTarget("https://169.254.169.254/latest/meta-data"))

        val redacted = TokenRedactor.sanitize(
            "Failed Authorization: Bearer hf_abcdefghijklmnop12345 url=https://victor-app.hf.space/call/run?__sign=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyMTIzNDU2In0.abcdef1234567890"
        )
        assertFalse(redacted.contains("hf_abcdef"))
        assertFalse(redacted.contains("eyJhbGci"))
        assertTrue(redacted.contains("__sign=[REDACTED]"))
        assertTrue(redacted.contains("[REDACTED]"))

        val signedTrusted = com.example.network.GradioNativeClient.appendSignParam(
            "https://victor-longcat.hf.space/gradio_api/call/predict",
            "jwt.token.val"
        )
        assertTrue(signedTrusted.endsWith("?__sign=jwt.token.val"))

        val signedUntrusted = com.example.network.GradioNativeClient.appendSignParam(
            "https://evil.example.com/gradio_api/call/predict",
            "jwt.token.val"
        )
        assertFalse(signedUntrusted.contains("__sign="))
    }

    @Test
    fun roundTripsNormalizedManifestAndCredentialFreeProfile() {
        val manifest = SpaceManifest(
            identity = SpaceIdentity(
                spaceId = "mrfakename/voice-isolator",
                author = "mrfakename",
                repoName = "voice-isolator",
                title = "Voice Isolator",
                subdomain = "mrfakename-voice-isolator",
                hostUrl = "https://mrfakename-voice-isolator.hf.space",
                runtimeStage = RuntimeStage.RUNNING
            ),
            hardware = HardwareInfo(current = "zero-a10g", requested = "zero-a10g", isZeroGpu = true),
            endpoints = listOf(
                EndpointManifest(
                    id = "named_isolate_0",
                    apiName = "/isolate",
                    fnIndex = 0,
                    label = "isolate",
                    inputs = listOf(
                        ComponentField(id = "in_0", parameterName = "audio", label = "Audio", gradioType = "audio", kind = ComponentKind.AUDIO)
                    )
                )
            ),
            provenance = DiscoveryProvenance(usedLiveApi = true, usedRuntimeConfig = true, usedRepoSource = true)
        )
        val encoded = ManifestJsonCodec.toJson(manifest)
        val decoded = ManifestJsonCodec.fromJson(encoded)
        assertEquals(manifest.identity.spaceId, decoded.identity.spaceId)
        assertEquals(1, decoded.endpoints.size)
        assertEquals(ComponentKind.AUDIO, decoded.endpoints.first().inputs.first().kind)

        val profile = SpaceProfile(
            spaceId = "mrfakename/voice-isolator",
            favoriteEndpoints = listOf("named_isolate_0"),
            customizations = mapOf("in_0" to FieldCustomization("in_0", orderIndex = 0, locked = true, advanced = false))
        )
        val profJson = ProfileCodec.encodeProfile(profile)
        assertTrue(profJson.contains("\"credentialsIncluded\": false"))
        val decodedProf = ProfileCodec.decodeProfile(profJson)
        assertEquals(true, decodedProf.customizations["in_0"]?.locked)
    }
}
