package com.aurora.music.localization

import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.platform.app.InstrumentationRegistry
import com.aurora.music.R
import com.aurora.music.model.LibraryFilter
import com.aurora.music.model.releaseTypeLabel
import com.aurora.music.navigation.topLevelDestinations
import com.aurora.music.ui.screens.settings.SettingsDestinations
import com.aurora.music.ui.theme.AccentPresets
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class LocalizationDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun configuration(language: String) = Configuration(context.resources.configuration).apply {
        setLocales(LocaleList(Locale.forLanguageTag(language)))
    }

    @Test fun japaneseUsesTheCorrectCountForm() {
        val resources = context.createConfigurationContext(configuration("ja")).resources
        val japaneseOther = "%1$d件のトラック"
        val expected = listOf(0, 1, 2, 5).associateWith { japaneseOther.replace("%1$d", it.toString()) }
        expected.forEach { (count, text) ->
            assertEquals(text, resources.getQuantityString(R.plurals.track_count, count, count))
        }
        val english = context.createConfigurationContext(configuration("en")).resources
        assertEquals("1 track", english.getQuantityString(R.plurals.track_count, 1, 1))
        assertEquals("2 tracks", english.getQuantityString(R.plurals.track_count, 2, 2))
    }

    @Test fun existingLabelsRefreshWithoutChangingMediaIdentifiers() {
        val original = Configuration(AppStrings.context.resources.configuration)
        try {
            AppStrings.useConfiguration(configuration("en"))
            val navigation = topLevelDestinations
            val destination = SettingsDestinations.about
            val filter = LibraryFilter.ALBUMS
            val accent = AccentPresets.first().name
            assertEquals("Home", navigation.first().label)
            assertEquals("Albums", filter.label)
            AppStrings.useConfiguration(configuration("ja"))
            assertEquals("ホーム", navigation.first().label)
            assertEquals("アルバム", filter.label)
            assertEquals("Auroraについて", destination.label)
            assertNotEquals(accent, AccentPresets.first().name)
            assertEquals("Single", releaseTypeLabel("single"))
            assertEquals("シングル", releaseTypeLabel("single").localizedMediaType())
        } finally {
            AppStrings.useConfiguration(original)
        }
    }

    @Test fun translationsRetainFormattingArguments() {
        val english = context.createConfigurationContext(configuration("en")).resources
        val japanese = context.createConfigurationContext(configuration("ja")).resources
        val format = Regex("(?<!%)%(?:[0-9]+\\$)?[-+0-9.]*[sdf]")
        val fields = R.string::class.java.fields.filter { it.name.startsWith("text_") }
        assertTrue(fields.size > 2000)
        fields.forEach { field ->
            val id = field.getInt(null)
            assertEquals(field.name, format.findAll(english.getString(id)).map { it.value }.sorted().toList(),
                format.findAll(japanese.getString(id)).map { it.value }.sorted().toList())
        }
    }
}
