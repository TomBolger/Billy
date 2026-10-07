package com.tombo.billyassistant.companion.agent.tools

import org.junit.Assert.assertEquals
import org.junit.Test

class GeminiImageReplyTest {
    @Test fun cloudPhotoWithReadyImageDoesNotClaimItCannotDisplay() {
        assertEquals("I found a photo from Paris in 2021.", GeminiImageReply.forWatch(
            "I found a photo from Paris in 2021. I can't display it on your screen.", true))
    }

    @Test fun preservesRealFailuresWithoutAnImage() {
        val text = "I found a photo, but I couldn't show it on your screen."
        assertEquals(text, GeminiImageReply.forWatch(text, false))
    }

    @Test fun keepsUsefulPhotoDescriptionAndUnrelatedLimitations() {
        val text = "Your dog is on the beach. I cannot identify the people in this photo."
        assertEquals(text, GeminiImageReply.forWatch(text, true))
    }

    @Test fun sameSentenceContradictionGetsNeutralCaption() {
        assertEquals("Photo from your Gemini account.", GeminiImageReply.forWatch(
            "I found a photo in Google Photos, but I'm unable to show you the picture here.", true))
    }
}
