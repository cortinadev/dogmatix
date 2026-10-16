package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionHistoryRulesTest {

    @Test
    fun `undo is offered for done and failed entries with a handler`() {
        assertTrue(ActionHistoryRules.offersUndo(ActionState.DONE, UndoKinds.TRASH))
        assertTrue(ActionHistoryRules.offersUndo(ActionState.UNDO_FAILED, UndoKinds.MOVE))
        assertFalse(ActionHistoryRules.offersUndo(ActionState.DONE, null))
        assertFalse(ActionHistoryRules.offersUndo(ActionState.UNDONE, UndoKinds.TRASH))
        assertFalse(ActionHistoryRules.offersUndo(ActionState.EXPIRED, UndoKinds.TRASH))
        assertFalse(ActionHistoryRules.offersUndo(ActionState.SUPERSEDED, UndoKinds.SOURCES))
        assertFalse(ActionHistoryRules.offersUndo(ActionState.UNDOING, UndoKinds.MOVE))
    }

    @Test
    fun `only imports chain`() {
        assertTrue(ActionHistoryRules.isChained(ActionTypes.SOURCES_IMPORT))
        assertFalse(ActionHistoryRules.isChained(ActionTypes.DOWNLOAD_DELETE))
    }

    @Test
    fun `unknown stored states read as expired`() {
        assertEquals(ActionState.UNDONE, ActionState.of("UNDONE"))
        assertEquals(ActionState.EXPIRED, ActionState.of("SOMETHING_NEW"))
        assertEquals(ActionState.EXPIRED, ActionState.of(null))
    }
}
