package app.folio.android.ui

import app.folio.core.model.FailureReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolioStringsTest {

    @Test
    fun `no failure reason leaks its enum name to the user`() {
        FailureReason.entries.forEach { reason ->
            val text = FolioStrings.explain(reason)
            assertFalse(
                "$reason leaked machinery into the UI: $text",
                text.contains('_') || text == reason.name,
            )
            assertTrue("$reason has no explanation", text.length > 10)
            assertTrue("$reason should read as a sentence", text.first().isUpperCase())
        }
    }

    @Test
    fun `every pipeline stage maps to designed copy`() {
        val stages = listOf(
            "importing", "detectingFormat", "extracting", "ocr",
            "detectingStructure", "normalizing", "ready",
        )
        stages.forEach { stage ->
            val text = FolioStrings.importStage(stage)
            assertFalse("stage name $stage reached the UI", text == stage)
            assertTrue(
                "unexpected copy for $stage: $text",
                text in setOf(
                    FolioStrings.PREPARING,
                    FolioStrings.EXTRACTING_TEXT,
                    FolioStrings.DETECTING_CHAPTERS,
                    FolioStrings.BOOK_READY,
                ),
            )
        }
    }

    @Test
    fun `ocr is described as extracting text, never as OCR`() {
        // The brief is explicit: no unnecessary technical terminology.
        assertEquals(FolioStrings.EXTRACTING_TEXT, FolioStrings.importStage("ocr"))
        assertFalse(FolioStrings.importStage("ocr").contains("OCR", ignoreCase = true))
    }

    @Test
    fun `an unknown stage falls back to a calm message rather than blank`() {
        assertEquals(FolioStrings.PREPARING, FolioStrings.importStage("something-new"))
    }

    @Test
    fun `a reminders row never claims to be on when the phone will not deliver`() {
        // The row the reader looks at to check whether reminders work. Saying "On"
        // while Android refuses to deliver is the one failure mode that produces a
        // reader quietly wondering why Folio stopped reminding them, with every
        // screen in the app agreeing that it should be.
        assertEquals(FolioStrings.REMINDERS_OFF, FolioStrings.reminderStatus(false, canPost = true))
        assertEquals(FolioStrings.REMINDERS_OFF, FolioStrings.reminderStatus(false, canPost = false))
        assertEquals(FolioStrings.REMINDERS_ON, FolioStrings.reminderStatus(true, canPost = true))
        assertEquals(
            FolioStrings.REMINDERS_BLOCKED,
            FolioStrings.reminderStatus(true, canPost = false),
        )
    }

    @Test
    fun `the reminder copy says what it does without naming any machinery`() {
        val visible = listOf(
            FolioStrings.REMINDERS, FolioStrings.REMINDER_TIME, FolioStrings.REMINDER_DAILY,
            FolioStrings.REMINDER_STREAK, FolioStrings.REMINDERS_BLOCKED,
            FolioStrings.REMINDER_INVITE_TITLE, FolioStrings.REMINDER_INVITE_BODY,
            FolioStrings.REMINDER_INVITE_YES, FolioStrings.REMINDER_INVITE_NO,
            FolioStrings.REMINDER_CHANNEL, FolioStrings.REMINDER_CHANNEL_EXPLAINER,
        )
        visible.forEach { text ->
            assertTrue("a reminder string is blank", text.isNotBlank())
            assertFalse("machinery leaked into \"$text\"", '_' in text)
            assertFalse(
                "a reminder string shouts: \"$text\"",
                '!' in text,
            )
            assertTrue("\"$text\" does not read as a phrase", text.first().isUpperCase())
        }
    }

    @Test
    fun `the offer states the promise the feature is built on`() {
        // The reader is being asked to allow notifications. What makes that a fair
        // ask is the guarantee that a day they have read is a day Folio is silent,
        // so that guarantee has to be in the words they are shown, not only in the
        // code that honours it.
        assertTrue(
            "the invitation does not mention days the reader has already read",
            "already read" in FolioStrings.REMINDER_INVITE_BODY,
        )
        assertTrue(
            "the invitation does not say it can be turned off",
            "turn it off" in FolioStrings.REMINDER_INVITE_BODY,
        )
    }

    @Test
    fun `greeting covers the whole clock`() {
        (0..23).forEach { hour ->
            assertTrue("hour $hour has no greeting", FolioStrings.greeting(hour).isNotBlank())
        }
        assertEquals("Good morning", FolioStrings.greeting(9))
        assertEquals("Good afternoon", FolioStrings.greeting(14))
        assertEquals("Good evening", FolioStrings.greeting(20))
    }
}
