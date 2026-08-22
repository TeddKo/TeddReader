package com.tedd.teddreader.core.datastore

/**
 * The preferences file name, shared by both platforms.
 *
 * JSON rather than a platform preferences API because the same file has to be written and read by Android
 * and iOS from common code; the name lives here so neither platform builder can drift onto its own file and
 * silently lose a reader's settings.
 */
const val ReaderPreferencesFileName = "reader_preferences.json"
