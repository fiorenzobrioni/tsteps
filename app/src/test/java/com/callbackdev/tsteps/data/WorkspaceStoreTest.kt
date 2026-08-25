package com.callbackdev.tsteps.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private fun store(file: File) = WorkspaceStore(
        PreferenceDataStoreFactory.create(scope = scope) { file }
    )

    @After
    fun tearDown() {
        scope.cancel()
    }

    /**
     * Fase 17: the HELP.md hint is workspace state on purpose — it must not be a
     * `settings.config` key that `$ git restore` would bring back to a veteran.
     */
    @Test
    fun `the help hint shows until it is dismissed`() = runBlocking {
        val store = store(tmp.newFile("ws-help.preferences_pb"))
        assertEquals(false, store.helpHintDismissed.first())

        store.dismissHelpHint()

        assertEquals(true, store.helpHintDismissed.first())
    }

    @Test
    fun `first run opens on the JSON file`() = runBlocking {
        val store = store(tmp.newFile("ws.preferences_pb"))
        assertEquals(MainEditorFile.JSON, store.mainActiveFile.first())
    }

    @Test
    fun `selecting the README is observed`() = runBlocking {
        val store = store(tmp.newFile("ws.preferences_pb"))
        store.setMainActiveFile(MainEditorFile.README)
        assertEquals(MainEditorFile.README, store.mainActiveFile.first())
    }

    @Test
    fun `the active file survives a restart (new store on the same file)`() = runBlocking {
        val file = tmp.newFile("ws.preferences_pb")
        // DataStore allows one active instance per file: the first scope must be
        // FULLY torn down (cancelAndJoin, cancel alone is async) to simulate
        // process death before the "restarted" instance opens the same file
        val firstRunJob = SupervisorJob()
        val firstRun = CoroutineScope(Dispatchers.IO + firstRunJob)
        WorkspaceStore(PreferenceDataStoreFactory.create(scope = firstRun) { file })
            .setMainActiveFile(MainEditorFile.README)
        firstRunJob.cancelAndJoin()
        assertEquals(MainEditorFile.README, store(file).mainActiveFile.first())
    }
}
