package io.music_assistant.client.player.sendspin

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SendspinReconnectResumePolicyTest {
    @Test
    fun `transport reconnect resumes only a timely stream with no continuity veto`() {
        assertTrue(shouldAutoResumeAfterReconnect(true, 0, true))
        assertTrue(shouldAutoResumeAfterReconnect(true, 8, true))
        assertFalse(shouldAutoResumeAfterReconnect(false, 0, true))
        assertFalse(shouldAutoResumeAfterReconnect(true, 9, true))
        assertFalse(shouldAutoResumeAfterReconnect(true, 0, false))
    }
}
