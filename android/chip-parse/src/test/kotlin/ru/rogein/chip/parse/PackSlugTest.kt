package ru.rogein.chip.parse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PackSlugTest {
    @Test
    fun conflictOnlyWhenBothKnownAndDifferent() {
        assertNull(packSlugConflict("", ""))
        assertNull(packSlugConflict("", "event-a"))
        assertNull(packSlugConflict("event-a", ""))
        assertNull(packSlugConflict("event-a", "event-a"))
        val err = packSlugConflict("event-a", "event-b")
        assertTrue(err != null && err.contains("event-a") && err.contains("event-b"))
    }

    @Test
    fun startPackParsesSlug() {
        val pack = parseStartPackJson(
            """
            {
              "revision": 1,
              "sport": "orient",
              "slug": "forest-2026",
              "courses": [],
              "people": []
            }
            """.trimIndent(),
        )
        assertEquals("forest-2026", pack.slug)
    }

    @Test
    fun eventPackParsesSlug() {
        val pack = parseEventPackJson(
            """
            {
              "sport": "rogaine",
              "slug": "rogaine-cup",
              "title": "Кубок",
              "settings": {},
              "formats": [],
              "people": []
            }
            """.trimIndent(),
        )
        assertEquals("rogaine-cup", pack.slug)
    }

    @Test
    fun fixturePacksExposeSlug() {
        assertEquals("orient-demo", parseStartPackJson(loadFixture("orient/start-pack.json")).slug)
        assertEquals("rogaine-demo", parseEventPackJson(loadFixture("rogaine/event-pack.json")).slug)
    }

    @Test
    fun missingSlugDefaultsEmpty() {
        assertEquals(
            "",
            parseStartPackJson("""{"revision":1,"sport":"orient","courses":[],"people":[]}""").slug,
        )
    }

    private fun loadFixture(path: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(path)) { "missing fixture $path" }
            .bufferedReader()
            .use { it.readText() }
}
