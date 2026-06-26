package keiyoushi.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceScreen
import androidx.preference.ListPreference
import androidx.preference.SwitchPreferenceCompat
import uy.kohesive.injekt.Injekt
import kotlin.reflect.KProperty
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.Response

class LazyMutable<T>(private val initializer: () -> T) {
    private var value: T? = null
    operator fun getValue(thisRef: Any?, property: KProperty<*>): T {
        if (value == null) {
            value = initializer()
        }
        return value!!
    }
    operator fun setValue(thisRef: Any?, property: KProperty<*>, newValue: T) {
        value = newValue
    }
}

fun eu.kanade.tachiyomi.animesource.AnimeSource.getPreferencesLazy(): Lazy<SharedPreferences> {
    return lazy {
        val context = Injekt.getInstance<Context>(Context::class.java)
        context.getSharedPreferences("source_$id", Context.MODE_PRIVATE)
    }
}

inline fun <reified T> SharedPreferences.delegate(key: String, defaultValue: T): SharedPreferencesDelegate<T> {
    return SharedPreferencesDelegate(this, key, defaultValue)
}

class SharedPreferencesDelegate<T>(
    private val prefs: SharedPreferences,
    private val key: String,
    private val defaultValue: T
) {
    operator fun getValue(thisRef: Any?, property: KProperty<*>): T {
        @Suppress("UNCHECKED_CAST")
        return when (defaultValue) {
            is String -> prefs.getString(key, defaultValue) as T
            is Boolean -> prefs.getBoolean(key, defaultValue) as T
            is Int -> prefs.getInt(key, defaultValue) as T
            is Long -> prefs.getLong(key, defaultValue) as T
            is Float -> prefs.getFloat(key, defaultValue) as T
            else -> throw IllegalArgumentException("Unsupported preference type")
        }
    }
}

fun String.decodeHex(): ByteArray {
    val len = length
    val data = ByteArray(len / 2)
    var i = 0
    while (i < len) {
        data[i / 2] = ((Character.digit(this[i], 16) shl 4) + Character.digit(this[i + 1], 16)).toByte()
        i += 2
    }
    return data
}

inline fun <reified T : Any> Response.parseAs(): T {
    val bodyStr = this.body.string()
    val json = Injekt.getInstance<Json>(Json::class.java)
    return json.decodeFromString<T>(bodyStr)
}

fun <T, R> List<T>.parallelCatchingFlatMapBlocking(transform: suspend (T) -> Iterable<R>): List<R> {
    return kotlinx.coroutines.runBlocking {
        coroutineScope {
            map { item ->
                async {
                    try {
                        transform(item)
                    } catch (e: Exception) {
                        Log.e("Miruro", "Error in parallelCatchingFlatMapBlocking: ${e.message}", e)
                        emptyList<R>()
                    }
                }
            }.awaitAll().flatten()
        }
    }
}

fun PreferenceScreen.addListPreference(
    key: String,
    title: String,
    entries: List<String>,
    entryValues: List<String>,
    default: String,
    summary: String,
    onComplete: ((String) -> Unit)? = null
) {
    val context = this.context
    val pref = ListPreference(context).apply {
        this.key = key
        this.title = title
        this.entries = entries.toTypedArray()
        this.entryValues = entryValues.toTypedArray()
        this.setDefaultValue(default)
        this.summary = summary
        setOnPreferenceChangeListener { _, newValue ->
            val strVal = newValue as? String ?: ""
            onComplete?.invoke(strVal)
            true
        }
    }
    addPreference(pref)
}

fun PreferenceScreen.addSwitchPreference(
    key: String,
    title: String,
    default: Boolean,
    summary: String,
    onComplete: ((Boolean) -> Unit)? = null
) {
    val context = this.context
    val pref = SwitchPreferenceCompat(context).apply {
        this.key = key
        this.title = title
        this.setDefaultValue(default)
        this.summary = summary
        setOnPreferenceChangeListener { _, newValue ->
            val boolVal = newValue as? Boolean ?: false
            onComplete?.invoke(boolVal)
            true
        }
    }
    addPreference(pref)
}

fun PreferenceScreen.getSwitchPreference(
    key: String,
    default: Boolean,
    title: String,
    summary: String,
    enabled: Boolean
): SwitchPreferenceCompat {
    val context = this.context
    return SwitchPreferenceCompat(context).apply {
        this.key = key
        this.title = title
        this.setDefaultValue(default)
        this.summary = summary
        this.isEnabled = enabled
    }
}
